package com.fherrmann.habits.cohabit.rules;

import com.fherrmann.habits.cohabit.model.AutoConfig;
import com.fherrmann.habits.cohabit.model.AutoSource;
import com.fherrmann.habits.cohabit.model.ChallengeConfig;
import com.fherrmann.habits.cohabit.model.CheckinKind;
import com.fherrmann.habits.cohabit.model.CohabitType;
import com.fherrmann.habits.cohabit.model.GoalConfig;
import com.fherrmann.habits.cohabit.model.GoalCounting;
import com.fherrmann.habits.cohabit.model.GoalMode;
import com.fherrmann.habits.cohabit.model.HealthMetric;
import com.fherrmann.habits.cohabit.model.Recurrence;
import com.fherrmann.habits.cohabit.model.Scoring;
import com.fherrmann.habits.cohabit.model.Tracking;
import com.fherrmann.habits.cohabit.model.TrackingMode;
import com.fherrmann.habits.cohabit.model.ValueUnit;
import com.fherrmann.habits.cohabit.rules.DaySeriesCalc.CheckinFact;
import com.fherrmann.habits.cohabit.rules.DaySeriesCalc.Kind;
import com.fherrmann.habits.cohabit.rules.DaySeriesCalc.Series;
import com.fherrmann.habits.cohabit.rules.DaySeriesCalc.Snapshot;
import com.fherrmann.habits.cohabit.rules.DaySeriesCalc.Span;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ein Co-Habit als Tagesreihe fuer das Logbook - ein fehlender Tag ist "unbekannt", 0 heisst "nicht getan". */
class DaySeriesCalcTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 9);

    private static LocalDate day(int offset) {
        return TODAY.plusDays(offset);
    }

    /** Ein Co-Habit, das vor fuenf Tagen begann; Felder je Test aenderbar. */
    private static final class S {
        CohabitType type = CohabitType.STREAK;
        Tracking tracking = Tracking.CHECK;
        GoalConfig goal;
        ChallengeConfig challenge;
        List<Span> rounds = new ArrayList<>();
        HealthMetric health;
        AutoConfig auto;
        LocalDate start = day(-5);
        LocalDate last = TODAY;
        List<Span> pauses = new ArrayList<>();
        List<CheckinFact> checkins = new ArrayList<>();
        LocalDate lastHealthDay;

        S done(int offset) {
            checkins.add(new CheckinFact(day(offset), CheckinKind.DONE, null, null));
            return this;
        }

        S done(int offset, double value) {
            checkins.add(new CheckinFact(day(offset), CheckinKind.DONE, value, null));
            return this;
        }

        S breakOn(int offset) {
            checkins.add(new CheckinFact(day(offset), CheckinKind.BREAK, null, null));
            return this;
        }

        Snapshot build() {
            return new Snapshot(type, tracking, goal, challenge, rounds, health, auto, start, last, pauses,
                    checkins, lastHealthDay);
        }

        Series series() {
            return series(Map.of());
        }

        Series series(Map<LocalDate, Double> autoValues) {
            return DaySeriesCalc.series(build(), autoValues, day(-10), TODAY);
        }
    }

    private static Map<LocalDate, Double> values(Object... offsetAndValue) {
        Map<LocalDate, Double> m = new LinkedHashMap<>();
        for (int i = 0; i < offsetAndValue.length; i += 2) {
            m.put(day((Integer) offsetAndValue[i]), ((Number) offsetAndValue[i + 1]).doubleValue());
        }
        return m;
    }

    @Test
    void aStreakCountsDoneAsOneAndEveryOtherDayAsZeroFromTheStart() {
        Series s = new S().done(-4).done(-2).series();

        assertEquals(Kind.BINARY, s.kind());
        assertNull(s.unit());
        // Nie vor dem Start: der Zeitraum beginnt bei -10, die Reihe erst bei -5.
        assertEquals(values(-5, 0, -4, 1, -3, 0, -2, 1, -1, 0, 0, 0), s.days());
    }

    @Test
    void pausedDaysAreUnknownNotZero() {
        S cohabit = new S().done(-4);
        cohabit.pauses.add(new Span(day(-3), day(-2)));

        assertEquals(values(-5, 0, -4, 1, -1, 0, 0, 0), cohabit.series().days());
    }

    @Test
    void anArchivedCohabitEndsOnItsDayOfArchiving() {
        S cohabit = new S().done(-4);
        cohabit.last = day(-3);

        assertEquals(values(-5, 0, -4, 1, -3, 0), cohabit.series().days());
    }

    @Test
    void aStreakWithValuesSumsThemAndLeavesADoneWithoutValueOpen() {
        S cohabit = new S().done(-4, 5.5).done(-2);
        cohabit.tracking = new Tracking(TrackingMode.VALUE, ValueUnit.KM);

        Series s = cohabit.series();

        assertEquals(Kind.AMOUNT, s.kind());
        assertEquals(ValueUnit.KM, s.unit());
        // -2: abgehakt, aber ohne Wert - getan, Menge unbekannt.
        assertEquals(values(-5, 0, -4, 5.5, -3, 0, -1, 0, 0, 0), s.days());
    }

    @Test
    void abstinenceIsOneUnlessTheDayHasABreak() {
        S cohabit = new S().breakOn(-3);
        cohabit.type = CohabitType.ABSTINENCE;

        assertEquals(values(-5, 1, -4, 1, -3, 0, -2, 1, -1, 1, 0, 1), cohabit.series().days());
    }

    @Test
    void aGoalCountsOnlyInsideItsPeriod() {
        S cohabit = new S().done(-4, 3).done(-2, 2).done(-2, 1);
        cohabit.type = CohabitType.GOAL;
        cohabit.tracking = new Tracking(TrackingMode.VALUE, ValueUnit.COUNT);
        cohabit.goal = new GoalConfig(100.0, day(-4), day(-2), GoalCounting.AMOUNT, GoalMode.INDIVIDUAL);

        Series amounts = cohabit.series();
        assertEquals(Kind.AMOUNT, amounts.kind());
        assertEquals(values(-4, 3, -3, 0, -2, 3), amounts.days());

        cohabit.goal = new GoalConfig(10.0, day(-4), day(-2), GoalCounting.ENTRIES, GoalMode.TEAM);
        Series entries = cohabit.series();
        assertEquals(Kind.BINARY, entries.kind());
        assertEquals(values(-4, 1, -3, 0, -2, 1), entries.days());
    }

    @Test
    void aChallengeCountsOnlyInItsRoundsAndRunPointsInKilometres() {
        S cohabit = new S();
        cohabit.type = CohabitType.CHALLENGE;
        cohabit.challenge = new ChallengeConfig(day(-1), day(5), Scoring.RUN_POINTS, null, null,
                Recurrence.WEEKLY, null);
        cohabit.rounds.add(new Span(day(-5), day(-4)));
        cohabit.rounds.add(new Span(day(-1), day(5)));
        cohabit.checkins.add(new CheckinFact(day(-4), CheckinKind.DONE, 12.0, 6.2));
        cohabit.checkins.add(new CheckinFact(day(-1), CheckinKind.DONE, 15.0, 7.0));

        Series s = cohabit.series();

        assertEquals(Kind.AMOUNT, s.kind());
        assertEquals(ValueUnit.KM, s.unit());
        // Zwischen den Runden (-3, -2) galt die Challenge nicht.
        assertEquals(values(-5, 0, -4, 6.2, -1, 7.0, 0, 0), s.days());

        cohabit.challenge = new ChallengeConfig(day(-1), day(5), Scoring.MOST_ENTRIES, null, null,
                Recurrence.WEEKLY, null);
        assertEquals(values(-5, 0, -4, 1, -1, 1, 0, 0), cohabit.series().days());
    }

    @Test
    void entriesWithoutAValueCountLikeCoHabitDoes() {
        // "Wer zuerst ..." darf ohne Wert gefuehrt werden: jeder Eintrag zaehlt 1.
        S challenge = new S().done(-4).done(-4).done(-2);
        challenge.type = CohabitType.CHALLENGE;
        challenge.challenge = new ChallengeConfig(day(-5), day(5), Scoring.FIRST_TO_TARGET, 10.0, null,
                Recurrence.NONE, null);
        challenge.rounds.add(new Span(day(-5), day(5)));
        assertEquals(Kind.AMOUNT, challenge.series().kind());
        assertEquals(values(-5, 0, -4, 2, -3, 0, -2, 1, -1, 0, 0, 0), challenge.series().days());

        // Ein Ziel nach Menge zaehlt einen Eintrag ohne Wert mit 0 - wie CohabitEval.contribution.
        S goal = new S().done(-3).done(-3, 4);
        goal.type = CohabitType.GOAL;
        goal.goal = new GoalConfig(100.0, day(-5), day(5), GoalCounting.AMOUNT, GoalMode.INDIVIDUAL);
        assertEquals(4.0, goal.series().days().get(day(-3)));
    }

    @Test
    void healthValuesFillZerosBetweenTheFirstEntryAndTheLastSync() {
        S cohabit = new S().done(-4, 3).done(-2, 1);
        cohabit.health = HealthMetric.WORKOUTS;
        cohabit.tracking = new Tracking(TrackingMode.VALUE, ValueUnit.COUNT);
        cohabit.lastHealthDay = day(-1);

        Series s = cohabit.series();

        assertEquals(Kind.AMOUNT, s.kind());
        assertEquals(ValueUnit.COUNT, s.unit());
        // Vor dem ersten Eintrag und nach dem letzten Abgleich weiss niemand etwas.
        assertEquals(values(-4, 3, -3, 0, -2, 1, -1, 0), s.days());
    }

    @Test
    void kcalFromHealthyLeavesUntrackedDaysOut() {
        S cohabit = new S().done(-4, 2100).done(-2, 1900);
        cohabit.health = HealthMetric.KCAL;
        cohabit.lastHealthDay = TODAY;

        Series s = cohabit.series();

        assertEquals(ValueUnit.KCAL, s.unit());
        assertEquals(values(-4, 2100, -2, 1900), s.days());
    }

    @Test
    void automaticSourcesBringTheirOwnValuesButStillRespectStartAndPauses() {
        S cohabit = new S();
        cohabit.auto = new AutoConfig(AutoSource.FOCUS, null, 240);
        cohabit.pauses.add(new Span(day(-2), day(-2)));

        Series s = cohabit.series(values(-7, 50, -5, 120, -2, 30, -1, 0));

        assertEquals(Kind.AMOUNT, s.kind());
        assertEquals(ValueUnit.MINUTES, s.unit());
        assertEquals(values(-5, 120, -1, 0), s.days());
    }

    @Test
    void kindsAndUnitsOfTheAutomaticSources() {
        S food = new S();
        food.auto = new AutoConfig(AutoSource.FOOD, null, null);
        assertEquals(Kind.BINARY, DaySeriesCalc.kind(food.build()));

        S target = new S();
        target.auto = new AutoConfig(AutoSource.FOOD_TARGET_WEEKLY, null, null);
        assertEquals(Kind.BINARY, DaySeriesCalc.kind(target.build()));

        S steps = new S();
        steps.auto = new AutoConfig(AutoSource.STEPS_WEEKLY, 70000, null);
        assertEquals(Kind.AMOUNT, DaySeriesCalc.kind(steps.build()));
        assertEquals(ValueUnit.STEPS, DaySeriesCalc.unit(steps.build()));
    }

    @Test
    void aCohabitThatStartsAfterTheRangeHasNoDays() {
        S cohabit = new S();
        cohabit.start = TODAY.plusDays(1);

        assertTrue(cohabit.series().days().isEmpty());
    }
}
