package com.fherrmann.habits.cohabit.model;

/**
 * Felix bisherige automatische Habits: der Dienst rechnet aus einer Quelle,
 * niemand hakt ab.
 */
public enum AutoSource {
    FOOD, STEPS_WEEKLY, FOCUS,
    /**
     * Im Kalorienziel geblieben, im Wochenmittel (Torben/Felix, 01.10.): eine Woche Mo-So
     * zaehlt, wenn der Schnitt der getrackten Tage (Regel wie FOOD) hoechstens beim
     * kcal-Ziel liegt. Entschieden wird erst nach Sonntag.
     */
    FOOD_TARGET_WEEKLY
}
