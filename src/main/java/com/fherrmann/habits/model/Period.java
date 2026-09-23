package com.fherrmann.habits.model;

/**
 * In welchem Rhythmus ein BUILD-Habit erfuellt sein will: jeden Tag, oder
 * so-und-so-oft je Woche oder je Monat ("politisch aktiv sein: 2x im
 * Monat", "Zeitungsartikel lesen: 1x die Woche"). Die Woche beginnt Montag,
 * der Monat am Ersten - wie die Schritte-Woche.
 */
public enum Period {
    DAY,
    WEEK,
    MONTH;

    /** Woran die Straehne dann gemessen wird. */
    public Unit unit() {
        return switch (this) {
            case DAY -> Unit.DAYS;
            case WEEK -> Unit.WEEKS;
            case MONTH -> Unit.MONTHS;
        };
    }

    /** Hoechstens so oft je Zeitraum - mehr Tage hat er nicht. */
    public int maxTimes() {
        return switch (this) {
            case DAY -> 1;
            case WEEK -> 7;
            case MONTH -> 31;
        };
    }
}
