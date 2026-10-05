package com.fherrmann.habits.cohabit.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

/** Eine Reaktion: ein Emoji je Person und Ziel (seit 05.10.; vorher die feste Auswahl). */
public class Reaction {
    public String personId;
    public String emoji;
    /**
     * Die alte feste Auswahl - wird nur noch gelesen und beim naechsten Schreiben
     * des Ziels durch {@link #emoji} ersetzt.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public ReactionKind reaction;

    public Reaction() {
    }

    public Reaction(String personId, String emoji) {
        this.personId = personId;
        this.emoji = emoji;
    }

    /** Das Emoji, bei alten Eintraegen das ihrer Art. */
    @JsonIgnore
    public String effectiveEmoji() {
        return emoji != null ? emoji : reaction == null ? null : reaction.emoji();
    }
}
