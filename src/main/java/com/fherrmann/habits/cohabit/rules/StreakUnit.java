package com.fherrmann.habits.cohabit.rules;

/** Woran eine Serie gemessen wird - mit den Beschriftungen, die alle Clients zeigen. */
public enum StreakUnit {
    DAYS("Tag", "Tage", "T"),
    WEEKS("Woche", "Wochen", "Wo."),
    MONTHS("Monat", "Monate", "Mon."),
    WINDOWS("Mal", "Mal", "×");

    private final String singular;
    private final String plural;
    private final String abbreviation;

    StreakUnit(String singular, String plural, String abbreviation) {
        this.singular = singular;
        this.plural = plural;
        this.abbreviation = abbreviation;
    }

    public String label(long value) {
        return value == 1 ? singular : plural;
    }

    public String plural() {
        return plural;
    }

    public String abbreviation() {
        return abbreviation;
    }

    /** Ungefaehre Laenge in Tagen - nur um Serien verschiedener Einheiten zu vergleichen. */
    public int approxDays(int windowDays) {
        return switch (this) {
            case DAYS -> 1;
            case WEEKS -> 7;
            case MONTHS -> 30;
            case WINDOWS -> Math.max(1, windowDays);
        };
    }
}
