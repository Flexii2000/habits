package com.fherrmann.habits.cohabit.rules;

import java.time.LocalDate;

/** Was ein Zeitraum von einer Person verlangt und was sie darin geschafft hat. */
public interface Judge {

    /**
     * Wie viel der Zeitraum verlangt (Tage mit Eintrag, bei den Schritten Schritte).
     * 0 heisst: nicht faellig - der Zeitraum wird uebersprungen, er bricht nichts
     * und zaehlt nichts (Pause, kein gewaehlter Wochentag).
     */
    int required(LocalDate start, LocalDate end);

    /** Wie viel davon geschafft ist. */
    int achieved(LocalDate start, LocalDate end);

    default boolean fulfilled(LocalDate start, LocalDate end) {
        int required = required(start, end);
        return required > 0 && achieved(start, end) >= required;
    }
}
