package com.fherrmann.habits.model;

import java.time.LocalDate;

/**
 * Ein Eintrag zu einem Tag.
 *
 * <p>Was er bedeutet, haengt an der Art des Habits: bei {@link HabitKind#BUILD}
 * ist es der Haken ("gemacht"), bei {@link HabitKind#QUIT} der Rueckfall
 * ("doch getan"). Ein Eintrag je Habit und Tag; automatische Habits haben
 * keine.
 */
public record Mark(String habitId, LocalDate date) {
}
