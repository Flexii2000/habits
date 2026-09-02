package com.fherrmann.habits.dto;

/**
 * Wie weit der laufende Zeitraum ist - bei den Schritten die Woche
 * ("55.432 von 70.000"), bei "Track food" der Tag (kcal gegen die 80 %).
 */
public record Progress(int value, int goal) {
}
