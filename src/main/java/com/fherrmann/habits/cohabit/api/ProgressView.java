package com.fherrmann.habits.cohabit.api;

/** Wochen-/Monatsfortschritt bzw. Ziel-Prozent: {@code {"done":2,"goal":3,"fraction":0.67}}. */
public record ProgressView(Number done, Number goal, double fraction) {
}
