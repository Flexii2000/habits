package com.fherrmann.habits.cohabit.rules;

import java.time.LocalDate;

/**
 * Wie eine Serie gezaehlt wird - ohne Zustand, ohne Uhr, alles kommt herein.
 *
 * <p>Die Regel, die nicht offensichtlich ist: <b>der laufende Zeitraum darf
 * offen sein.</b> Wer heute (diese Woche) noch nicht erfuellt hat, hat die Serie
 * nicht verloren - sie zaehlt vom letzten abgeschlossenen Zeitraum weiter und ist
 * dann gefaehrdet. Uebernommen aus der alten Habit-App, unveraendert.
 */
public final class StreakCalc {

    /** Schutz gegen endloses Ueberspringen (etwa ein Jahr voller Pausen). */
    private static final int MAX_ITERATIONS = 20_000;

    private StreakCalc() {
    }

    /**
     * @param current         die laufende Serie in Zeitraeumen
     * @param atRisk          laufender Zeitraum faellig, noch nicht erfuellt, Serie > 0
     * @param currentStart    Beginn des laufenden Zeitraums
     * @param currentRequired was der laufende Zeitraum verlangt (0 = nicht faellig)
     * @param currentAchieved was darin schon geschafft ist
     * @param runStart        Beginn des aeltesten Zeitraums der laufenden Serie
     *                        (Schluessel fuer Bestserie und Meilensteine), sonst null
     */
    public record Outcome(int current, boolean atRisk, LocalDate currentStart, LocalDate currentEnd,
                          int currentRequired, int currentAchieved, boolean currentDone, LocalDate runStart) {
    }

    /** Erfuellte und faellige Zeitraeume - fuer die Erfuellungsquote. */
    public record Rate(int fulfilled, int due) {

        public Rate plus(Rate other) {
            return new Rate(fulfilled + other.fulfilled, due + other.due);
        }

        /** Ganze Prozent; ohne faellige Zeitraeume 0. */
        public int percent() {
            return due == 0 ? 0 : (int) Math.round(100.0 * fulfilled / due);
        }
    }

    /**
     * Die laufende Serie, rueckwaerts ab dem laufenden Zeitraum.
     *
     * @param lowerBound erster Tag, der zaehlt (Beitritt), oder {@code null} fuer
     *                   automatische Quellen, die wie bisher unbegrenzt zurueckschauen
     * @param maxStreak  Deckel; bei automatischen Quellen der alte (730 Tage bzw.
     *                   104 Wochen) gegen endloses Nachfragen
     */
    public static Outcome evaluate(PeriodScheme scheme, Judge judge, LocalDate today, LocalDate lowerBound,
                                   int maxStreak) {
        LocalDate current = scheme.startOf(today);
        LocalDate currentEnd = scheme.endOf(current);
        int required = judge.required(current, currentEnd);
        int achieved = judge.achieved(current, currentEnd);
        boolean done = required > 0 && achieved >= required;
        int streak = 0;
        LocalDate runStart = null;
        if (done) {
            streak = 1;
            runStart = current;
        }
        LocalDate period = scheme.previous(current);
        int iterations = 0;
        while (streak < maxStreak && iterations++ < MAX_ITERATIONS) {
            LocalDate end = scheme.endOf(period);
            if (lowerBound != null && end.isBefore(lowerBound)) {
                break;
            }
            int r = judge.required(period, end);
            if (r == 0) {
                period = scheme.previous(period);
                continue;
            }
            if (judge.achieved(period, end) >= r) {
                streak++;
                runStart = period;
                period = scheme.previous(period);
            } else {
                break;
            }
        }
        boolean atRisk = required > 0 && !done && streak > 0;
        return new Outcome(streak, atRisk, current, currentEnd, required, achieved, done, runStart);
    }

    /**
     * Die laengste Serie zwischen {@code from} und heute. Ein noch offener
     * laufender Zeitraum unterbricht nichts.
     */
    public static int longest(PeriodScheme scheme, Judge judge, LocalDate from, LocalDate today) {
        LocalDate current = scheme.startOf(today);
        int run = 0;
        int best = 0;
        int iterations = 0;
        for (LocalDate period = scheme.startOf(from); !period.isAfter(current) && iterations++ < MAX_ITERATIONS;
             period = scheme.next(period)) {
            LocalDate end = scheme.endOf(period);
            int r = judge.required(period, end);
            if (r == 0) {
                continue;
            }
            if (judge.achieved(period, end) >= r) {
                run++;
                best = Math.max(best, run);
            } else if (!period.equals(current)) {
                run = 0;
            }
        }
        return best;
    }

    /**
     * Erfuellte / faellige Zeitraeume seit {@code from}: abgeschlossene Zeitraeume,
     * dazu der laufende, wenn er schon erfuellt ist. Mit {@code rangeFrom} und
     * {@code rangeTo} nur die Zeitraeume, die in diesem Bereich enden (Statistik).
     */
    public static Rate rate(PeriodScheme scheme, Judge judge, LocalDate from, LocalDate today,
                            LocalDate rangeFrom, LocalDate rangeTo) {
        LocalDate current = scheme.startOf(today);
        int fulfilled = 0;
        int due = 0;
        int iterations = 0;
        LocalDate first = scheme.startOf(from);
        if (rangeFrom != null && scheme.startOf(rangeFrom).isAfter(first)) {
            first = scheme.startOf(rangeFrom);
        }
        for (LocalDate period = first; !period.isAfter(current) && iterations++ < MAX_ITERATIONS;
             period = scheme.next(period)) {
            LocalDate end = scheme.endOf(period);
            LocalDate countedEnd = period.equals(current) ? today : end;
            if (rangeFrom != null && (countedEnd.isBefore(rangeFrom) || countedEnd.isAfter(rangeTo))) {
                continue;
            }
            int r = judge.required(period, end);
            if (r == 0) {
                continue;
            }
            boolean ok = judge.achieved(period, end) >= r;
            if (period.equals(current)) {
                if (ok) {
                    fulfilled++;
                    due++;
                }
            } else {
                due++;
                if (ok) {
                    fulfilled++;
                }
            }
        }
        return new Rate(fulfilled, due);
    }
}
