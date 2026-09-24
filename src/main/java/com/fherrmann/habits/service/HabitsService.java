package com.fherrmann.habits.service;

import com.fherrmann.habits.client.FoodClient;
import com.fherrmann.habits.client.SourceUnavailableException;
import com.fherrmann.habits.client.StepsClient;
import com.fherrmann.habits.dto.HabitRequest;
import com.fherrmann.habits.dto.HabitStatus;
import com.fherrmann.habits.dto.Progress;
import com.fherrmann.habits.model.Habit;
import com.fherrmann.habits.model.HabitKind;
import com.fherrmann.habits.model.HabitsData;
import com.fherrmann.habits.model.Period;
import com.fherrmann.habits.model.Mark;
import com.fherrmann.habits.repository.HabitsRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * Die Regeln: was ein Habit ist, wann es als erledigt gilt, wie die Straehne
 * gezaehlt wird. Keine Ein-/Ausgabe ausser ueber Repository und Clients.
 */
@Service
public class HabitsService {

    /** Ab diesem Anteil des kcal-Ziels gilt "Track food" als erledigt. */
    static final double FOOD_KCAL_SHARE = 0.8;
    /** Oder wenn diese drei Mahlzeiten je einen Eintrag haben - Snacks zaehlen nicht. */
    static final Set<String> FOOD_MAIN_MEALS = Set.of("BREAKFAST", "LUNCH", "DINNER");

    static final int MAX_NAME_LENGTH = 60;
    static final int MAX_STEP_GOAL = 500_000;
    /** Vorgabe fuer "Fokus-Zeit": vier Stunden Baumzeit am Tag - so hat Felix es bestellt. */
    static final int DEFAULT_FOCUS_MINUTES = 240;
    static final int MAX_FOCUS_MINUTES = 24 * 60;
    static final int RECENT = 7;
    /** So weit zurueck listet der Stand die markierten Tage - zum rueckwirkenden Abhaken. */
    static final int MARKED_DAYS = 31;

    private final HabitsRepository repository;
    private final FocusService focus;
    private final FoodClient food;
    private final StepsClient steps;
    private final Clock clock;
    private final int maxLookbackDays;

    /**
     * Was der Kalorienzaehler ueber vergangene Tage gesagt hat.
     *
     * <p>Nur fuer Tage, die mindestens zwei Tage zurueckliegen: heute aendert
     * sich mit jedem Eintrag, und gestern wird nach Mitternacht noch
     * nachgetragen. Alles davor ist Geschichte - und jede Straehne fragt sonst
     * bei jedem Oeffnen der App dieselben Tage noch einmal ab. Im Speicher,
     * nicht in der Datei: nach einem Neustart ist der Cache leer und fuellt
     * sich beim ersten Aufruf wieder. Ein nachtraeglich geaenderter alter Tag
     * wird damit erst nach einem Neustart gesehen; das ist der Preis, und er
     * ist klein.
     */
    private final Map<LocalDate, Boolean> foodHistory = new ConcurrentHashMap<>();

    public HabitsService(
            HabitsRepository repository,
            FocusService focus,
            FoodClient food,
            StepsClient steps,
            Clock clock,
            @Value("${habits.max-lookback-days:730}") int maxLookbackDays) {
        this.repository = repository;
        this.focus = focus;
        this.food = food;
        this.steps = steps;
        this.clock = clock;
        this.maxLookbackDays = maxLookbackDays;
    }

    // MARK: - Lesen

    public List<HabitStatus> list() {
        HabitsData data = repository.load();
        LocalDate today = LocalDate.now(clock);
        List<HabitStatus> statuses = new ArrayList<>();
        for (Habit habit : data.habits()) {
            statuses.add(status(habit, data, today));
        }
        return statuses;
    }

    public HabitStatus get(String id) {
        HabitsData data = repository.load();
        return status(find(data, id), data, LocalDate.now(clock));
    }

    // MARK: - Anlegen, aendern, loeschen

