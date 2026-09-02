package com.fherrmann.habits.model;

import java.util.List;

/** Alles, was in {@code habits.json} steht. */
public record HabitsData(List<Habit> habits, List<Mark> marks) {

    public HabitsData {
        habits = habits == null ? List.of() : List.copyOf(habits);
        marks = marks == null ? List.of() : List.copyOf(marks);
    }

    public static HabitsData empty() {
        return new HabitsData(List.of(), List.of());
    }
}
