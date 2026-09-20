package com.fherrmann.habits.dto;

import com.fherrmann.habits.model.HabitKind;

/** Was die App beim Anlegen oder Umbenennen schickt. */
public record HabitRequest(String name, HabitKind kind, Integer weeklyStepGoal, Integer focusMinutesGoal) {

    public HabitRequest(String name, HabitKind kind, Integer weeklyStepGoal) {
        this(name, kind, weeklyStepGoal, null);
    }
}