    public HabitStatus create(HabitRequest request) {
        validate(request, true);
        HabitsData data = repository.load();
        Habit habit = new Habit(
                HabitsRepository.newId(),
                request.name().trim(),
                request.kind(),
                request.kind() == HabitKind.STEPS ? request.weeklyStepGoal() : null,
                LocalDate.now(clock),
                request.kind() == HabitKind.FOCUS ? focusGoal(request) : null,
                rhythm(request),
                times(request));
        List<Habit> habits = new ArrayList<>(data.habits());
        habits.add(habit);
        HabitsData updated = new HabitsData(habits, data.marks());
        repository.save(updated);
        return status(habit, updated, LocalDate.now(clock));
    }

    /**
     * Name und Wochenziel lassen sich aendern, die Art nicht.
     *
     * <p>Aus einem BUILD ein QUIT zu machen kehrte die Bedeutung jedes
     * vorhandenen Eintrags um - aus "gemacht" wuerde "Rueckfall". Wer die Art
     * aendern will, legt ein neues Habit an.
     */
    public HabitStatus update(String id, HabitRequest request) {
        HabitsData data = repository.load();
        Habit existing = find(data, id);
        HabitRequest checked = new HabitRequest(request.name(), existing.kind(),
                request.weeklyStepGoal(), request.focusMinutesGoal(),
                request.period(), request.timesPerPeriod());
        validate(checked, false);
        Habit habit = new Habit(
                existing.id(),
                request.name().trim(),
                existing.kind(),
                existing.kind() == HabitKind.STEPS ? request.weeklyStepGoal() : null,
                existing.createdAt(),
                existing.kind() == HabitKind.FOCUS ? focusGoal(checked) : null,
                rhythm(checked),
                times(checked));
        List<Habit> habits = new ArrayList<>();
        for (Habit h : data.habits()) {
            habits.add(h.id().equals(id) ? habit : h);
        }
        HabitsData updated = new HabitsData(habits, data.marks());
        repository.save(updated);
        return status(habit, updated, LocalDate.now(clock));
    }

    /** Loescht das Habit samt aller Eintraege. Es gibt kein Archiv - weg ist weg. */
    public void delete(String id) {
        HabitsData data = repository.load();
        find(data, id);
        List<Habit> habits = data.habits().stream().filter(h -> !h.id().equals(id)).toList();
        List<Mark> marks = data.marks().stream().filter(m -> !m.habitId().equals(id)).toList();
        repository.save(new HabitsData(habits, marks));
    }

    // MARK: - Haken und Rueckfaelle

    public HabitStatus mark(String id, LocalDate date) {
        HabitsData data = repository.load();
        Habit habit = find(data, id);
        LocalDate today = LocalDate.now(clock);
        LocalDate day = date == null ? today : date;
        if (habit.kind().isAutomatic()) {
            throw badRequest("Dieses Habit wird automatisch erfasst.");
        }
        if (day.isAfter(today)) {
            throw badRequest("In der Zukunft gibt es nichts abzuhaken.");
        }
        if (day.isBefore(habit.createdAt())) {
            throw badRequest("Das Habit gab es an diesem Tag noch nicht.");
        }
        List<Mark> marks = new ArrayList<>(data.marks());
        Mark mark = new Mark(id, day);
        if (!marks.contains(mark)) {
            marks.add(mark);
        }
        HabitsData updated = new HabitsData(data.habits(), marks);
        repository.save(updated);
        return status(habit, updated, today);
    }

    public HabitStatus unmark(String id, LocalDate date) {
        HabitsData data = repository.load();
        Habit habit = find(data, id);
        List<Mark> marks = data.marks().stream()
                .filter(m -> !(m.habitId().equals(id) && m.date().equals(date)))
                .toList();
        HabitsData updated = new HabitsData(data.habits(), marks);
        repository.save(updated);
        return status(habit, updated, LocalDate.now(clock));
    }

    // MARK: - Der Stand eines Habits

