package com.fherrmann.habits.cohabit.model;

/**
 * Die feste Auswahl an Reaktionen bis 05.10. - seitdem sind Reaktionen Emojis. Bleibt,
 * damit alte Eintraege und aeltere Apps (die diese Namen schicken) lesbar bleiben.
 */
public enum ReactionKind {
    STARK("💪"),
    RESPEKT("🙌"),
    WEITER_SO("🔥"),
    HAHA("😂");

    private final String emoji;

    ReactionKind(String emoji) {
        this.emoji = emoji;
    }

    /** Das Emoji, das an die Stelle dieser Art getreten ist. */
    public String emoji() {
        return emoji;
    }
}
