package com.fherrmann.habits.cohabit.service;

import com.fherrmann.habits.client.FoodClient;
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

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Holt die kcal des Tages aus dem Kalorienzaehler in Co-Habits mit der Messgroesse
 * {@link HealthMetric#KCAL} - je Mitglied, das zugestimmt hat und einen Healthy-Zugang
 * hat (Quelle FOOD). Dieselbe Logik wie bei den Health-Werten der Apps: ein Wert je Tag,
 * innerhalb der Nachtragsfrist, mindestens heute und gestern. Nur holt der Dienst
 * selbst - die Daten liegen ja nebenan, kein Handy muss dafuer etwas schicken.
 *
 * <p>Wie bei den automatischen Quellen: unter dem Lock einsammeln, wer was braucht;
 * ohne Lock beim Kalorienzaehler fragen; dann unter dem Lock schreiben. Antwortet der
 * Kalorienzaehler nicht, bleibt fuer diese Person alles, wie es war.
 */
@Service
public class KcalSync {

    private static final Logger log = LoggerFactory.getLogger(KcalSync.class);

    /** Nach der Zustimmung so weit zurueck - die Nachtragsfrist ist hoechstens 14 Tage lang. */
    static final int FIRST_SYNC_DAYS = 15;

    private final CohabitStore store;
    private final FoodClient food;
    private final AutoSources sources;
    private final CheckinService checkins;

    public KcalSync(CohabitStore store, FoodClient food, AutoSources sources, CheckinService checkins) {
        this.store = store;
        this.food = food;
        this.sources = sources;
        this.checkins = checkins;
    }

    /** Ob eine Person kcal beisteuern kann: nur mit Healthy-Zugang. */
    public boolean canProvide(String personId) {
        return sources.sourcesOf(personId).contains(AutoSource.FOOD);
    }

    /** Alle Co-Habits mit kcal, heute und gestern je zustimmendem Mitglied - fuer den Scheduler. */
    public int syncAll(Instant now) {
        return run(now, c -> true, null, false);
    }

    /** Gleich nach der Zustimmung: die ganze Nachtragsfrist dieser einen Person. */
    public int sync(String cohabitId, String personId, Instant now) {
        return run(now, c -> c.id.equals(cohabitId), personId, true);
    }

    private record Need(String cohabitId, String personId, List<LocalDate> days) {
    }

    private int run(Instant now, Predicate<Cohabit> which, String onlyPerson, boolean wholeWindow) {
        List<Need> needs = store.read(data -> {
            List<Need> list = new ArrayList<>();
            for (Cohabit c : data.cohabits().cohabits) {
                if (c.archived || c.health == null || c.health.metric() != HealthMetric.KCAL || !which.test(c)) {
                    continue;
                }
                CohabitEval e = CohabitEval.evaluate(c, data.checkins(c.id), Map.of(), now);
                for (Member m : c.members) {
                    if ((onlyPerson != null && !onlyPerson.equals(m.personId)) || !m.settings.healthConsent
                            || !canProvide(m.personId)) {
                        continue;
                    }
                    MemberEval mine = e.member(m.personId);
                    LocalDate from = e.today.minusDays(wholeWindow ? FIRST_SYNC_DAYS : 1);
                    List<LocalDate> days = new ArrayList<>();
                    for (LocalDate d = from; !d.isAfter(e.today); d = d.plusDays(1)) {
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

        // Je Person und Tag hoechstens eine Anfrage, auch wenn sie in mehreren Co-Habits zustimmt.
        Map<String, Map<LocalDate, Double>> kcal = new HashMap<>();
        Set<String> unavailable = new HashSet<>();
        for (Need need : needs) {
            Map<LocalDate, Double> perDay = kcal.computeIfAbsent(need.personId(), k -> new HashMap<>());
            for (LocalDate day : need.days()) {
                if (unavailable.contains(need.personId()) || perDay.containsKey(day)) {
                    continue;
                }
                try {
                    perDay.put(day, food.day(need.personId(), day).kcal());
                } catch (SourceUnavailableException e) {
                    unavailable.add(need.personId());
                    log.warn("kcal fuer {} nicht abgeglichen: {}", need.personId(), e.getMessage());
                }
            }
        }

        int[] changed = {0};
        store.update(tx -> {
            for (Need need : needs) {
                if (unavailable.contains(need.personId())) {
                    continue;
                }
                Cohabit c = tx.cohabit(need.cohabitId()).orElse(null);
                if (c == null || c.health == null || c.health.metric() != HealthMetric.KCAL) {
                    continue;
                }
                Member m = c.member(need.personId()).orElse(null);
                if (m == null || !m.settings.healthConsent) {
                    continue; // inzwischen widerrufen oder ausgetreten
                }
                Map<LocalDate, Double> perDay = kcal.getOrDefault(need.personId(), Map.of());
                for (LocalDate day : need.days()) {
                    Double value = perDay.get(day);
                    if (value != null && checkins.applyHealthValue(tx, c, need.personId(), day, Math.round(value), now)) {
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
