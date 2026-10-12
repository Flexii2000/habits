package com.fherrmann.habits.cohabit.api;

import java.time.LocalDate;

/**
 * Ein Tag der Nachtragsfrist bei einer Abstinenz mit Tageslimit - zum Korrigieren.
 *
 * @param count     die Zahl im Logbook; {@code null}, wenn nichts eingetragen ist
 * @param countText „2“, „2,5“ oder „–“
 */
public record LimitDayView(LocalDate date, Double count, String countText, boolean over) {
}
