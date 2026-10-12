package com.fherrmann.habits.cohabit.rules;

import java.time.LocalDate;
import java.util.Map;

/**
 * Was das Logbook fuer eine Abstinenz mit Tageslimit hergibt: die Menge je Tag. Ein Tag ohne
 * Wert zaehlt als 0 - wer nichts eintraegt, hat das Limit nicht ueberschritten.
 *
 * @param unavailable gesetzt, wenn das Logbook nicht antwortete oder das Verhalten fehlt - dann
 *                    gilt nichts davon, und es wird auch nichts gerechnet (nie eine falsche 0)
 */
public record LogbookFacts(Map<LocalDate, Double> values, String unavailable) {

    public static LogbookFacts unavailable(String message) {
        return new LogbookFacts(Map.of(), message);
    }

    public boolean isUnavailable() {
        return unavailable != null;
    }

    public double count(LocalDate day) {
        return values.getOrDefault(day, 0.0);
    }
}
