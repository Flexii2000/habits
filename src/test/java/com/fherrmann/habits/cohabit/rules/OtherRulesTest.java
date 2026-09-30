package com.fherrmann.habits.cohabit.rules;

import com.fherrmann.habits.cohabit.model.GoalCounting;
import com.fherrmann.habits.cohabit.model.GoalMode;
import com.fherrmann.habits.cohabit.model.Scoring;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static com.fherrmann.habits.cohabit.rules.Fixture.TODAY;
import static com.fherrmann.habits.cohabit.rules.Fixture.at;
import static com.fherrmann.habits.cohabit.rules.Fixture.d;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ABSTINENCE, GOAL und CHALLENGE. */
class OtherRulesTest {

    @Test
    void abstinenzZaehltVonSelbstUndHeuteMit() {
        Fixture f = Fixture.abstinence(d(10), false).member("felix");
        assertEquals(11, f.eval().member("felix").abstinence.current());
        f.breaks("felix", d(3));
        var o = f.eval().member("felix").abstinence;
        assertEquals(3, o.current(), "die drei Tage nach der Unterbrechung");
        assertEquals(7, o.record());
        assertEquals(7, o.priorRecord());
        f.breaks("felix", TODAY);
        o = f.eval().member("felix").abstinence;
        assertEquals(0, o.current(), "eine Unterbrechung heute ist entschieden");
        assertTrue(o.breakToday());
    }

    @Test
    void abstinenzSerienMitMonatsnamen() {
        Fixture f = Fixture.abstinence(LocalDate.of(2026, 5, 1), false).member("felix")
                .breaks("felix", LocalDate.of(2026, 5, 13), LocalDate.of(2026, 7, 20), LocalDate.of(2026, 7, 21));
        var o = f.eval().member("felix").abstinence;
        List<AbstinenceCalc.Series> s = o.series();
        assertEquals(3, s.size(), "12 Tage bis Mai, 67 bis Juli, die laufende; der Tag zwischen zwei Unterbrechungen fehlt");
        assertEquals(12, s.get(0).days());
        assertEquals(LocalDate.of(2026, 5, 12), s.get(0).end());
        assertEquals(67, s.get(1).days());
        assertTrue(s.get(2).current());
        assertEquals(71, s.get(2).days());
        assertEquals(71, o.record());
    }

    @Test
    void gruppenmodusZaehltSeitDerLetztenUnterbrechungIrgendwem() {
        Fixture f = Fixture.abstinence(d(20), true).member("felix").member("sara")
                .breaks("felix", d(12)).breaks("sara", d(5));
        CohabitEval e = f.eval();
        assertEquals(5, e.groupDays);
        assertEquals(12, e.member("felix").abstinence.current());
    }

    @Test
    void zielTeamSollIstUndProzent() {
        // 1.9. bis 30.9. = 30 Tage, heute der 30. - alles verstrichen.
        Fixture f = Fixture.goal(100_000, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 30), GoalCounting.AMOUNT,
                GoalMode.TEAM).member("felix").member("lena")
                .value("felix", d(1), 24_800, at(d(1), 9))
                .value("lena", d(2), 43_600.5, at(d(2), 9))
                .value("lena", LocalDate.of(2026, 8, 31), 99_999, at(d(2), 9));
        CohabitEval e = f.eval();
        assertEquals(68_400.5, e.goalTotal, 0.001, "der Eintrag vor dem Start zaehlt nicht");
        assertEquals(30.0 / 60, e.goalElapsedShare(), 1e-9);
        // floor(68,4005 %)
        assertEquals(68, com.fherrmann.habits.cohabit.service.ViewsAccess.goalPercent(e.goalTotal, 100_000));
        assertEquals(999, com.fherrmann.habits.cohabit.service.ViewsAccess.goalPercent(50_000, 1));
    }

    @Test
    void zielNachEintraegenZaehltJedenEintragEinmal() {
        Fixture f = Fixture.goal(10, d(5), TODAY.plusDays(5), GoalCounting.ENTRIES, GoalMode.INDIVIDUAL)
                .member("felix").value("felix", d(1), 5.2, at(d(1), 8)).value("felix", TODAY, 1, at(TODAY, 8));
        var m = f.eval().member("felix");
        assertEquals(2, m.amount, 1e-9);
        assertTrue(m.entryToday);
    }

    @Test
    void challengeGleichstandGibtGleichenRang() {
        Fixture f = Fixture.challenge(Scoring.MOST_ENTRIES, null, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))
                .member("lena").member("felix").member("max").member("sara")
                .done("lena", d(1), d(2), d(3)).done("felix", d(4), d(5), d(6)).done("max", d(1));
        CohabitEval e = f.eval();
        assertEquals(CohabitEval.ChallengePhase.RUNNING, e.phase);
        List<CohabitEval.Place> board = e.leaderboard;
        assertEquals(1, board.get(0).rank());
        assertEquals(1, board.get(1).rank());
        assertEquals(3, board.get(2).rank());
        assertEquals("max", board.get(2).personId());
        assertEquals(4, board.get(3).rank());
        assertEquals(0, board.get(3).score(), 1e-9);
    }

    @Test
    void hoechsteSummeUndWerZuerstDasZielErreicht() {
        LocalDate start = LocalDate.of(2026, 9, 1);
        Fixture sum = Fixture.challenge(Scoring.HIGHEST_SUM, null, start, LocalDate.of(2026, 9, 30))
                .member("lena").member("felix")
                .value("lena", d(3), 5.5, at(d(3), 8)).value("lena", d(2), 4.5, at(d(2), 8))
                .value("felix", d(1), 12, at(d(1), 8));
        assertEquals("felix", sum.eval().leaderboard.getFirst().personId());
        assertEquals(12, sum.eval().leaderboard.getFirst().score(), 1e-9);

        // Lena hat mehr, aber Felix war zuerst bei 20.
        Fixture first = Fixture.challenge(Scoring.FIRST_TO_TARGET, 20.0, start, LocalDate.of(2026, 9, 30))
                .member("lena").member("felix")
                .value("felix", d(5), 20, at(d(5), 8))
                .value("lena", d(4), 25, at(d(4), 8));
        List<CohabitEval.Place> board = first.eval().leaderboard;
        assertEquals("felix", board.get(0).personId());
        assertNotNull(board.get(0).reachedAt());
        assertEquals(2, board.get(1).rank());
        // Nachgetragen zaehlt der Zeitpunkt des Eintrags, nicht der Tag.
        Fixture late = Fixture.challenge(Scoring.FIRST_TO_TARGET, 10.0, start, LocalDate.of(2026, 9, 30))
                .member("lena").member("felix")
                .value("felix", d(5), 10, at(TODAY, 9))
                .value("lena", d(1), 10, at(d(1), 9));
        assertEquals("lena", late.eval().leaderboard.getFirst().personId());
    }

    @Test
    void challengeVorDemStartUndNachDemEnde() {
        Fixture upcoming = Fixture.challenge(Scoring.MOST_ENTRIES, null, TODAY.plusDays(3), TODAY.plusDays(10))
                .member("felix");
        assertEquals(CohabitEval.ChallengePhase.UPCOMING, upcoming.eval().phase);
        Fixture over = Fixture.challenge(Scoring.MOST_ENTRIES, null, d(10), d(1)).member("felix");
        assertEquals(CohabitEval.ChallengePhase.FINISHED, over.eval().phase);
        assertEquals(at(TODAY, 0).minusSeconds(1).plusSeconds(0), over.eval().challengeEndsAt().plusSeconds(0));
        assertNull(over.eval().member("felix").abstinence);
    }
}
