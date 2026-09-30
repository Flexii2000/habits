package com.fherrmann.habits.cohabit.rules;

import com.fherrmann.habits.cohabit.model.Rhythm;
import com.fherrmann.habits.cohabit.model.RhythmKind;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static com.fherrmann.habits.cohabit.rules.Fixture.TODAY;
import static com.fherrmann.habits.cohabit.rules.Fixture.at;
import static com.fherrmann.habits.cohabit.rules.Fixture.d;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** STREAK: alle Rhythmen, Pausen, "heute darf offen sein", Gruppe, Rekord, Quote. */
class StreakRulesTest {

    private static CohabitEval.MemberEval felix(Fixture f) {
        return f.eval().member("felix");
    }

    @Test
    void taeglichHeuteDarfOffenSein() {
        Fixture f = Fixture.streak(Rhythm.daily(), d(20)).member("felix").done("felix", d(1), d(2));
        var m = felix(f);
        assertEquals(2, m.streak.current());
        assertTrue(m.streak.atRisk());
        assertFalse(m.doneToday);
        f.done("felix", TODAY);
        m = felix(f);
        assertEquals(3, m.streak.current());
        assertFalse(m.streak.atRisk());
        assertEquals(d(2), m.streak.runStart());
    }

    @Test
    void eineLueckeGesternReisst() {
        Fixture f = Fixture.streak(Rhythm.daily(), d(20)).member("felix").done("felix", d(2), d(3));
        assertEquals(0, felix(f).streak.current());
        assertFalse(felix(f).streak.atRisk());
    }

    @Test
    void vorDemBeitrittZaehltNichts() {
        Fixture f = Fixture.streak(Rhythm.daily(), d(20)).member("felix", d(2))
                .done("felix", d(5), d(4), d(3), d(2), d(1), TODAY);
        assertEquals(3, felix(f).streak.current(), "nur ab dem Beitritt vor zwei Tagen");
    }

    @Test
    void wochentageUeberspringenDieAnderenTage() {
        // Heute Mittwoch. Faellig Mo, Mi, Fr.
        Rhythm r = new Rhythm(RhythmKind.WEEKDAYS, List.of(1, 3, 5), null, null);
        Fixture f = Fixture.streak(r, d(30)).member("felix").done("felix", d(2), d(5), d(7));
        var m = felix(f);
        assertEquals(3, m.streak.current(), "Mo 28., Fr 25., Mi 23.");
        assertTrue(m.dueToday);
        assertTrue(m.streak.atRisk());
        f.done("felix", d(1)); // ein Dienstag - zaehlt nicht, bricht nicht
        assertEquals(3, felix(f).streak.current());
        // Ohne Haken am Mittwoch ist die Serie am Donnerstag gerissen ...
        assertEquals(0, f.eval(at(TODAY.plusDays(1), 12)).member("felix").streak.current());
        // ... mit Haken laeuft sie, und der Donnerstag ist frei.
        f.done("felix", TODAY);
        CohabitEval thursday = f.eval(at(TODAY.plusDays(1), 12));
        assertFalse(thursday.member("felix").dueToday);
        assertEquals(4, thursday.member("felix").streak.current());
        assertFalse(thursday.member("felix").streak.atRisk());
    }

    @Test
    void proWocheZaehltWochenUndDieLaufendeDarfOffenSein() {
        Fixture f = Fixture.streak(Rhythm.timesPerWeek(3), d(40)).member("felix")
                .done("felix", d(1), d(2))                   // diese Woche (ab Mo 28.9.): 2 von 3
                .done("felix", d(3), d(5), d(7))             // Vorwoche: 3
                .done("felix", d(10), d(11), d(13));         // Woche davor: 3
        var m = felix(f);
        assertEquals(2, m.streak.current());
        assertTrue(m.streak.atRisk());
        assertEquals(3, m.streak.currentRequired());
        assertEquals(2, m.streak.currentAchieved());
        f.done("felix", TODAY);
        m = felix(f);
        assertEquals(3, m.streak.current());
        assertTrue(m.streak.currentDone());
    }

    @Test
    void pausenVerlangenAnteiligWeniger() {
        // Vorwoche (21.-27.9.) Mo-Do pausiert: ceil(3 x 3/7) = 2 genuegen.
        Fixture f = Fixture.streak(Rhythm.timesPerWeek(3), d(40)).member("felix")
                .pause("felix", d(9), d(6))
                .done("felix", d(4), d(3))
                .done("felix", d(10), d(11), d(13));
        var m = felix(f);
        assertEquals(2, m.streak.current(), "Vorwoche mit 2 von anteilig 2 erfuellt, die davor mit 3");
        assertEquals(2, StreakModel.proRated(3, d(9), d(3), day -> !day.isBefore(d(9)) && !day.isAfter(d(6))));
    }

