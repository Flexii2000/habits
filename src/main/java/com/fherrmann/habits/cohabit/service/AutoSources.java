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
    /** Vergangene Tage des Kalorienzaehlers samt kcal und Ziel - fuer das Wochenmittel. */
    private final Map<String, Map<LocalDate, FoodClient.Day>> foodDays = new ConcurrentHashMap<>();

    public AutoSources(FoodClient food, StepsClient steps, FocusService focus, HealthUsers users) {
        this.food = food;
        this.steps = steps;
        this.focus = focus;
        this.users = users;
    }

    /** Vergisst die gemerkten Tage des Kalorienzaehlers. */
    public void forgetHistory() {
        foodHistory.clear();
        foodDays.clear();
    }

    /** Welche Quellen eine Person hat: FOOD und Schritte alle Healthy-Personen, FOCUS nur die Eigentuemerin. */
    public List<AutoSource> sourcesOf(String personId) {
        if (users.isOwner(personId)) {
            return List.of(AutoSource.FOOD, AutoSource.FOOD_TARGET_WEEKLY, AutoSource.STEPS_WEEKLY, AutoSource.FOCUS);
        }
        if (users.isHealthPerson(personId)) {
            return List.of(AutoSource.FOOD, AutoSource.FOOD_TARGET_WEEKLY, AutoSource.STEPS_WEEKLY);
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
                case FOOD_TARGET_WEEKLY -> fetchFoodTarget(personId, memberStart, today, paused);
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

    /**
     * Im Kalorienziel, im Wochenmittel: je abgeschlossener Woche (Montag als Schluessel),
     * ob der Schnitt der getrackten Tage hoechstens beim Ziel lag - rueckwaerts bis zur
     * ersten verfehlten Woche und ab dem Beitritt (Rekord, Quote). Fuer die laufende Woche
     * der Schnitt bisher ({@code todayValue}) gegen das Ziel ({@code todayGoal}); ohne
     * getrackten Tag ist er 0.
     */
    private AutoFacts fetchFoodTarget(String personId, LocalDate memberStart, LocalDate today,
                                      Predicate<LocalDate> paused) {
        LocalDate monday = PeriodScheme.mondayOf(today);
        long[] current = weekSums(personId, monday, today, today);
        int avgKcal = current[2] == 0 ? 0 : (int) Math.round((double) current[0] / current[2]);
        int avgTarget = current[2] == 0 ? (int) Math.round(foodDay(personId, today, today).targetKcal())
                : (int) Math.round((double) current[1] / current[2]);

        Map<LocalDate, Boolean> weeks = new HashMap<>();
        LocalDate limit = today.minusDays(StreakModel.AUTO_LOOKBACK_DAYS);
        for (LocalDate week = monday.minusWeeks(1); !week.isBefore(limit); week = week.minusWeeks(1)) {
            boolean ok = weekInTarget(personId, week, today);
            weeks.put(week, ok);
            if (!ok && !fullyPaused(week, paused)) {
                break;
            }
        }
        LocalDate from = PeriodScheme.mondayOf(memberStart.isBefore(limit) ? limit : memberStart);
        for (LocalDate week = from; week.isBefore(monday); week = week.plusWeeks(1)) {
            if (!weeks.containsKey(week)) {
                weeks.put(week, weekInTarget(personId, week, today));
            }
        }
        return new AutoFacts(AutoSource.FOOD_TARGET_WEEKLY, weeks, Map.of(), avgKcal, avgTarget, null);
    }

    private boolean weekInTarget(String personId, LocalDate monday, LocalDate today) {
        long[] sums = weekSums(personId, monday, monday.plusDays(6), today);
        return sums[2] > 0 && sums[0] <= sums[1];
    }

    /** kcal, Ziel und Zahl der getrackten Tage (Regel wie "Track food") von {@code from} bis {@code to}. */
    private long[] weekSums(String personId, LocalDate from, LocalDate to, LocalDate today) {
        long kcal = 0;
        long target = 0;
        long tracked = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            FoodClient.Day day = foodDay(personId, d, today);
            if (day.targetKcal() > 0 && isFoodDone(day)) {
                kcal += Math.round(day.kcal());
                target += Math.round(day.targetKcal());
                tracked++;
            }
        }
        return new long[] {kcal, target, tracked};
    }

    private static boolean fullyPaused(LocalDate monday, Predicate<LocalDate> paused) {
        for (int i = 0; i < 7; i++) {
            if (!paused.test(monday.plusDays(i))) {
                return false;
            }
        }
        return true;
    }

    /** Ein Tag des Kalorienzaehlers; heute und gestern immer frisch, davor aus dem Speicher. */
    private FoodClient.Day foodDay(String personId, LocalDate day, LocalDate today) {
        boolean history = day.isBefore(today.minusDays(1));
        Map<LocalDate, FoodClient.Day> cache = foodDays.computeIfAbsent(personId, k -> new ConcurrentHashMap<>());
        if (history) {
            FoodClient.Day cached = cache.get(day);
            if (cached != null) {
                return cached;
            }
        }
        FoodClient.Day fetched = food.day(personId, day);
        if (history) {
            cache.put(day, fetched);
        }
        return fetched;
    }

    /** Die Sessions gehoeren der Eigentuemerin - nur sie hat diese Quelle. */
    private AutoFacts fetchFocus(AutoConfig cfg, String personId, LocalDate today) {
        if (!users.isOwner(personId)) {
            return AutoFacts.unavailable(AutoSource.FOCUS, "Keine Fokus-Sessions");
        }
        int goal = cfg.focusGoal();
        // Mit Kategorie zaehlen nur deren Baeume ("1 h Bachelorarbeit"); je Woche rechnet der
        // Judge aus den Minuten je Tag, der Tages-Haken gilt nur bei Tageszielen.
        Map<LocalDate, Integer> perDay = focus.minutesPerDay(today.minusDays(StreakModel.AUTO_LOOKBACK_DAYS), today,
                cfg.focusCategoryId());
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
