package com.fherrmann.habits.cohabit.api;

/** Ein Lauf einer Lauf-Challenge: was eingetragen wurde und was es brachte. */
public record RunView(int durationMinutes, Number distanceKm, String paceText, int points, String pointsText,
                      String breakdownText) {
}
