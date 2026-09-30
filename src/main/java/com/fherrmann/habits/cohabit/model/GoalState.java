package com.fherrmann.habits.cohabit.model;

import java.time.Instant;

/** Gesetzt, sobald die Deadline vorbei und das Ziel ausgewertet ist. */
public class GoalState {
    public Instant finishedAt;
    public boolean reached;
    public double total;
    public String messageId;
}
