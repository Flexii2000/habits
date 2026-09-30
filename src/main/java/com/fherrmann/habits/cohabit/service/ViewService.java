package com.fherrmann.habits.cohabit.service;

import com.fherrmann.habits.cohabit.api.CohabitDetail;
import com.fherrmann.habits.cohabit.api.CohabitSummary;
import com.fherrmann.habits.cohabit.model.AutoConfig;
import com.fherrmann.habits.cohabit.model.Cohabit;
import com.fherrmann.habits.cohabit.model.Member;
import com.fherrmann.habits.cohabit.model.Pause;
import com.fherrmann.habits.cohabit.rules.AutoFacts;
import com.fherrmann.habits.cohabit.rules.CohabitEval;
import com.fherrmann.habits.cohabit.rules.Evaluations;
import com.fherrmann.habits.cohabit.rules.StreakModel;
import com.fherrmann.habits.cohabit.store.CohabitStore;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Rechnet und baut die Sichten in drei Schritten: unter dem Lock einsammeln, was
 * die automatischen Quellen brauchen; ohne Lock bei den Quellen nachfragen; dann
 * unter dem Lock rechnen. So wartet niemand, waehrend der Kalorienzaehler antwortet.
 */
@Service
public class ViewService {

    private final CohabitStore store;
    private final AutoSources sources;
    private final Clock clock;

    public ViewService(CohabitStore store, AutoSources sources, Clock clock) {
        this.store = store;
        this.sources = sources;
        this.clock = clock;
    }

    public Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }

    /** Die Quellen-Daten je Co-Habit und Person. */
    public record Facts(Map<String, Map<String, AutoFacts>> byCohabit) {

        public static final Facts NONE = new Facts(Map.of());

        public Map<String, AutoFacts> of(String cohabitId) {
            return byCohabit.getOrDefault(cohabitId, Map.of());
        }
    }

    private record Need(String cohabitId, AutoConfig auto, String personId, LocalDate start, LocalDate today,
                        List<Pause> pauses) {
    }

    /** Holt die Quellen fuer alle automatischen Co-Habits, die die Auswahl trifft. */
    public Facts fetchFacts(Predicate<Cohabit> which) {
        Instant now = now();
        List<Need> needs = store.read(data -> {
            List<Need> list = new ArrayList<>();
            for (Cohabit c : data.cohabits().cohabits) {
                if (c.auto == null || !which.test(c)) {
                    continue;
                }
                LocalDate today = LocalDate.ofInstant(now, CohabitEval.zoneOf(c));
                for (Member m : c.members) {
                    List<Pause> pauses = new ArrayList<>();
                    for (Pause p : m.pauses) {
                        Pause copy = new Pause();
                        copy.id = p.id;
                        copy.from = p.from;
                        copy.to = p.to;
                        pauses.add(copy);
                    }
                    list.add(new Need(c.id, c.auto, m.personId, Evaluations.memberStart(c, m), today, pauses));
                }
            }
            return list;
        });
        if (needs.isEmpty()) {
            return Facts.NONE;
        }
        Map<String, Map<String, AutoFacts>> result = new HashMap<>();
        for (Need need : needs) {
            AutoFacts facts = sources.fetch(need.auto(), need.personId(), need.start(), need.today(),
                    StreakModel.paused(need.pauses()));
            result.computeIfAbsent(need.cohabitId(), k -> new HashMap<>()).put(need.personId(), facts);
        }
        return new Facts(result);
    }

    public Facts factsForViewer(String viewerId) {
        return fetchFacts(c -> c.isMember(viewerId));
    }

    public Facts factsForCohabit(String cohabitId) {
        return fetchFacts(c -> c.id.equals(cohabitId));
    }

    public CohabitEval evaluate(CohabitStore.Data data, Cohabit c, Facts facts, Instant now) {
        return CohabitEval.evaluate(c, data.checkins(c.id), facts.of(c.id), now);
    }

    /** Ein Co-Habit, das die Person sehen darf - sonst 404 (auch wenn es existiert). */
    public static Cohabit visible(CohabitStore.Data data, String cohabitId, String viewerId) {
        return data.cohabit(cohabitId).filter(c -> c.isMember(viewerId))
                .orElseThrow(() -> Errors.notFound("Co-Habit nicht gefunden."));
    }

    public CohabitDetail detail(String viewerId, String cohabitId) {
        Facts facts = factsForCohabit(cohabitId);
        Instant now = now();
        return store.read(data -> {
            Cohabit c = visible(data, cohabitId, viewerId);
            return Views.detail(data, evaluate(data, c, facts, now), viewerId);
        });
    }

    public CohabitDetail detail(CohabitStore.Data data, String viewerId, String cohabitId, Facts facts) {
        Cohabit c = visible(data, cohabitId, viewerId);
        return Views.detail(data, evaluate(data, c, facts, now()), viewerId);
    }

    /** Die aktiven bzw. archivierten Co-Habits einer Person. */
    public List<CohabitSummary> summaries(String viewerId, boolean archived) {
        Facts facts = factsForViewer(viewerId);
        Instant now = now();
        return store.read(data -> summaries(data, viewerId, archived, facts, now));
    }

    public List<CohabitSummary> summaries(CohabitStore.Data data, String viewerId, boolean archived, Facts facts,
                                          Instant now) {
        List<CohabitSummary> list = new ArrayList<>();
        for (Cohabit c : mine(data, viewerId, archived)) {
            list.add(Views.summary(data, evaluate(data, c, facts, now), viewerId));
        }
        list.sort(order());
        return list;
    }

    public static List<Cohabit> mine(CohabitStore.Data data, String viewerId, boolean archived) {
        return data.cohabits().cohabits.stream().filter(c -> c.archived == archived && c.isMember(viewerId)).toList();
    }

    /** "Offen heute" zuerst, dann nach Name. */
    public static Comparator<CohabitSummary> order() {
        return Comparator.comparing((CohabitSummary s) -> s.section().equals("OPEN_TODAY") ? 0 : 1)
                .thenComparing(s -> s.ref().name().toLowerCase(Locale.GERMANY));
    }

    public static List<String> ids(Collection<Cohabit> cohabits) {
        return cohabits.stream().map(c -> c.id).toList();
    }
}