    HabitStatus status(Habit habit, HabitsData data, LocalDate today) {
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
            // Die Quelle fehlt - dann lieber das sagen als eine Null zeigen,
            // die wie eine gerissene Straehne aussaehe.
            return new HabitStatus(habit.id(), habit.name(), habit.kind(), habit.unit(),
                    habit.weeklyStepGoal(), 0, false, false, null, List.of(), e.getMessage(),
                    habit.focusMinutesGoal(), habit.rhythm(), habit.timesPerPeriod(),
                    markedDays, habit.createdAt());
        }
    }

    /**
     * Die Tage mit Eintrag in den letzten {@link #MARKED_DAYS} Tagen - nur bei
     * Habits, die man selbst abhakt; automatische haben keine Eintraege.
     */
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

    /**
     * BUILD je Woche oder je Monat: erfuellt, sobald im Zeitraum genug Tage
     * abgehakt sind. Der laufende Zeitraum darf offen sein, wie bei den Tagen
     * das Heute; {@code doneToday} bleibt der Haken von heute - das ist, was
     * der Knopf in der App zeigt -, {@code progress} zaehlt den Zeitraum.
     */
    private Status periodic(Habit habit, LocalDate today, Predicate<LocalDate> marked) {
        Period period = habit.rhythm();
        int times = habit.times();
        UnaryOperator<LocalDate> previous = period == Period.WEEK
                ? start -> start.minusWeeks(1)
                : start -> start.minusMonths(1);
        LocalDate current = period == Period.WEEK ? Streaks.mondayOf(today) : Streaks.firstOfMonth(today);
        Predicate<LocalDate> periodDone = start -> countMarked(marked, start, period) >= times;
        int maxPeriods = period == Period.WEEK ? maxLookbackDays / 7 : maxLookbackDays / 28;
        int streak = Streaks.periodic(current, periodDone, previous, maxPeriods);
        boolean thisPeriodDone = periodDone.test(current);
        return new Status(habit, streak, marked.test(today),
                !thisPeriodDone && streak > 0,
                new Progress(countMarked(marked, current, period), times),
                Streaks.recentPeriods(current, periodDone, previous, RECENT));
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

    private static Period rhythm(HabitRequest request) {
        return request.kind() == HabitKind.BUILD && request.period() != null ? request.period() : null;
    }

    private static Integer times(HabitRequest request) {
        Period period = rhythm(request);
        if (period == null || period == Period.DAY) {
            return null;
        }
        return request.timesPerPeriod() == null ? 1 : request.timesPerPeriod();
    }

    /**
     * "Fokus-Zeit": erledigt, sobald die Sessions des Tages zusammen das Ziel
     * erreichen. Heute darf offen sein - die naechste Session kann noch kommen.
     */
    private Status focusDaily(Habit habit, LocalDate today) {
        int goal = habit.focusMinutesGoal() == null ? DEFAULT_FOCUS_MINUTES : habit.focusMinutesGoal();
        Map<LocalDate, Integer> perDay = focus.minutesPerDay(today.minusDays(maxLookbackDays), today);
        Predicate<LocalDate> done = day -> perDay.getOrDefault(day, 0) >= goal;
        return daily(habit, today, done, true)
                .withProgress(new Progress(perDay.getOrDefault(today, 0), goal));
    }

    private static int focusGoal(HabitRequest request) {
        return request.focusMinutesGoal() == null ? DEFAULT_FOCUS_MINUTES : request.focusMinutesGoal();
    }

    /**
     * @param todayMayBeOpen bei BUILD und FOOD kann der Haken heute noch kommen -
     *                       die Straehne lebt und ist "gefaehrdet". Bei QUIT ist
     *                       ein nicht erledigtes Heute ein Rueckfall, und der
     *                       ist entschieden.
     */
    private Status daily(Habit habit, LocalDate today, Predicate<LocalDate> done, boolean todayMayBeOpen) {
        int streak = Streaks.daily(today, done, maxLookbackDays, todayMayBeOpen);
        boolean doneToday = done.test(today);
        return new Status(habit, streak, doneToday,
                todayMayBeOpen && !doneToday && streak > 0,
                null, Streaks.recentDays(today, done, RECENT));
    }

    private Status weekly(Habit habit, LocalDate today) {
        int goal = habit.weeklyStepGoal() == null ? 0 : habit.weeklyStepGoal();
        LocalDate thisMonday = Streaks.mondayOf(today);
        // Ein Aufruf fuer den ganzen Rueckblick statt einer je Woche.
        Map<LocalDate, Integer> perDay = steps.steps(thisMonday.minusDays(maxLookbackDays), today);
        Predicate<LocalDate> weekDone = monday -> weekTotal(perDay, monday) >= goal && goal > 0;
        int thisWeek = weekTotal(perDay, thisMonday);
        int streak = Streaks.weekly(today, weekDone, maxLookbackDays / 7);
        return new Status(habit, streak, weekDone.test(thisMonday), false,
                new Progress(thisWeek, goal), Streaks.recentWeeks(today, weekDone, RECENT));
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

    /** QUIT: jeder Tag seit dem Vorsatz zaehlt - ausser einem mit Rueckfall. */
    private static Predicate<LocalDate> notRelapsed(Habit habit, HabitsData data) {
        Predicate<LocalDate> relapsed = marked(habit, data);
        return day -> !day.isBefore(habit.createdAt()) && !relapsed.test(day);
    }

    /**
     * "Track food" ist erledigt, wenn mindestens 80 % des kcal-Ziels erreicht
     * sind <b>oder</b> Fruehstueck, Mittag- und Abendessen je einen Eintrag
     * haben. Das Zweite faengt den Tag mit kleinem Appetit ab; das Erste den
     * Tag, an dem alles in einem Eintrag steht.
     */
    private boolean foodDone(LocalDate day) {
        LocalDate today = LocalDate.now(clock);
        boolean history = day.isBefore(today.minusDays(1));
        if (history) {
            Boolean cached = foodHistory.get(day);
            if (cached != null) {
                return cached;
            }
        }
        boolean done = isFoodDone(food.day(day));
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
        FoodClient.Day day = food.day(today);
        return new Progress((int) Math.round(day.kcal()),
                (int) Math.round(FOOD_KCAL_SHARE * day.targetKcal()));
    }

    // MARK: - Kleinkram

    private static Habit find(HabitsData data, String id) {
        return data.habits().stream()
                .filter(h -> h.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Habit nicht gefunden."));
    }

    private static void validate(HabitRequest request, boolean creating) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw badRequest("Ein Habit braucht einen Namen.");
        }
        if (request.name().trim().length() > MAX_NAME_LENGTH) {
            throw badRequest("Der Name darf höchstens " + MAX_NAME_LENGTH + " Zeichen haben.");
        }
        if (creating && request.kind() == null) {
            throw badRequest("Welche Art Habit soll es sein?");
        }
        if (request.kind() == HabitKind.STEPS) {
            Integer goal = request.weeklyStepGoal();
            if (goal == null || goal <= 0 || goal > MAX_STEP_GOAL) {
                throw badRequest("Das Wochenziel muss zwischen 1 und " + MAX_STEP_GOAL + " Schritten liegen.");
            }
        }
        if (request.period() != null && request.kind() != HabitKind.BUILD && request.period() != Period.DAY) {
            throw badRequest("Einen Rhythmus haben nur Habits zum Aufbauen.");
        }
        if (request.kind() == HabitKind.BUILD && request.period() != null && request.timesPerPeriod() != null) {
            int times = request.timesPerPeriod();
            if (times < 1 || times > request.period().maxTimes()) {
                throw badRequest("Wie oft je Zeitraum muss zwischen 1 und " + request.period().maxTimes() + " liegen.");
            }
        }
        if (request.kind() == HabitKind.FOCUS && request.focusMinutesGoal() != null) {
            int goal = request.focusMinutesGoal();
            if (goal <= 0 || goal > MAX_FOCUS_MINUTES) {
                throw badRequest("Das Tagesziel muss zwischen 1 und " + MAX_FOCUS_MINUTES + " Minuten liegen.");
            }
        }
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    /** Zwischenstand, bevor daraus ein {@link HabitStatus} wird. */
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
