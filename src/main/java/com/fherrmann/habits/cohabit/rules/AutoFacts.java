package com.fherrmann.habits.cohabit.rules;

import com.fherrmann.habits.cohabit.model.AutoSource;

import java.time.LocalDate;
import java.util.Map;

/**
 * Was eine automatische Quelle fuer eine Person hergibt - einmal geholt, bevor
 * gerechnet wird. So laeuft die Rechnung ohne Netz und ohne Lock-Wartezeit.
 *
 * @param doneDays    FOOD/FOCUS: Tage, fuer die die Quelle gefragt wurde, und ob sie erfuellt sind
 * @param amounts     FOCUS: Minuten je Tag; STEPS_WEEKLY: Schritte je Tag
 * @param todayValue  FOOD: kcal heute; FOCUS: Minuten heute
 * @param todayGoal   FOOD: 80 % des kcal-Ziels; FOCUS: Tagesziel
 * @param unavailable gesetzt, wenn die Quelle nicht antwortete - dann gilt nichts davon
 */
public record AutoFacts(AutoSource source, Map<LocalDate, Boolean> doneDays, Map<LocalDate, Integer> amounts,
                        int todayValue, int todayGoal, String unavailable) {

    public static AutoFacts unavailable(AutoSource source, String message) {
        return new AutoFacts(source, Map.of(), Map.of(), 0, 0, message);
    }

    public boolean isUnavailable() {
        return unavailable != null;
    }

    public boolean done(LocalDate day) {
        return Boolean.TRUE.equals(doneDays.get(day));
    }

    public int amount(LocalDate day) {
        return amounts.getOrDefault(day, 0);
    }
}
