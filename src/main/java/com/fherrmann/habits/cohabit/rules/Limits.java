package com.fherrmann.habits.cohabit.rules;

import com.fherrmann.habits.cohabit.model.Cohabit;
import com.fherrmann.habits.cohabit.model.CohabitType;
import com.fherrmann.habits.cohabit.model.LimitConfig;

/** Abstinenz mit Tageslimit aus dem Logbook. */
public final class Limits {

    /** Die Tage des Logbooks sind Berliner Tage - so traegt die App sie ein. */
    public static final String ZONE = "Europe/Berlin";
    /** Nachtragen wie im Logbook: 14 Tage zurueck. */
    public static final int BACKFILL_HOURS = 14 * 24;
    public static final int MAX_PER_DAY = 1000;

    private Limits() {
    }

    public static boolean isLogbookLimit(Cohabit c) {
        return c.type == CohabitType.ABSTINENCE && c.abstinence != null && c.abstinence.limit() != null;
    }

    public static LimitConfig of(Cohabit c) {
        return isLogbookLimit(c) ? c.abstinence.limit() : null;
    }

    /** "2", "2,5" - Mengen wie im Logbook, ohne ueberfluessige Nachkommastelle. */
    public static String count(double value) {
        if (value == Math.rint(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(Math.round(value * 10) / 10.0).replace('.', ',');
    }
}
