package com.fherrmann.habits.legacy;

import com.fherrmann.habits.client.FoodClient;
import com.fherrmann.habits.client.SourceUnavailableException;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * Die Rechenregeln der alten Habit-App (HabitsService bis 2026-09-30), als
 * Referenz im Testbaum. Nur der Teil, der den Stand eines Habits rechnet;
 * Anlegen, Abhaken und die Quellen-Clients sind durch Funktionen ersetzt.
 *
 * <p>Die Migration muss am Stichtag dieselben Serien liefern - daran misst sich
 * MigrationParityTest. Hier bitte nichts "verbessern".
 */
public class LegacyHabitsService {

    static final double FOOD_KCAL_SHARE = 0.8;
    static final Set<String> FOOD_MAIN_MEALS = Set.of("BREAKFAST", "LUNCH", "DINNER");
    static final int DEFAULT_FOCUS_MINUTES = 240;
    static final int RECENT = 7;
    static final int MARKED_DAYS = 31;

    private final Function<LocalDate, FoodClient.Day> food;
    private final BiFunction<LocalDate, LocalDate, Map<LocalDate, Integer>> steps;
    private final BiFunction<LocalDate, LocalDate, Map<LocalDate, Integer>> focusMinutes;
    private final Clock clock;
    private final int maxLookbackDays;
    private final Map<LocalDate, Boolean> foodHistory = new ConcurrentHashMap<>();

    public LegacyHabitsService(Function<LocalDate, FoodClient.Day> food,
                               BiFunction<LocalDate, LocalDate, Map<LocalDate, Integer>> steps,
                               BiFunction<LocalDate, LocalDate, Map<LocalDate, Integer>> focusMinutes,
                               Clock clock, int maxLookbackDays) {
        this.food = food;
        this.steps = steps;
        this.focusMinutes = focusMinutes;
        this.clock = clock;
        this.maxLookbackDays = maxLookbackDays;
    }

    public HabitStatus status(Habit habit, HabitsData data, LocalDate today) {
        List<LocalDate> markedDays = markedDays(habit, data, today);
        try {
            Status status = switch (habit.kind()) {
                case BUILD -> habit.rhythm() == Period.DAY
                        ? daily(habit, today, marked(habit, data), true)
                        : periodic(habit, today, marked(habit, data));
                case QUIT -> daily(habit, today, notRelapsed(habit, data), false);
                case FOOD -> daily(habit, today, this::foodDone, true).withProgress(foodProgress(today));
                case STEPS -> weekly(habit, today);
                case FOCUS -> focusDaily(habit, today);
            };
            return toStatus(status, markedDays);
        } catch (SourceUnavailableException e) {
            return new HabitStatus(habit.id(), habit.name(), habit.kind(), habit.unit(),
                    habit.weeklyStepGoal(), 0, false, false, null, List.of(), e.getMessage(),
                    habit.focusMinutesGoal(), habit.rhythm(), habit.timesPerPeriod(),
                    markedDays, habit.createdAt());
        }
    }

    private static List<LocalDate> markedDays(Habit habit, HabitsData data, LocalDate today) {
        if (habit.kind().isAutomatic()) {
            return List.of();
        }
        LocalDate from = today.minusDays(MARKED_DAYS - 1);
        return data.marks().stream()
                .filter(m -> m.habitId().equals(habit.id()))
                .map(Mark::date)
                .filter(d -> !d.isBefore(from) && !d.isAfter(today))
                .sorted()
                .toList();
    }

    private Status periodic(Habit habit, LocalDate today, Predicate<LocalDate> marked) {
        Period period = habit.rhythm();
        int times = habit.times();
        UnaryOperator<LocalDate> previous = period == Period.WEEK
                ? start -> start.minusWeeks(1)
                : start -> start.minusMonths(1);
        LocalDate current = period == Period.WEEK ? LegacyStreaks.mondayOf(today) : LegacyStreaks.firstOfMonth(today);
        Predicate<LocalDate> periodDone = start -> countMarked(marked, start, period) >= times;
        int maxPeriods = period == Period.WEEK ? maxLookbackDays / 7 : maxLookbackDays / 28;
        int streak = LegacyStreaks.periodic(current, periodDone, previous, maxPeriods);
        boolean thisPeriodDone = periodDone.test(current);
        return new Status(habit, streak, marked.test(today),
                !thisPeriodDone && streak > 0,
                new Progress(countMarked(marked, current, period), times),
                LegacyStreaks.recentPeriods(current, periodDone, previous, RECENT));
    }

