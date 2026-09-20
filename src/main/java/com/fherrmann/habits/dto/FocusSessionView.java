package com.fherrmann.habits.dto;

import com.fherrmann.habits.model.FocusSession;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Eine Session, wie der Wald sie zeigt - mit Dauer und dem Tag, dem sie
 * zugerechnet wird (der Tag des Beginns in Felix' Zeitzone).
 */
public record FocusSessionView(String id, Instant start, Instant end, int minutes, LocalDate day) {

    public static FocusSessionView of(FocusSession s, LocalDate day) {
        return new FocusSessionView(s.id(), s.start(), s.end(), s.minutes(), day);
    }
}
