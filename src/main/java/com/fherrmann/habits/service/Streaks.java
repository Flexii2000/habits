package com.fherrmann.habits.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Wie eine Straehne gezaehlt wird. Ohne Zustand, ohne Uhr - alles kommt herein.
 *
 * <p>Die eine Regel, die nicht offensichtlich ist: <b>heute darf offen sein.</b>
 * Wer heute noch nicht abgehakt hat, hat die Straehne nicht verloren - erst
 * um Mitternacht. Bis dahin zaehlt sie von gestern weiter. Snapchat macht es
 * genauso, und alles andere fuehlt sich an wie eine Strafe fuer Fruehaufsteher.
 */
final class Streaks {

    private Streaks() {
    }

    /**
     * Zusammenhaengende erledigte Tage, rueckwaerts ab heute.
     *
     * @param todayMayBeOpen ob ein nicht erledigtes Heute noch offen ist (BUILD,
     *                       FOOD: der Haken kann bis Mitternacht kommen) oder
     *                       schon entschieden (QUIT: ein Rueckfall heute IST der
     *                       Riss - die Tage davor laufen nicht weiter)
     * @param maxDays        Deckel, damit ein Habit, das "immer" erledigt ist,
     *                       nicht bis in die Steinzeit nachfragt
     */
    static int daily(LocalDate today, Predicate<LocalDate> done, int maxDays, boolean todayMayBeOpen) {
        LocalDate day;
        if (done.test(today)) {
            day = today;
        } else if (todayMayBeOpen) {
            day = today.minusDays(1);
        } else {
            return 0;
        }
        int streak = 0;
        while (streak < maxDays && done.test(day)) {
            streak++;
            day = day.minusDays(1);
        }
        return streak;
    }

    /** Dasselbe in Wochen. {@code weekDone} bekommt den Montag der Woche. */
    static int weekly(LocalDate today, Predicate<LocalDate> weekDone, int maxWeeks) {
        LocalDate week = mondayOf(today);
        LocalDate start = weekDone.test(week) ? week : week.minusWeeks(1);
        int streak = 0;
        while (streak < maxWeeks && weekDone.test(start)) {
            streak++;
            start = start.minusWeeks(1);
        }
        return streak;
    }

    /** Die letzten {@code count} Tage, aelteste zuerst, heute als letzter. */
    static List<Boolean> recentDays(LocalDate today, Predicate<LocalDate> done, int count) {
        List<Boolean> recent = new ArrayList<>(count);
        for (int i = count - 1; i >= 0; i--) {
            recent.add(done.test(today.minusDays(i)));
        }
        return recent;
    }

    /** Die letzten {@code count} Wochen, aelteste zuerst, die laufende als letzte. */
    static List<Boolean> recentWeeks(LocalDate today, Predicate<LocalDate> weekDone, int count) {
        LocalDate week = mondayOf(today);
        List<Boolean> recent = new ArrayList<>(count);
        for (int i = count - 1; i >= 0; i--) {
            recent.add(weekDone.test(week.minusWeeks(i)));
        }
        return recent;
    }

    /**
     * Der Montag, mit dem die Woche dieses Tages beginnt.
     *
     * <p>Montag 0:00 - nicht Sonntag, nicht der Tag, an dem das Habit angelegt
     * wurde. Ein Sonntag gehoert damit noch zur Vorwoche.
     */
    static LocalDate mondayOf(LocalDate day) {
        return day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }
}
