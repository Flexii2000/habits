package com.fherrmann.habits.cohabit.model;

import java.time.Instant;

/** Symmetrisch: {@code a} und {@code b} sind in beide Richtungen befreundet. */
public class Friendship {
    public String a;
    public String b;
    public Instant since;

    public boolean involves(String personId) {
        return a.equals(personId) || b.equals(personId);
    }

    public String other(String personId) {
        return a.equals(personId) ? b : a;
    }
}
