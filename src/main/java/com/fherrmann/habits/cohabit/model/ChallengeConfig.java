package com.fherrmann.habits.cohabit.model;

import java.time.LocalDate;

/**
 * Die laufende (oder naechste) Runde: {@code start} bis {@code end}, beide Tage
 * einschliesslich. Nach dem Ende einer wiederkehrenden Challenge stehen hier die
 * Tage der neuen Runde.
 */
public record ChallengeConfig(LocalDate start, LocalDate end, Scoring scoring, Double target, String stake,
                              Recurrence recurrence) {
}
