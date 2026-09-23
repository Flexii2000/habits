package com.fherrmann.habits.dto;

import com.fherrmann.habits.model.HabitKind;
import com.fherrmann.habits.model.Period;

/** Was die App beim Anlegen oder Umbenennen schickt. */
public record HabitRequest(String name, HabitKind kind, Integer weeklyStepGoal, Integer focusMinutesGoal,
                           Period period, Integer timesPerPeriod) {

    public HabitRequest(String name, HabitKind kind, Integer weeklyStepGoal) {
        this(name, kind, weeklyStepGoal, null, null, null);
    }

    public HabitRequest(String name, HabitKind kind, Integer weeklyStepGoal, Integer focusMinutesGoal) {
        this(name, kind, weeklyStepGoal, focusMinutesGoal, null, null);
    }
}
