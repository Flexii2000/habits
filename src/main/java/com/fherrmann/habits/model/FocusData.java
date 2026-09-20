package com.fherrmann.habits.model;

import java.util.List;

/** Alles, was in {@code focus.json} steht. */
public record FocusData(List<FocusSession> sessions) {

    public FocusData {
        sessions = sessions == null ? List.of() : List.copyOf(sessions);
    }

    public static FocusData empty() {
        return new FocusData(List.of());
    }
}
