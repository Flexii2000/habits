package com.fherrmann.habits.model;

import java.time.LocalDate;

/**
 * Eine Gewohnheit.
 *
 * @param id             stabile Kennung, beim Anlegen vergeben
 * @param name           wie sie in der Liste heisst
 * @param kind           siehe {@link HabitKind}
 * @param weeklyStepGoal nur bei {@link HabitKind#STEPS}: das Wochenziel
 * @param focusMinutesGoal nur bei {@link HabitKind#FOCUS}: Fokus-Minuten je Tag
 * @param period         nur bei {@link HabitKind#BUILD}: jeden Tag, je Woche, je
 *                       Monat - fehlt es (aeltere Daten), ist es DAY
 * @param timesPerPeriod nur bei BUILD mit WEEK/MONTH: wie oft je Zeitraum
 * @param createdAt      ab wann sie zaehlt. Bei BUILD und QUIT gibt es davor
 *                       keine Haken, und die Straehne eines QUIT-Habits
 *                       beginnt an diesem Tag - man kann nicht schon vor dem
 *                       Vorsatz durchgehalten haben
 */
public record Habit(
        String id,
        String name,
        HabitKind kind,
        Integer weeklyStepGoal,
        LocalDate createdAt,
        Integer focusMinutesGoal,
        Period period,
        Integer timesPerPeriod) {

    /** Fuer alle, die weder Fokus-Ziel noch Rhythmus kennen - die Reihenfolge der Felder bleibt. */
    public Habit(String id, String name, HabitKind kind, Integer weeklyStepGoal, LocalDate createdAt) {
        this(id, name, kind, weeklyStepGoal, createdAt, null, null, null);
    }

    public Habit(String id, String name, HabitKind kind, Integer weeklyStepGoal, LocalDate createdAt,
                 Integer focusMinutesGoal) {
        this(id, name, kind, weeklyStepGoal, createdAt, focusMinutesGoal, null, null);
    }

    /** Der Rhythmus, mit Vorgabe: taeglich. Nur BUILD hat einen anderen. */
    public Period rhythm() {
        return kind == HabitKind.BUILD && period != null ? period : Period.DAY;
    }

    /** Wie oft je Zeitraum, mit Vorgabe: einmal. */
    public int times() {
        return timesPerPeriod == null || timesPerPeriod < 1 ? 1 : timesPerPeriod;
    }

    /** Woran die Straehne gemessen wird: Tage, Wochen oder Monate. */
    public Unit unit() {
        return kind == HabitKind.STEPS ? Unit.WEEKS : rhythm().unit();
    }
}
