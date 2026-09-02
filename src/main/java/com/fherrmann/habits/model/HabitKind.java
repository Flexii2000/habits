package com.fherrmann.habits.model;

/**
 * Was fuer eine Art Gewohnheit das ist - und damit, wer sie abhakt.
 *
 * <ul>
 *   <li>{@link #BUILD} - etwas, das man tun will. Zaehlt nur, wenn man es
 *       selbst abhakt. Kein Haken = Straehne gerissen (ab Mitternacht).</li>
 *   <li>{@link #QUIT} - etwas, das man lassen will. Zaehlt von selbst; nur ein
 *       eingetragener Rueckfall setzt die Straehne auf null.</li>
 *   <li>{@link #FOOD} - "Track food": gilt als erledigt, wenn der
 *       Kalorienzaehler fuer den Tag genug hergibt (siehe
 *       {@code HabitsService}). Nichts abzuhaken.</li>
 *   <li>{@link #STEPS} - ein Wochenziel an Schritten, gerechnet aus dem
 *       Weight Tracker. Nichts abzuhaken, die Woche beginnt Montag 0:00.</li>
 * </ul>
 */
public enum HabitKind {
    BUILD,
    QUIT,
    FOOD,
    STEPS;

    /** Ob die Quelle woanders liegt und der Nutzer hier nichts abhaken kann. */
    public boolean isAutomatic() {
        return this == FOOD || this == STEPS;
    }

    /** Ob die Straehne in Tagen oder in Wochen gezaehlt wird. */
    public Unit unit() {
        return this == STEPS ? Unit.WEEKS : Unit.DAYS;
    }
}
