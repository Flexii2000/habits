package com.fherrmann.habits.cohabit.model;

public enum HealthMetric {
    STEPS, RUNNING_DISTANCE, WORKOUTS, WORKOUT_MINUTES,
    /**
     * Die kcal des Tages aus dem Kalorienzaehler (Healthy). Anders als die anderen holt
     * sie der Dienst selbst ({@code KcalSync}) - je Mitglied mit Einwilligung und
     * Healthy-Zugang; die Apps schicken hier nichts.
     */
    KCAL
}
