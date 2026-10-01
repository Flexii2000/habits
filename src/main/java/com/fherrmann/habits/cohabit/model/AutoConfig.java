package com.fherrmann.habits.cohabit.model;

/**
 * Woraus ein automatisches Co-Habit zaehlt.
 *
 * @param focusMinutesGoal  FOCUS: Minuten je Tag bzw. je Woche ({@code focusPeriod})
 * @param focusCategoryId   FOCUS: nur Baeume dieser Kategorie aus dem Wald; {@code null}: alle
 * @param focusCategoryName FOCUS: ihr Name, vom Dienst eingetragen und bei Umbenennung
 *                          nachgezogen - die Kategorien liegen in {@code focus.json}
 * @param focusPeriod       FOCUS: DAY (Vorgabe, wie bisher) oder WEEK
 */
public record AutoConfig(AutoSource source, Integer weeklyStepGoal, Integer focusMinutesGoal,
                         String focusCategoryId, String focusCategoryName, FocusPeriod focusPeriod) {

    /** Vorgabe fuer die Fokus-Zeit: vier Stunden Baumzeit am Tag, wie bisher. */
    public static final int DEFAULT_FOCUS_MINUTES = 240;

    /** Fuer alle, die weder Kategorie noch Zeitraum kennen (Migration, alte Clients). */
    public AutoConfig(AutoSource source, Integer weeklyStepGoal, Integer focusMinutesGoal) {
        this(source, weeklyStepGoal, focusMinutesGoal, null, null, null);
    }

    public int focusGoal() {
        return focusMinutesGoal == null ? DEFAULT_FOCUS_MINUTES : focusMinutesGoal;
    }

    public int stepGoal() {
        return weeklyStepGoal == null ? 0 : weeklyStepGoal;
    }

    /** Ein Fokus-Habit mit Minuten je Woche statt je Tag. */
    public boolean focusWeekly() {
        return source == AutoSource.FOCUS && focusPeriod == FocusPeriod.WEEK;
    }

    public AutoConfig withFocusCategoryName(String name) {
        return new AutoConfig(source, weeklyStepGoal, focusMinutesGoal, focusCategoryId, name, focusPeriod);
    }
}
