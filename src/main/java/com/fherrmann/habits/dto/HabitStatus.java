package com.fherrmann.habits.dto;

import com.fherrmann.habits.model.HabitKind;
import com.fherrmann.habits.model.Period;
import com.fherrmann.habits.model.Unit;

import java.time.LocalDate;
import java.util.List;

/**
 * Ein Habit, wie die App ihn zeigt: mit Straehne und dem Stand von heute.
 *
 * @param streak      die laufende Straehne in {@code unit}. Bei BUILD zaehlt
 *                    heute mit, sobald abgehakt; ist heute noch offen, laeuft
 *                    die Straehne von gestern weiter und {@code atRisk} ist gesetzt
 * @param doneToday   ob der laufende Zeitraum (Tag bzw. Woche) erledigt ist
 * @param atRisk      heute noch nicht erledigt, aber die Straehne lebt noch -
 *                    bis Mitternacht
 * @param focusMinutesGoal nur bei FOCUS: das Tagesziel an Fokus-Minuten
 * @param period      bei BUILD der Rhythmus (DAY, WEEK, MONTH), sonst DAY
 * @param timesPerPeriod bei BUILD mit WEEK/MONTH: wie oft je Zeitraum
 * @param markedDays  bei BUILD/QUIT die Tage der letzten 31 Tage mit Eintrag
 *                    (Haken bzw. Rueckfall) - damit die App rueckwirkend
 *                    abhaken kann und weiss, was schon steht
 * @param createdAt   ab wann das Habit zaehlt - davor nimmt der Dienst keine Eintraege
 * @param progress    nur bei automatischen Habits, sonst {@code null}
 * @param recent      die letzten sieben Zeitraeume, aelteste zuerst - fuer die
 *                    Punktreihe unter dem Namen
 * @param unavailable gesetzt, wenn die Quelle (Kalorienzaehler, Weight
 *                    Tracker) nicht erreichbar war. Dann sind Straehne und
 *                    Punkte nicht zu gebrauchen, und die App zeigt lieber
 *                    diesen Satz als eine falsche Null
 */
public record HabitStatus(
        String id,
        String name,
        HabitKind kind,
        Unit unit,
        Integer weeklyStepGoal,
        int streak,
        boolean doneToday,
        boolean atRisk,
        Progress progress,
        List<Boolean> recent,
        String unavailable,
        Integer focusMinutesGoal,
        Period period,
        Integer timesPerPeriod,
        List<LocalDate> markedDays,
        LocalDate createdAt) {

    public HabitStatus {
        markedDays = markedDays == null ? List.of() : List.copyOf(markedDays);
    }

    /** Fuer Aufrufer ohne Fokus-Ziel und Rhythmus. */
    public HabitStatus(String id, String name, HabitKind kind, Unit unit, Integer weeklyStepGoal,
                       int streak, boolean doneToday, boolean atRisk, Progress progress,
                       List<Boolean> recent, String unavailable) {
        this(id, name, kind, unit, weeklyStepGoal, streak, doneToday, atRisk, progress, recent, unavailable,
                null, Period.DAY, null, List.of(), null);
    }

    public HabitStatus(String id, String name, HabitKind kind, Unit unit, Integer weeklyStepGoal,
                       int streak, boolean doneToday, boolean atRisk, Progress progress,
                       List<Boolean> recent, String unavailable, Integer focusMinutesGoal) {
        this(id, name, kind, unit, weeklyStepGoal, streak, doneToday, atRisk, progress, recent, unavailable,
                focusMinutesGoal, Period.DAY, null, List.of(), null);
    }

    public HabitStatus(String id, String name, HabitKind kind, Unit unit, Integer weeklyStepGoal,
                       int streak, boolean doneToday, boolean atRisk, Progress progress,
                       List<Boolean> recent, String unavailable, Integer focusMinutesGoal,
                       Period period, Integer timesPerPeriod) {
        this(id, name, kind, unit, weeklyStepGoal, streak, doneToday, atRisk, progress, recent, unavailable,
                focusMinutesGoal, period, timesPerPeriod, List.of(), null);
    }
}
