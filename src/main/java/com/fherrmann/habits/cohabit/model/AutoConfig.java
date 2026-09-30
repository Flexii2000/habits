package com.fherrmann.habits.cohabit.model;

public record AutoConfig(AutoSource source, Integer weeklyStepGoal, Integer focusMinutesGoal) {

    /** Vorgabe fuer die Fokus-Zeit: vier Stunden Baumzeit am Tag, wie bisher. */
    public static final int DEFAULT_FOCUS_MINUTES = 240;

    public int focusGoal() {
        return focusMinutesGoal == null ? DEFAULT_FOCUS_MINUTES : focusMinutesGoal;
    }

    public int stepGoal() {
        return weeklyStepGoal == null ? 0 : weeklyStepGoal;
    }
}
