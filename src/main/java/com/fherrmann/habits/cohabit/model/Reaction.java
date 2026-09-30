package com.fherrmann.habits.cohabit.model;

public class Reaction {
    public String personId;
    public ReactionKind reaction;

    public Reaction() {
    }

    public Reaction(String personId, ReactionKind reaction) {
        this.personId = personId;
        this.reaction = reaction;
    }
}
