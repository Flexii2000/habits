package com.fherrmann.habits.cohabit.api;

import java.time.LocalDate;

/**
 * Heute bei einer Abstinenz mit Tageslimit: die Zahl aus dem Logbook gegen das Limit.
 *
 * @param date     der Tag, fuer den gezaehlt wird (heute in Berlin) - ein „+“ zaehlt auf
 *                 diesen Tag, auch wenn die Ansicht ueber Mitternacht offen war
 * @param count    heute bisher; {@code null}, wenn das Logbook nicht antwortet
 * @param perDay   hoechstens so viele am Tag
 * @param unit     die Einheit des Verhaltens („Tassen“)
 * @param text     „heute 2 von 4 Tassen“; {@code null} ohne Logbook
 * @param over     heute schon ueber dem Limit
 * @param canCount ob „+“/„−“ gehen (Mitglied, nicht archiviert, Logbook erreichbar)
 */
public record LimitView(LocalDate date, Double count, int perDay, String unit, String text, boolean over,
                        boolean canCount) {
}
