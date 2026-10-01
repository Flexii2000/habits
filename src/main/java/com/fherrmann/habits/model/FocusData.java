package com.fherrmann.habits.model;

import java.util.List;

/** Alles, was in {@code focus.json} steht: die Baeume und ihre Kategorien. */
public record FocusData(List<FocusSession> sessions, List<FocusCategory> categories) {

    public FocusData {
        sessions = sessions == null ? List.of() : List.copyOf(sessions);
        categories = categories == null ? List.of() : List.copyOf(categories);
    }

    /** Fuer Aufrufer ohne Kategorien (aeltere Tests, Migration). */
    public FocusData(List<FocusSession> sessions) {
        this(sessions, List.of());
    }

    public static FocusData empty() {
        return new FocusData(List.of(), List.of());
    }
}
