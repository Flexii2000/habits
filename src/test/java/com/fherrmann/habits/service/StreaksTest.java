package com.fherrmann.habits.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StreaksTest {

    // Ein Mittwoch.
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 2);

    private static Predicate<LocalDate> done(LocalDate... days) {
        return Set.of(days)::contains;
    }

    @Test
    void zaehltZusammenhaengendeTageBisHeute() {
        assertEquals(3, Streaks.daily(TODAY, done(TODAY, TODAY.minusDays(1), TODAY.minusDays(2)), 100, true));
    }

    @Test
    void heuteDarfNochOffenSein() {
        // Gestern und vorgestern erledigt, heute noch nicht: die Straehne lebt.
        assertEquals(2, Streaks.daily(TODAY, done(TODAY.minusDays(1), TODAY.minusDays(2)), 100, true));
    }

    @Test
    void eineLueckeGesternReisstDieStraehne() {
        assertEquals(0, Streaks.daily(TODAY, done(TODAY.minusDays(2), TODAY.minusDays(3)), 100, true));
    }

    @Test
    void heuteAlleinIstEinTag() {
        assertEquals(1, Streaks.daily(TODAY, done(TODAY), 100, true));
    }

    @Test
    void einEntschiedenesHeuteLaesstNichtsWeiterlaufen() {
        // QUIT: Rueckfall heute. Die zehn Tage davor sind vorbei, nicht "offen".
        assertEquals(0, Streaks.daily(TODAY, day -> !day.equals(TODAY), 100, false));
        // Ohne Rueckfall zaehlt heute mit.
        assertEquals(100, Streaks.daily(TODAY, day -> true, 100, false));
    }

    @Test
    void derDeckelGreift() {
        assertEquals(5, Streaks.daily(TODAY, day -> true, 5, true));
    }

    @Test
    void wochenBeginnenMontag() {
        assertEquals(LocalDate.of(2026, 8, 31), Streaks.mondayOf(LocalDate.of(2026, 9, 2)));
        // Ein Sonntag gehoert noch zur Vorwoche ...
        assertEquals(LocalDate.of(2026, 8, 31), Streaks.mondayOf(LocalDate.of(2026, 9, 6)));
        // ... der Montag danach beginnt die neue.
        assertEquals(LocalDate.of(2026, 9, 7), Streaks.mondayOf(LocalDate.of(2026, 9, 7)));
    }

    @Test
    void wochenstraehneLaeuftWeiterSolangeDieLaufendeWocheOffenIst() {
        LocalDate thisMonday = LocalDate.of(2026, 8, 31);
        Predicate<LocalDate> weekDone = done(thisMonday.minusWeeks(1), thisMonday.minusWeeks(2));
        assertEquals(2, Streaks.weekly(TODAY, weekDone, 52));
        // Diese Woche erreicht: zaehlt mit.
        assertEquals(3, Streaks.weekly(TODAY,
                done(thisMonday, thisMonday.minusWeeks(1), thisMonday.minusWeeks(2)), 52));
    }

    @Test
    void dieLetztenSiebenTageAeltesteZuerst() {
        List<Boolean> recent = Streaks.recentDays(TODAY, done(TODAY, TODAY.minusDays(6)), 7);
        assertEquals(List.of(true, false, false, false, false, false, true), recent);
    }
}
