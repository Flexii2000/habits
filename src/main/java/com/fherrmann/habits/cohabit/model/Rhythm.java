package com.fherrmann.habits.cohabit.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Wie oft ein STREAK erfuellt sein will. Nur die Felder der jeweiligen Art sind
 * gesetzt: {@code weekdays} (ISO, 1 = Montag) bei WEEKDAYS, {@code times} bei
 * TIMES_PER_WEEK/TIMES_PER_MONTH, {@code days} bei INTERVAL.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Rhythm(RhythmKind kind, List<Integer> weekdays, Integer times, Integer days) {

    public static Rhythm daily() {
        return new Rhythm(RhythmKind.DAILY, null, null, null);
    }

    public static Rhythm timesPerWeek(int times) {
        return new Rhythm(RhythmKind.TIMES_PER_WEEK, null, times, null);
    }

    public static Rhythm timesPerMonth(int times) {
        return new Rhythm(RhythmKind.TIMES_PER_MONTH, null, times, null);
    }
}