    @Test
    void ganzPausierteZeitraeumeWerdenUebersprungen() {
        Fixture f = Fixture.streak(Rhythm.timesPerWeek(1), d(60)).member("felix")
                .pause("felix", d(16), d(3))                 // Wochen ab 14.9. und 21.9. ganz pausiert
                .done("felix", d(17), d(24));                // die beiden Wochen davor erfuellt
        var m = felix(f);
        assertEquals(2, m.streak.current());
        assertTrue(m.streak.atRisk(), "diese Woche offen");
        Fixture daily = Fixture.streak(Rhythm.daily(), d(30)).member("felix")
                .pause("felix", d(3), d(1))
                .done("felix", d(4), d(5));
        assertEquals(2, felix(daily).streak.current(), "Pausentage brechen nichts");
        Fixture pausedToday = Fixture.streak(Rhythm.daily(), d(30)).member("felix")
                .pause("felix", TODAY, TODAY.plusDays(3)).done("felix", d(1));
        var p = felix(pausedToday);
        assertTrue(p.pausedToday);
        assertFalse(p.dueToday);
        assertEquals(1, p.streak.current());
        assertFalse(p.streak.atRisk(), "pausiert ist nicht gefaehrdet");
    }

    @Test
    void proMonatMitPausen() {
        // September: 30 Tage, 15 pausiert -> ceil(2 x 15/30) = 1.
        Fixture f = Fixture.streak(Rhythm.timesPerMonth(2), LocalDate.of(2026, 6, 1)).member("felix")
                .pause("felix", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 15))
                .done("felix", LocalDate.of(2026, 9, 20))
                .done("felix", LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 30))
                .done("felix", LocalDate.of(2026, 7, 1));
        var m = felix(f);
        assertTrue(m.streak.currentDone());
        assertEquals(2, m.streak.current(), "September (anteilig 1) und August; Juli nur einmal");
    }

    @Test
    void intervallZaehltFensterAbDemStart() {
        // Start 31.8.: Fenster zu 3 Tagen ... 24.-26.9., 27.-29.9., 30.9.-2.10.
        Fixture f = Fixture.streak(new Rhythm(RhythmKind.INTERVAL, null, null, 3), d(30)).member("felix")
                .done("felix", d(1), d(5), d(6));
        var m = felix(f);
        assertEquals(TODAY, m.streak.currentStart());
        assertEquals(TODAY.plusDays(2), m.streak.currentEnd());
        assertEquals(2, m.streak.current(), "27.-29.9. und 24.-26.9.; 21.-23.9. leer");
        Fixture shifted = Fixture.streak(new Rhythm(RhythmKind.INTERVAL, null, null, 3), d(31)).member("felix");
        assertEquals(d(1), shifted.eval().member("felix").streak.currentStart(), "ein Tag frueher gestartet");
        assertTrue(m.streak.atRisk());
        assertEquals(StreakUnit.WINDOWS, StreakModel.scheme(f.c).unit());
    }

    @Test
    void gruppenStreakHaeltNurWennAlleErfuellen() {
        Fixture f = Fixture.streak(Rhythm.daily(), d(10)).group().member("felix").member("lena")
                .done("felix", d(1), d(2), d(3), TODAY)
                .done("lena", d(1), d(2));
        CohabitEval e = f.eval();
        assertEquals(2, e.groupStreak, "Gestern und vorgestern alle, heute offen");
        f.done("lena", TODAY);
        assertEquals(3, f.eval().groupStreak);
        // Wer spaeter beitritt, zaehlt erst ab seinem Beitritt.
        Fixture late = Fixture.streak(Rhythm.daily(), d(10)).group().member("felix").member("max", d(1))
                .done("felix", d(1), d(2), d(3)).done("max", d(1));
        assertEquals(3, late.eval().groupStreak);
    }

    @Test
    void rekordUndErfuellungsquote() {
        Fixture f = Fixture.streak(Rhythm.daily(), d(9)).member("felix")
                .done("felix", d(9), d(8), d(7), d(6), d(4), d(3), d(1), TODAY);
        var m = felix(f);
        assertEquals(2, m.streak.current());
        assertEquals(4, m.longest);
        // 9 abgeschlossene Tage (d9..d1), davon 7 erfuellt, dazu heute erfuellt: 8/10.
        assertEquals(8, m.rate.fulfilled());
        assertEquals(10, m.rate.due());
        assertEquals(80, m.rate.percent());
    }

    @Test
    void ohneErledigtesHeuteZaehltHeuteNichtZurQuote() {
        Fixture f = Fixture.streak(Rhythm.daily(), TODAY).member("felix");
        var m = felix(f);
        assertEquals(0, m.rate.due());
        assertEquals(0, m.rate.percent());
        assertNull(m.streak.runStart());
    }
}
