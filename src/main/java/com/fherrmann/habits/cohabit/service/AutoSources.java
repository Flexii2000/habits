package com.fherrmann.habits.cohabit.service;

import com.fherrmann.habits.client.FoodClient;
import com.fherrmann.habits.client.SourceUnavailableException;
import com.fherrmann.habits.client.StepsClient;
import com.fherrmann.habits.cohabit.model.AutoConfig;
import com.fherrmann.habits.cohabit.model.AutoSource;
import com.fherrmann.habits.cohabit.rules.AutoFacts;
import com.fherrmann.habits.cohabit.rules.PeriodScheme;
import com.fherrmann.habits.cohabit.rules.StreakModel;
import com.fherrmann.habits.security.HealthUsers;
import com.fherrmann.habits.service.FocusService;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Holt die Daten der automatischen Quellen - vor der Rechnung, ausserhalb jedes Locks.
 *
 * <p>"Track food" ist erledigt, wenn mindestens 80 % des kcal-Ziels erreicht
 * sind <b>oder</b> Fruehstueck, Mittag- und Abendessen je einen Eintrag haben;
 * "Fokus-Zeit", wenn die Wald-Sessions des Tages das Tagesziel schaffen; die
 * Schritte-Woche, wenn die Summe Mo-So das Wochenziel erreicht. Alles wie in der
 * alten App.
 *
 * <p>Vergangene Tage des Kalorienzaehlers bleiben im Speicher (je Person): heute
 * und gestern werden immer frisch gefragt - gestern wird nach Mitternacht noch
 * nachgetragen -, alles davor nach dem ersten Mal nicht mehr, bis zum Neustart.
 */
@Component
public class AutoSources {

    /** Ab diesem Anteil des kcal-Ziels gilt "Track food" als erledigt. */
    public static final double FOOD_KCAL_SHARE = 0.8;
    /** Oder wenn diese drei Mahlzeiten je einen Eintrag haben - Snacks zaehlen nicht. */
    public static final Set<String> FOOD_MAIN_MEALS = Set.of("BREAKFAST", "LUNCH", "DINNER");

    private final FoodClient food;
    private final StepsClient steps;
    private final FocusService focus;
    private final HealthUsers users;
    private final Map<String, Map<LocalDate, Boolean>> foodHistory = new ConcurrentHashMap<>();

    public AutoSources(FoodClient food, StepsClient steps, FocusService focus, HealthUsers users) {
        this.food = food;
        this.steps = steps;
        this.focus = focus;
        this.users = users;
    }

    /** Welche Quellen eine Person hat: FOOD und Schritte alle Healthy-Personen, FOCUS nur die Eigentuemerin. */
    public List<AutoSource> sourcesOf(String personId) {
        if (users.isOwner(personId)) {
            return List.of(AutoSource.FOOD, AutoSource.STEPS_WEEKLY, AutoSource.FOCUS);
        }
        if (users.isHealthPerson(personId)) {
            return List.of(AutoSource.FOOD, AutoSource.STEPS_WEEKLY);
        }
        return List.of();
    }

    /**
     * Alles, was die Rechnung fuer diese Person braucht: die laufende Serie
     * rueckwaerts bis zum ersten verfehlten Tag (hoechstens 730 Tage, wie bisher),
     * die Tage seit dem Beitritt (Rekord, Quote) und die laufende Woche (Raster).
     */
    public AutoFacts fetch(AutoConfig cfg, String personId, LocalDate memberStart, LocalDate today,
                           Predicate<LocalDate> paused) {
        try {
            return switch (cfg.source()) {
                case FOOD -> fetchFood(personId, memberStart, today, paused);
                case FOCUS -> fetchFocus(cfg, personId, today);
                case STEPS_WEEKLY -> fetchSteps(personId, today);
            };
        } catch (SourceUnavailableException e) {
            return AutoFacts.unavailable(cfg.source(), e.getMessage());
        }
    }

    private AutoFacts fetchFood(String personId, LocalDate memberStart, LocalDate today,
                                Predicate<LocalDate> paused) {
        Map<LocalDate, Boolean> done = new HashMap<>();
        LocalDate limit = today.minusDays(StreakModel.AUTO_LOOKBACK_DAYS);
        FoodClient.Day todayDay = food.day(personId, today);
        done.put(today, isFoodDone(todayDay));
        for (LocalDate d = today.minusDays(1); !d.isBefore(limit); d = d.minusDays(1)) {
            boolean ok = foodDone(personId, d, today);
            done.put(d, ok);
            if (!ok && !paused.test(d)) {
                break;
            }
        }
        LocalDate from = memberStart.isBefore(limit) ? limit : memberStart;
        LocalDate monday = PeriodScheme.mondayOf(today);
        if (monday.isBefore(from)) {
            from = monday;
        }
        for (LocalDate d = from; d.isBefore(today); d = d.plusDays(1)) {
            if (!done.containsKey(d)) {
                done.put(d, foodDone(personId, d, today));
            }
        }
        return new AutoFacts(AutoSource.FOOD, done, Map.of(), (int) Math.round(todayDay.kcal()),
                (int) Math.round(FOOD_KCAL_SHARE * todayDay.targetKcal()), null);
    }

    private boolean foodDone(String personId, LocalDate day, LocalDate today) {
        boolean history = day.isBefore(today.minusDays(1));
        Map<LocalDate, Boolean> cache = foodHistory.computeIfAbsent(personId, k -> new ConcurrentHashMap<>());
        if (history) {
            Boolean cached = cache.get(day);
            if (cached != null) {
                return cached;
            }
        }
        boolean done = isFoodDone(food.day(personId, day));
        if (history) {
            cache.put(day, done);
        }
        return done;
    }

    public static boolean isFoodDone(FoodClient.Day day) {
        boolean enoughKcal = day.targetKcal() > 0 && day.kcal() >= FOOD_KCAL_SHARE * day.targetKcal();
        boolean allMainMeals = day.meals().containsAll(FOOD_MAIN_MEALS);
        return enoughKcal || allMainMeals;
    }

    /** Die Sessions gehoeren der Eigentuemerin - nur sie hat diese Quelle. */
    private AutoFacts fetchFocus(AutoConfig cfg, String personId, LocalDate today) {
        if (!users.isOwner(personId)) {
            return AutoFacts.unavailable(AutoSource.FOCUS, "Keine Fokus-Sessions");
        }
        int goal = cfg.focusGoal();
        Map<LocalDate, Integer> perDay = focus.minutesPerDay(today.minusDays(StreakModel.AUTO_LOOKBACK_DAYS), today);
        Map<LocalDate, Boolean> done = new HashMap<>();
        for (LocalDate d = today.minusDays(StreakModel.AUTO_LOOKBACK_DAYS); !d.isAfter(today); d = d.plusDays(1)) {
            done.put(d, perDay.getOrDefault(d, 0) >= goal);
        }
        return new AutoFacts(AutoSource.FOCUS, done, perDay, perDay.getOrDefault(today, 0), goal, null);
    }

    /** Ein Aufruf fuer den ganzen Rueckblick statt einer je Woche. */
    private AutoFacts fetchSteps(String personId, LocalDate today) {
        LocalDate monday = PeriodScheme.mondayOf(today);
        Map<LocalDate, Integer> perDay = steps.steps(personId, monday.minusDays(StreakModel.AUTO_LOOKBACK_DAYS), today);
        return new AutoFacts(AutoSource.STEPS_WEEKLY, Map.of(), perDay, 0, 0, null);
    }
}
