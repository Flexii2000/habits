package com.fherrmann.habits.cohabit.model;

public enum HealthMetric {
    STEPS, RUNNING_DISTANCE, WORKOUTS, WORKOUT_MINUTES,
    /**
     * Die kcal des Tages aus dem Kalorienzaehler (Healthy). Anders als die anderen holt
     * sie der Dienst selbst ({@code KcalSync}) - je Mitglied mit Einwilligung und
     * Healthy-Zugang; die Apps schicken hier nichts.
     */
    KCAL,
    /**
     * Ein beendetes Training im Gym-Tab von Healthy (Weight Tracker, Felix 14.10.). Holt der
     * Dienst selbst wie die kcal ({@code GymSync}); nur als Serie, und abgehakt wird ohne Wert -
     * von Hand geht es weiter.
     */
    GYM;

    /** Kommt aus Healthy (Kalorienzaehler, Weight Tracker) statt von Apple Health/Health Connect. */
    public boolean fromHealthy() {
        return this == KCAL || this == GYM;
    }
}
