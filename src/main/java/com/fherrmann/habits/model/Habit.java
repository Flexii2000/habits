package com.fherrmann.habits.model;

import java.time.LocalDate;

/**
 * Eine Gewohnheit.
 *
 * @param id             stabile Kennung, beim Anlegen vergeben
 * @param name           wie sie in der Liste heisst
 * @param kind           siehe {@link HabitKind}
 * @param weeklyStepGoal nur bei {@link HabitKind#STEPS}: das Wochenziel
 * @param createdAt      ab wann sie zaehlt. Bei BUILD und QUIT gibt es davor
 *                       keine Haken, und die Straehne eines QUIT-Habits
 *                       beginnt an diesem Tag - man kann nicht schon vor dem
 *                       Vorsatz durchgehalten haben
 */
public record Habit(
        String id,
        String name,
        HabitKind kind,
        Integer weeklyStepGoal,
        LocalDate createdAt) {
}
