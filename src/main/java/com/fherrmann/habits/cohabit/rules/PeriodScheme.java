package com.fherrmann.habits.cohabit.rules;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;

/**
 * Wie die Zeit in Zeitraeume zerfaellt: Tage, Wochen (ab Montag), Monate (ab dem
 * Ersten) oder Fenster zu n Tagen ab dem Start des Co-Habits.
 */
public interface PeriodScheme {

    /** Der erste Tag des Zeitraums, in dem {@code day} liegt. */
    LocalDate startOf(LocalDate day);

    /** Der letzte Tag des Zeitraums, der an {@code start} beginnt (einschliesslich). */
    LocalDate endOf(LocalDate start);

    StreakUnit unit();

    default LocalDate previous(LocalDate start) {
        return startOf(start.minusDays(1));
    }

    default LocalDate next(LocalDate start) {
        return endOf(start).plusDays(1);
    }

    static PeriodScheme days() {
        return new PeriodScheme() {
            public LocalDate startOf(LocalDate day) {
                return day;
            }

            public LocalDate endOf(LocalDate start) {
                return start;
            }

            public StreakUnit unit() {
                return StreakUnit.DAYS;
            }
        };
    }

    /** Montag 0:00 - ein Sonntag gehoert noch zur Vorwoche. */
    static PeriodScheme weeks() {
        return new PeriodScheme() {
            public LocalDate startOf(LocalDate day) {
                return mondayOf(day);
            }

            public LocalDate endOf(LocalDate start) {
                return start.plusDays(6);
            }

            public StreakUnit unit() {
                return StreakUnit.WEEKS;
            }
        };
    }

    static PeriodScheme months() {
        return new PeriodScheme() {
            public LocalDate startOf(LocalDate day) {
                return day.withDayOfMonth(1);
            }

            public LocalDate endOf(LocalDate start) {
                return start.with(TemporalAdjusters.lastDayOfMonth());
            }

            public StreakUnit unit() {
                return StreakUnit.MONTHS;
            }
        };
    }

    /** Fenster zu je {@code n} Tagen, gezaehlt ab {@code anchor} (dem Start des Co-Habits). */
    static PeriodScheme windows(LocalDate anchor, int n) {
        return new PeriodScheme() {
            public LocalDate startOf(LocalDate day) {
                long offset = ChronoUnit.DAYS.between(anchor, day);
                return anchor.plusDays(Math.floorDiv(offset, n) * n);
            }

            public LocalDate endOf(LocalDate start) {
                return start.plusDays(n - 1L);
            }

            public StreakUnit unit() {
                return StreakUnit.WINDOWS;
            }
        };
    }

    static LocalDate mondayOf(LocalDate day) {
        return day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }
}
