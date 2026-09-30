package com.fherrmann.habits.legacy;

import com.fherrmann.habits.client.FoodClient;
import com.fherrmann.habits.client.SourceUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Die Tests der alten Rechnung - sie halten die Referenz ehrlich. */
class LegacyHabitsServiceTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    // Mittwoch, 2. September 2026, 21:30 in Berlin.
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 2);
    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 31);

    private Function<LocalDate, FoodClient.Day> food = day -> new FoodClient.Day(0, 0, Set.of());
    private Map<LocalDate, Integer> steps = Map.of();
    private Map<LocalDate, Integer> focus = Map.of();
    private LegacyHabitsService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(ZonedDateTime.of(2026, 9, 2, 21, 30, 0, 0, BERLIN).toInstant(), BERLIN);
        service = new LegacyHabitsService(day -> food.apply(day), (from, to) -> steps, (from, to) -> focus,
                clock, 730);
    }

    private HabitStatus statusOf(Habit habit, Mark... marks) {
        return service.status(habit, new HabitsData(List.of(habit), List.of(marks)), TODAY);
    }

    private static Habit habit(HabitKind kind, LocalDate createdAt) {
        return new Habit("h1", "Test", kind, kind == HabitKind.STEPS ? 70_000 : null, createdAt);
    }

    @Test
    void wochenHabitZaehltAbgehakteTageDerWocheGegenDasZiel() {
        Habit h = new Habit("w1", "Zeitungsartikel lesen", HabitKind.BUILD, null, TODAY.minusDays(60),
                null, Period.WEEK, 1);
        HabitStatus s = statusOf(h, new Mark("w1", TODAY.minusDays(9)), new Mark("w1", TODAY.minusDays(15)));
        assertEquals(2, s.streak());
        assertTrue(s.atRisk());
        s = statusOf(h, new Mark("w1", TODAY), new Mark("w1", TODAY.minusDays(9)));
        assertEquals(2, s.streak());
        assertFalse(s.atRisk());
    }

    @Test
    void monatsHabitBrauchtZweiTageImMonat() {
        Habit h = new Habit("m1", "Politisch aktiv", HabitKind.BUILD, null, TODAY.minusDays(120),
                null, Period.MONTH, 2);
        HabitStatus s = statusOf(h,
                new Mark("m1", TODAY.minusDays(1)),
                new Mark("m1", LocalDate.of(2026, 8, 3)), new Mark("m1", LocalDate.of(2026, 8, 20)),
                new Mark("m1", LocalDate.of(2026, 7, 1)), new Mark("m1", LocalDate.of(2026, 7, 31)),
                new Mark("m1", LocalDate.of(2026, 6, 15)));
        assertEquals(2, s.streak(), "August und Juli erfuellt, Juni nicht, September offen");
        assertTrue(s.atRisk());
    }

    @Test
    void fokusZeitZaehltDieSessionsDesTagesGegenDasZiel() {
        Habit h = new Habit("h1", "Fokus", HabitKind.FOCUS, null, TODAY.minusDays(10), 240);
        focus = Map.of(TODAY, 150, TODAY.minusDays(1), 260, TODAY.minusDays(2), 240, TODAY.minusDays(3), 100);
        HabitStatus s = statusOf(h);
        assertEquals(2, s.streak());
        assertTrue(s.atRisk());
    }

    @Test
    void buildZaehltHakenUndIstOhneHakenHeuteGefaehrdet() {
        Habit h = habit(HabitKind.BUILD, TODAY.minusDays(10));
        HabitStatus s = statusOf(h, new Mark("h1", TODAY.minusDays(1)), new Mark("h1", TODAY.minusDays(2)));
        assertEquals(2, s.streak());
        assertTrue(s.atRisk());
    }

    @Test
    void quitZaehltVonSelbstAbDemVorsatzUndEinRueckfallSetztZurueck() {
        assertEquals(5, statusOf(habit(HabitKind.QUIT, TODAY.minusDays(4))).streak());
        Habit h = habit(HabitKind.QUIT, TODAY.minusDays(10));
        assertEquals(2, statusOf(h, new Mark("h1", TODAY.minusDays(2))).streak());
        assertEquals(0, statusOf(h, new Mark("h1", TODAY)).streak());
    }

    @Test
    void trackFoodFragtDenKalorienzaehler() {
        food = day -> day.equals(TODAY.minusDays(1)) || day.equals(TODAY.minusDays(2))
                ? new FoodClient.Day(2000, 2300, Set.of())
                : new FoodClient.Day(500, 2300, Set.of("BREAKFAST"));
        HabitStatus s = statusOf(habit(HabitKind.FOOD, TODAY.minusDays(30)));
        assertEquals(2, s.streak());
        assertTrue(s.atRisk());
        assertNotNull(s.progress());
        assertEquals(1840, s.progress().goal());
    }

    @Test
    void ohneKalorienzaehlerGibtEsEinenHinweisStattEinerNull() {
        food = day -> {
            throw new SourceUnavailableException("Kalorienzähler", 503);
        };
        HabitStatus s = statusOf(habit(HabitKind.FOOD, TODAY));
        assertNotNull(s.unavailable());
        assertNull(s.progress());
    }

    @Test
    void schritteZaehlenWochenAbMontag() {
        Map<LocalDate, Integer> perDay = new HashMap<>();
        for (int i = 0; i < 3; i++) {
            perDay.put(MONDAY.plusDays(i), 20_000);
        }
        for (int i = 0; i < 7; i++) {
            perDay.put(MONDAY.minusWeeks(1).plusDays(i), 10_000);
        }
        perDay.put(MONDAY.minusWeeks(2), 69_999);
        steps = perDay;
        HabitStatus s = statusOf(habit(HabitKind.STEPS, TODAY));
        assertEquals(1, s.streak());
        assertEquals(60_000, s.progress().value());
    }
}
