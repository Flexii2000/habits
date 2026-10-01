package com.fherrmann.habits.cohabit.api;

import com.fherrmann.habits.cohabit.model.FocusPeriod;
import com.fherrmann.habits.cohabit.rules.StreakUnit;
import com.fherrmann.habits.legacy.Period;

import java.time.LocalDate;
import java.util.List;

/**
 * Ein Co-Habit in der Form der alten Habits-API ({@code HabitStatus} bis 2026-09-30) -
 * fuer die klassische Liste der iOS-App. Die ersten sechzehn Felder heissen und
 * bedeuten genau das, was die alte App gelesen hat; dazu kommen fuenf, die es erst
 * mit coHabit gibt.
 *
 * <p>Ziele und Challenges kannte die alte App nicht: dort stehen die alten Felder auf
 * neutralen Werten (unit DAYS, streak 0, leere Listen), und {@code summary} traegt,
 * was die neue Liste von ihnen zeigt - Kennzahl, Zeilentext, Fortschritt, Eintragen.
 *
 * @param unit           DAYS, WEEKS, MONTHS - und WINDOWS fuer den Rhythmus "alle n Tage"
 * @param period         bei BUILD der Rhythmus DAY, WEEK oder MONTH; {@code null} bei
 *                       Rhythmen, die das alte Design nicht kennt (Wochentage, Intervall),
 *                       und bei allen anderen Arten
 * @param progress       bei BUILD mit Woche/Monat die Eintraege des laufenden Zeitraums,
 *                       bei "Track food" kcal gegen 80 % des Ziels, bei den Schritten die
 *                       Woche, bei der Fokus-Zeit die Minuten von heute - sonst {@code null}
 * @param recent         die letzten sieben Zeitraeume, aelteste zuerst; leer, wenn die
 *                       Quelle nicht erreichbar war
 * @param markedDays     BUILD: eigene Haken, QUIT: Rueckfaelle - die letzten 31 Tage
 * @param createdAt      ab wann Eintraege zaehlen (Start bzw. eigener Beitritt)
 * @param photoRequired  abhaken nur mit Beweisfoto - das geht nicht aus der Liste heraus
 * @param shared         mehr als ein Mitglied; Loeschen heisst dann Verlassen
 * @param admin          ob die Person die Einstellungen aendern darf
 * @param backfillFrom   der frueheste Tag, den der Dienst noch annimmt
 * @param summary        nur bei GOAL und CHALLENGE: die Zusammenfassung wie in {@code GET /cohabits}
 * @param focus          nur bei FOCUS: Kategorie aus dem Wald ({@code null}: alle Baeume) und Zeitraum
 */
public record ClassicHabit(
        String id,
        String name,
        ClassicKind kind,
        StreakUnit unit,
        Integer weeklyStepGoal,
        int streak,
        boolean doneToday,
        boolean atRisk,
        Progress progress,
        List<Boolean> recent,
        String unavailable,
        Integer focusMinutesGoal,
        Period period,
        Integer timesPerPeriod,
        List<LocalDate> markedDays,
        LocalDate createdAt,
        boolean photoRequired,
        boolean shared,
        boolean admin,
        LocalDate backfillFrom,
        CohabitSummary summary,
        Focus focus) {

    /** Fokus-Habit: nur Baeume dieser Kategorie zaehlen ({@code categoryId} null: alle), je Tag oder Woche. */
    public record Focus(String categoryId, String categoryName, FocusPeriod period) {
    }

    /** Wie weit der laufende Zeitraum ist - wie {@code HabitProgress} der alten App. */
    public record Progress(int value, int goal) {
    }
}
