package com.fherrmann.habits.cohabit.rules;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

/**
 * Abstinenz: die Serie laeuft von selbst, nur eine Unterbrechung setzt sie zurueck.
 *
 * <p>Heute zaehlt mit - und eine Unterbrechung heute ist entschieden: dann steht
 * die Serie auf null, die Tage davor laufen nicht "noch offen" weiter. So hat es
 * die alte App bei Quit-Habits gezaehlt.
 */
public final class AbstinenceCalc {

    private AbstinenceCalc() {
    }

    /** Eine Serie: {@code start} bis {@code end} einschliesslich. */
    public record Series(LocalDate start, LocalDate end, int days, boolean current) {
    }

    /**
     * @param current     Tage der laufenden Serie
     * @param record      laengste Serie ueberhaupt, die laufende eingeschlossen
     * @param priorRecord laengste abgeschlossene Serie (ohne die laufende)
     * @param series      alle Serien mit mindestens einem Tag, aelteste zuerst, die
     *                    laufende zuletzt (auch mit 0 Tagen nach einer Unterbrechung heute)
     */
    public record Outcome(int current, int record, int priorRecord, boolean breakToday, List<Series> series,
                          LocalDate runStart) {
    }

    public static Outcome evaluate(LocalDate start, Collection<LocalDate> breaks, LocalDate today) {
        TreeSet<LocalDate> sorted = new TreeSet<>();
        for (LocalDate b : breaks) {
            if (!b.isBefore(start) && !b.isAfter(today)) {
                sorted.add(b);
            }
        }
        List<Series> series = new ArrayList<>();
        int prior = 0;
        LocalDate cursor = start;
        for (LocalDate b : sorted) {
            int days = (int) ChronoUnit.DAYS.between(cursor, b);
            if (days > 0) {
                series.add(new Series(cursor, b.minusDays(1), days, false));
                prior = Math.max(prior, days);
            }
            cursor = b.plusDays(1);
        }
        int current = cursor.isAfter(today) ? 0 : (int) ChronoUnit.DAYS.between(cursor, today) + 1;
        if (!start.isAfter(today)) {
            series.add(new Series(cursor, today, current, true));
        }
        boolean breakToday = sorted.contains(today);
        return new Outcome(current, Math.max(prior, current), prior, breakToday, series,
                current > 0 ? cursor : null);
    }

    /** Gruppenmodus: Tage seit der letzten Unterbrechung irgendeines Mitglieds. */
    public static int groupDays(LocalDate start, Collection<LocalDate> allBreaks, LocalDate today) {
        return evaluate(start, allBreaks, today).current();
    }
}
