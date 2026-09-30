package com.fherrmann.habits.cohabit.model;

/** Die feste Auswahl an Reaktionen - je Person hoechstens eine je Art. */
public enum ReactionKind {
    STARK("Stark"),
    RESPEKT("Respekt"),
    WEITER_SO("Weiter so"),
    HAHA("Haha");

    private final String label;

    ReactionKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
