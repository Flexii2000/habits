package com.fherrmann.habits.cohabit.api;

import java.time.Instant;

/**
 * @param source {@code DEVICE}: die App liest Apple Health bzw. Health Connect und schickt die
 *               Tageswerte; {@code HEALTHY}: der Dienst holt sie selbst aus dem Kalorienzaehler -
 *               zustimmen kann, wer die Quelle FOOD hat ({@code MeView.sources})
 */
public record HealthBlock(String metric, String label, boolean consent, Instant lastSyncAt, String shareText,
                          String source) {
}