    private static int countMarked(Predicate<LocalDate> marked, LocalDate start, Period period) {
        LocalDate end = period == Period.WEEK ? start.plusWeeks(1) : start.plusMonths(1);
        int count = 0;
        for (LocalDate day = start; day.isBefore(end); day = day.plusDays(1)) {
            if (marked.test(day)) {
                count++;
            }
        }
        return count;
    }

    private Status focusDaily(Habit habit, LocalDate today) {
        int goal = habit.focusMinutesGoal() == null ? DEFAULT_FOCUS_MINUTES : habit.focusMinutesGoal();
        Map<LocalDate, Integer> perDay = focusMinutes.apply(today.minusDays(maxLookbackDays), today);
        Predicate<LocalDate> done = day -> perDay.getOrDefault(day, 0) >= goal;
        return daily(habit, today, done, true)
                .withProgress(new Progress(perDay.getOrDefault(today, 0), goal));
    }

    private Status daily(Habit habit, LocalDate today, Predicate<LocalDate> done, boolean todayMayBeOpen) {
        int streak = LegacyStreaks.daily(today, done, maxLookbackDays, todayMayBeOpen);
        boolean doneToday = done.test(today);
        return new Status(habit, streak, doneToday,
                todayMayBeOpen && !doneToday && streak > 0,
                null, LegacyStreaks.recentDays(today, done, RECENT));
    }

    private Status weekly(Habit habit, LocalDate today) {
        int goal = habit.weeklyStepGoal() == null ? 0 : habit.weeklyStepGoal();
        LocalDate thisMonday = LegacyStreaks.mondayOf(today);
        Map<LocalDate, Integer> perDay = steps.apply(thisMonday.minusDays(maxLookbackDays), today);
        Predicate<LocalDate> weekDone = monday -> weekTotal(perDay, monday) >= goal && goal > 0;
        int thisWeek = weekTotal(perDay, thisMonday);
        int streak = LegacyStreaks.weekly(today, weekDone, maxLookbackDays / 7);
        return new Status(habit, streak, weekDone.test(thisMonday), false,
                new Progress(thisWeek, goal), LegacyStreaks.recentWeeks(today, weekDone, RECENT));
    }

    private static int weekTotal(Map<LocalDate, Integer> perDay, LocalDate monday) {
        int total = 0;
        for (int i = 0; i < 7; i++) {
            total += perDay.getOrDefault(monday.plusDays(i), 0);
        }
        return total;
    }

    private static Predicate<LocalDate> marked(Habit habit, HabitsData data) {
        Set<LocalDate> days = new HashSet<>();
        for (Mark mark : data.marks()) {
            if (mark.habitId().equals(habit.id())) {
                days.add(mark.date());
            }
        }
        return days::contains;
    }

    private static Predicate<LocalDate> notRelapsed(Habit habit, HabitsData data) {
        Predicate<LocalDate> relapsed = marked(habit, data);
        return day -> !day.isBefore(habit.createdAt()) && !relapsed.test(day);
    }

    private boolean foodDone(LocalDate day) {
        LocalDate today = LocalDate.now(clock);
        boolean history = day.isBefore(today.minusDays(1));
        if (history) {
            Boolean cached = foodHistory.get(day);
            if (cached != null) {
                return cached;
            }
        }
        boolean done = isFoodDone(food.apply(day));
        if (history) {
            foodHistory.put(day, done);
        }
        return done;
    }

    static boolean isFoodDone(FoodClient.Day day) {
        boolean enoughKcal = day.targetKcal() > 0 && day.kcal() >= FOOD_KCAL_SHARE * day.targetKcal();
        boolean allMainMeals = day.meals().containsAll(FOOD_MAIN_MEALS);
        return enoughKcal || allMainMeals;
    }

    private Progress foodProgress(LocalDate today) {
        FoodClient.Day day = food.apply(today);
        return new Progress((int) Math.round(day.kcal()),
                (int) Math.round(FOOD_KCAL_SHARE * day.targetKcal()));
    }

    private record Status(Habit habit, int streak, boolean doneToday, boolean atRisk,
                          Progress progress, List<Boolean> recent) {

        Status withProgress(Progress progress) {
            return new Status(habit, streak, doneToday, atRisk, progress, recent);
        }
    }

    private static HabitStatus toStatus(Status s, List<LocalDate> markedDays) {
        return new HabitStatus(s.habit.id(), s.habit.name(), s.habit.kind(), s.habit.unit(),
                s.habit.weeklyStepGoal(), s.streak, s.doneToday, s.atRisk, s.progress, s.recent, null,
                s.habit.focusMinutesGoal(), s.habit.rhythm(), s.habit.timesPerPeriod(),
                markedDays, s.habit.createdAt());
    }
}
