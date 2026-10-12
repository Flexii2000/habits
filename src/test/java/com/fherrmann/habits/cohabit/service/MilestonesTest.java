package com.fherrmann.habits.cohabit.service;

import com.fherrmann.habits.cohabit.model.AutoConfig;
import com.fherrmann.habits.cohabit.model.AutoSource;
import com.fherrmann.habits.cohabit.model.Cohabit;
import com.fherrmann.habits.cohabit.model.CohabitType;
import com.fherrmann.habits.cohabit.model.FocusPeriod;
import com.fherrmann.habits.cohabit.model.Member;
import com.fherrmann.habits.cohabit.model.Rhythm;
import com.fherrmann.habits.cohabit.model.StreakConfig;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wann eine Bestserie nicht mehr gilt: nur wenn sich die Regel der Serie aendert. */
class MilestonesTest {

    private static Cohabit streak(Rhythm rhythm) {
        Cohabit c = new Cohabit();
        c.type = CohabitType.STREAK;
        c.startDate = LocalDate.of(2026, 9, 1);
        c.streak = new StreakConfig(rhythm, false);
        return c;
    }

    @Test
    void theRhythmIsPartOfTheRule() {
        Cohabit c = streak(Rhythm.daily());
        List<Object> daily = Milestones.seriesRule(c);
        c.streak = new StreakConfig(Rhythm.timesPerWeek(4), false);
        assertNotEquals(daily, Milestones.seriesRule(c));
        List<Object> four = Milestones.seriesRule(c);
        c.streak = new StreakConfig(Rhythm.timesPerWeek(3), false);
        assertNotEquals(four, Milestones.seriesRule(c), "auch 4 -> 3 je Woche misst etwas anderes");
    }

    @Test
    void nameColourAndGroupStreakAreNot() {
        Cohabit c = streak(Rhythm.daily());
        List<Object> before = Milestones.seriesRule(c);
        c.name = "Gewohnheit B";
        c.color = "mint";
        c.streak = new StreakConfig(Rhythm.daily(), true);
        assertEquals(before, Milestones.seriesRule(c));
    }

    @Test
    void forASourceTheGoalCountsButNotTheCategoryName() {
        Cohabit c = streak(null);
        c.auto = new AutoConfig(AutoSource.FOCUS, null, 120, "cat-1", "Uni", FocusPeriod.DAY);
        List<Object> before = Milestones.seriesRule(c);
        c.auto = new AutoConfig(AutoSource.FOCUS, null, 120, "cat-1", "Studium", FocusPeriod.DAY);
        assertEquals(before, Milestones.seriesRule(c), "eine umbenannte Kategorie wertet gleich");
        c.auto = new AutoConfig(AutoSource.FOCUS, null, 180, "cat-1", "Studium", FocusPeriod.DAY);
        assertNotEquals(before, Milestones.seriesRule(c));
        List<Object> perDay = Milestones.seriesRule(c);
        c.auto = new AutoConfig(AutoSource.FOCUS, null, 180, "cat-1", "Studium", FocusPeriod.WEEK);
        assertNotEquals(perDay, Milestones.seriesRule(c), "Tag -> Woche");
    }

    @Test
    void otherTypesHaveNoRule() {
        Cohabit c = new Cohabit();
        c.type = CohabitType.ABSTINENCE;
        assertEquals(List.of(), Milestones.seriesRule(c));
    }

    /** Mit Quelle fehlen die Werte im Moment des Umstellens: still nachholen, sobald sie da sind. */
    @Test
    void aSourceIsBaselinedLater() {
        Cohabit c = streak(null);
        c.auto = new AutoConfig(AutoSource.STEPS_WEEKLY, 70000, null);
        Member m = new Member();
        m.personId = "felix";
        m.bestStreak = 50;
        m.bestAnnouncedRun = LocalDate.of(2026, 1, 5);
        m.milestones.put("2026-01-05", List.of(4, 12, 26));
        c.members.add(m);

        Milestones.resetSeries(c, List.of(), LocalDate.of(2026, 10, 12));

        assertEquals(0, m.bestStreak);
        assertNull(m.bestAnnouncedRun);
        assertTrue(m.milestones.isEmpty());
        assertTrue(m.baselinePending);
    }
}
