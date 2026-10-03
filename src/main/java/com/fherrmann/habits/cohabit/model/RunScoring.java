package com.fherrmann.habits.cohabit.model;

/**
 * Die Gewichte einer Lauf-Challenge ({@link Scoring#RUN_POINTS}). Ein Lauf bringt
 * die Basis (hoechstens einmal je Person und Tag, ab {@code baseMinMinutes}), je
 * volle km {@code pointsPerKm} und je volle {@code minutesPerPoint} Minuten einen
 * Punkt - wenn die Pace schneller ist als {@code paceLimitSeconds} je km.
 */
public record RunScoring(Integer basePoints, Integer pointsPerKm, Integer minutesPerPoint, Integer baseMinMinutes,
                         Integer paceLimitSeconds) {

    public static final RunScoring DEFAULT = new RunScoring(10, 1, 6, 20, 480);

    public int base() {
        return basePoints == null ? DEFAULT.basePoints : basePoints;
    }

    public int perKm() {
        return pointsPerKm == null ? DEFAULT.pointsPerKm : pointsPerKm;
    }

    public int perMinutes() {
        return minutesPerPoint == null ? DEFAULT.minutesPerPoint : minutesPerPoint;
    }

    public int baseMinutes() {
        return baseMinMinutes == null ? DEFAULT.baseMinMinutes : baseMinMinutes;
    }

    public int paceLimit() {
        return paceLimitSeconds == null ? DEFAULT.paceLimitSeconds : paceLimitSeconds;
    }
}
