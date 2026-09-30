package com.fherrmann.habits.cohabit.model;

import java.time.Instant;

/** Ein Platz in einer Challenge-Runde. {@code reachedAt} nur bei FIRST_TO_TARGET. */
public class Standing {
    public String personId;
    public double score;
    public int rank;
    public Instant reachedAt;
}
