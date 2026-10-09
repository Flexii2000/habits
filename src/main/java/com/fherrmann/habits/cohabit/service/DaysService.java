package com.fherrmann.habits.cohabit.service;

import com.fherrmann.habits.client.SourceUnavailableException;
import com.fherrmann.habits.cohabit.api.CohabitRef;
import com.fherrmann.habits.cohabit.api.DaySeriesView;
import com.fherrmann.habits.cohabit.api.DayValueView;
import com.fherrmann.habits.cohabit.api.DaysView;
import com.fherrmann.habits.cohabit.model.ChallengeRound;
import com.fherrmann.habits.cohabit.model.ChallengeState;
import com.fherrmann.habits.cohabit.model.Cohabit;
import com.fherrmann.habits.cohabit.model.Member;
import com.fherrmann.habits.cohabit.model.Pause;
import com.fherrmann.habits.cohabit.rules.CohabitEval;
import com.fherrmann.habits.cohabit.rules.DaySeriesCalc;
import com.fherrmann.habits.cohabit.rules.DaySeriesCalc.CheckinFact;
import com.fherrmann.habits.cohabit.rules.DaySeriesCalc.Snapshot;
import com.fherrmann.habits.cohabit.rules.DaySeriesCalc.Span;
import com.fherrmann.habits.cohabit.rules.Evaluations;
import com.fherrmann.habits.cohabit.rules.Texts;
import com.fherrmann.habits.cohabit.store.CohabitStore;
import com.fherrmann.habits.security.Viewer;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tagesreihen aller eigenen Co-Habits fuer das Logbook in Healthy (der Weight Tracker
 * fragt hier je Person nach).
 *
 * <p>Wie {@link ViewService} in drei Schritten: unter dem Lock kopieren, ohne Lock die
 * automatischen Quellen fragen, dann rechnen ({@link DaySeriesCalc}). Bewusst nicht
 * {@code ViewService.fetchFacts}: das holt fuer alle Mitglieder und den Rueckblick der
 * Serien, und das Wochenmittel kennt dort nur Wochenurteile - hier geht es um eine
 * Person, einen Zeitraum, einen Wert je Tag.
 */
@Service
public class DaysService {

    /** Mehr fragt das Logbook nicht (365 Tage plus Vorlauf); groesser waere nur langsamer. */
    public static final int MAX_DAYS = 400;

    private final CohabitStore store;
    private final AutoSources sources;
    private final Clock clock;

    public DaysService(CohabitStore store, AutoSources sources, Clock clock) {
        this.store = store;
        this.sources = sources;
        this.clock = clock;
    }

    private record Item(CohabitRef ref, boolean archived, Snapshot snapshot) {
    }

    public DaysView days(Viewer viewer, LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw Errors.badRequest("Der Zeitraum endet vor seinem Beginn.");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw Errors.badRequest("Höchstens " + MAX_DAYS + " Tage auf einmal.");
        }
        String me = viewer.personId();
        Instant now = Instant.now(clock);
        List<Item> items = store.read(data -> {
            List<Item> list = new ArrayList<>();
            for (Cohabit c : data.cohabits().cohabits) {
                Member m = c.member(me).orElse(null);
                if (m == null) {
                    continue;
                }
                ZoneId zone = CohabitEval.zoneOf(c);
                LocalDate today = LocalDate.ofInstant(now, zone);
                LocalDate last = today;
                if (c.archived && c.archivedAt != null) {
                    LocalDate archivedOn = LocalDate.ofInstant(c.archivedAt, zone);
                    last = archivedOn.isBefore(today) ? archivedOn : today;
                }
                List<Span> pauses = new ArrayList<>();
                for (Pause p : m.pauses) {
                    if (p.from != null && p.to != null) {
                        pauses.add(new Span(p.from, p.to));
                    }
                }
                List<CheckinFact> facts = data.checkins(c.id).stream()
                        .filter(ch -> me.equals(ch.personId))
                        .map(ch -> new CheckinFact(ch.date, ch.kind, ch.value, ch.distanceKm))
                        .toList();
                LocalDate lastHealthDay = m.lastHealthSyncAt == null ? null
                        : LocalDate.ofInstant(m.lastHealthSyncAt, zone);
                Snapshot snapshot = new Snapshot(c.type, c.tracking, c.goal, c.challenge, rounds(c, zone),
                        c.health == null ? null : c.health.metric(), c.auto, Evaluations.memberStart(c, m), last,
                        pauses, facts, lastHealthDay);
                list.add(new Item(CohabitRef.of(c), c.archived, snapshot));
            }
            return list;
        });

        List<DaySeriesView> series = new ArrayList<>();
        for (Item item : items) {
            Snapshot s = item.snapshot();
            Map<LocalDate, Double> auto = Map.of();
            String unavailable = null;
            if (s.auto() != null) {
                LocalDate lo = s.start() == null || s.start().isBefore(from) ? from : s.start();
                LocalDate hi = s.last().isAfter(to) ? to : s.last();
                if (!lo.isAfter(hi)) {
                    try {
                        auto = sources.dayValues(s.auto(), me, lo, hi);
                    } catch (SourceUnavailableException e) {
                        unavailable = e.getMessage();
                    }
                }
            }
            DaySeriesCalc.Series computed = DaySeriesCalc.series(s, auto, from, to);
            List<DayValueView> days = computed.days().entrySet().stream()
                    .map(e -> new DayValueView(e.getKey(), e.getValue()))
                    .toList();
            series.add(new DaySeriesView(item.ref(), item.archived(), computed.kind().name(), computed.unit(),
                    computed.unit() == null ? null : Texts.unitLabel(computed.unit()), s.health(), unavailable, days));
        }
        return new DaysView(from, to, series);
    }

    /**
     * Die Runden einer Challenge: die laufende (bei "wer zuerst das Ziel erreicht" bis zum
     * Tag, an dem sie endete) und alle frueheren.
     */
    static List<Span> rounds(Cohabit c, ZoneId zone) {
        if (c.challenge == null) {
            return List.of();
        }
        List<Span> spans = new ArrayList<>();
        ChallengeState state = c.challengeState;
        if (state != null) {
            for (ChallengeRound r : state.rounds) {
                if (r.start != null && r.end != null) {
                    spans.add(new Span(r.start, endOf(r.end, r.endedAt, zone)));
                }
            }
        }
        if (c.challenge.start() != null && c.challenge.end() != null) {
            spans.add(new Span(c.challenge.start(),
                    endOf(c.challenge.end(), state == null ? null : state.roundEndedAt, zone)));
        }
        return spans;
    }

    private static LocalDate endOf(LocalDate end, Instant endedAt, ZoneId zone) {
        if (endedAt == null) {
            return end;
        }
        LocalDate endedOn = LocalDate.ofInstant(endedAt, zone);
        return endedOn.isBefore(end) ? endedOn : end;
    }
}
