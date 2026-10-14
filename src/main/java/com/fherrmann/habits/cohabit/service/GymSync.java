package com.fherrmann.habits.cohabit.service;

import com.fherrmann.habits.client.GymClient;
import com.fherrmann.habits.client.SourceUnavailableException;
import com.fherrmann.habits.cohabit.model.AutoSource;
import com.fherrmann.habits.cohabit.model.Cohabit;
import com.fherrmann.habits.cohabit.model.HealthMetric;
import com.fherrmann.habits.cohabit.model.Member;
import com.fherrmann.habits.cohabit.rules.CohabitEval;
import com.fherrmann.habits.cohabit.rules.CohabitEval.MemberEval;
import com.fherrmann.habits.cohabit.store.CohabitStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Hakt Co-Habits mit der Messgroesse {@link HealthMetric#GYM} ab, wenn im Gym-Tab von Healthy
 * ein Training beendet wurde (Felix, 14.10.: "wird automatisch abgehakt, wenn man einen
 * Gym-Tag beendet hat") - je Mitglied mit Einwilligung und Healthy-Zugang, in der
 * Nachtragsfrist, mindestens heute und gestern. Von Hand abhaken geht daneben weiter.
 *
 * <p>Wie {@link KcalSync}: unter dem Lock einsammeln, ohne Lock beim Weight Tracker fragen
 * (ein Aufruf je Person fuer alle ihre Tage), unter dem Lock schreiben. Antwortet er nicht,
 * bleibt alles, wie es war.
 *
 * <p>Drei Anlaesse: der Scheduler alle 15 Minuten, die Einwilligung (auch beim Anlegen) und
 * das Oeffnen der App ({@link #refresh}) - damit der Haken nach "Training beenden" sofort da
 * ist und nicht erst beim naechsten Lauf.
 */
@Service
public class GymSync {

    private static final Logger log = LoggerFactory.getLogger(GymSync.class);

    /** So weit zurueck - die Nachtragsfrist ist hoechstens 14 Tage lang, wie bei den kcal. */
    static final int WINDOW_DAYS = KcalSync.FIRST_SYNC_DAYS;
    /** Beim Oeffnen hoechstens einmal je Minute und Person. */
    static final Duration REFRESH_EVERY = Duration.ofMinutes(1);
    /** Beim Oeffnen wartet die Ansicht - laenger darf der Weight Tracker dann nicht brauchen. */
    static final Duration QUICK = Duration.ofSeconds(2);
    static final Duration NORMAL = Duration.ofSeconds(5);

    private final CohabitStore store;
    private final GymClient gym;
    private final AutoSources sources;
    private final CheckinService checkins;
    private final Clock clock;
    private final Map<String, Instant> lastRefresh = new ConcurrentHashMap<>();

    public GymSync(CohabitStore store, GymClient gym, AutoSources sources, CheckinService checkins, Clock clock) {
        this.store = store;
        this.gym = gym;
        this.sources = sources;
        this.checkins = checkins;
        this.clock = clock;
    }

    /** Ob eine Person Trainings beisteuern kann: nur mit Healthy-Zugang (das Gym liegt im Weight Tracker). */
    public boolean canProvide(String personId) {
        return sources.sourcesOf(personId).contains(AutoSource.STEPS_WEEKLY);
    }

    /** Alle Co-Habits mit Gym - fuer den Scheduler. */
    public int syncAll(Instant now) {
        return run(now, c -> true, null, NORMAL);
    }

    /** Gleich nach der Einwilligung: dieses eine Co-Habit dieser einen Person. */
    public int sync(String cohabitId, String personId, Instant now) {
        return run(now, c -> c.id.equals(cohabitId), personId, NORMAL);
    }

    /**
     * Beim Oeffnen der App (Heute, Liste, Detail, Widget, klassische Liste): hoechstens einmal
     * je Minute, mit kurzer Frist, und nie mit einem Fehler - die Ansicht kommt trotzdem.
     */
    public void refresh(String personId) {
        Instant now = clock.instant();
        Instant[] previous = {null};
        lastRefresh.compute(personId, (k, last) -> {
            previous[0] = last;
            return last != null && last.plus(REFRESH_EVERY).isAfter(now) ? last : now;
        });
        if (previous[0] != null && previous[0].plus(REFRESH_EVERY).isAfter(now)) {
            return;
        }
        try {
            run(now, c -> true, personId, QUICK);
        } catch (RuntimeException e) {
            log.warn("Gym fuer {} beim Oeffnen nicht abgeglichen", personId, e);
        }
    }

    /** Fuer Tests: der naechste {@link #refresh} fragt wieder. */
    void forgetRefreshes() {
        lastRefresh.clear();
    }

    private record Need(String cohabitId, String personId, List<LocalDate> days) {
    }

    private int run(Instant now, Predicate<Cohabit> which, String onlyPerson, Duration timeout) {
        List<Need> needs = store.read(data -> {
            List<Need> list = new ArrayList<>();
            for (Cohabit c : data.cohabits().cohabits) {
                if (c.archived || c.health == null || c.health.metric() != HealthMetric.GYM || !which.test(c)) {
                    continue;
                }
                CohabitEval e = null;
                for (Member m : c.members) {
                    if ((onlyPerson != null && !onlyPerson.equals(m.personId)) || !m.settings.healthConsent
                            || !canProvide(m.personId)) {
                        continue;
                    }
                    if (e == null) {
                        e = CohabitEval.evaluate(c, data.checkins(c.id), Map.of(), now);
                    }
                    MemberEval mine = e.member(m.personId);
                    List<LocalDate> days = new ArrayList<>();
                    for (LocalDate d = e.today.minusDays(WINDOW_DAYS); !d.isAfter(e.today); d = d.plusDays(1)) {
                        if (CheckinService.healthDayAllowed(c, e, mine, d)) {
                            days.add(d);
                        }
                    }
                    if (!days.isEmpty()) {
                        list.add(new Need(c.id, m.personId, days));
                    }
                }
            }
            return list;
        });
        if (needs.isEmpty()) {
            return 0;
        }

        // Je Person ein Aufruf ueber alle ihre Tage, auch wenn sie in mehreren Co-Habits zustimmt.
        Map<String, LocalDate[]> ranges = new HashMap<>();
        for (Need need : needs) {
            LocalDate first = need.days().getFirst();
            LocalDate last = need.days().getLast();
            ranges.merge(need.personId(), new LocalDate[]{first, last}, (a, b) -> new LocalDate[]{
                    a[0].isBefore(b[0]) ? a[0] : b[0], a[1].isAfter(b[1]) ? a[1] : b[1]});
        }
        Map<String, Map<LocalDate, Integer>> trained = new HashMap<>();
        Set<String> unavailable = new HashSet<>();
        ranges.forEach((person, range) -> {
            try {
                trained.put(person, gym.trainingDays(person, range[0], range[1], timeout));
            } catch (SourceUnavailableException e) {
                unavailable.add(person);
                log.warn("Gym fuer {} nicht abgeglichen: {}", person, e.getMessage());
            }
        });

        int[] changed = {0};
        store.update(tx -> {
            for (Need need : needs) {
                if (unavailable.contains(need.personId())) {
                    continue;
                }
                Cohabit c = tx.cohabit(need.cohabitId()).orElse(null);
                if (c == null || c.archived || c.health == null || c.health.metric() != HealthMetric.GYM) {
                    continue;
                }
                Member m = c.member(need.personId()).orElse(null);
                if (m == null || !m.settings.healthConsent) {
                    continue; // inzwischen widerrufen oder ausgetreten
                }
                Map<LocalDate, Integer> perDay = trained.getOrDefault(need.personId(), Map.of());
                for (LocalDate day : need.days()) {
                    if (checkins.applyGymDay(tx, c, need.personId(), day, perDay.getOrDefault(day, 0) > 0, now)) {
                        changed[0]++;
                    }
                }
                tx.cohabitsW();
                m.lastHealthSyncAt = now;
            }
        });
        return changed[0];
    }
}
