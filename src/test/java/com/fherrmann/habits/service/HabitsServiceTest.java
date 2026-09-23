package com.fherrmann.habits.service;

import com.fherrmann.habits.client.FoodClient;
import com.fherrmann.habits.client.SourceUnavailableException;
import com.fherrmann.habits.client.StepsClient;
import com.fherrmann.habits.dto.HabitRequest;
import com.fherrmann.habits.dto.HabitStatus;
import com.fherrmann.habits.model.Habit;
import com.fherrmann.habits.model.HabitKind;
import com.fherrmann.habits.model.Period;
import com.fherrmann.habits.model.Unit;
import com.fherrmann.habits.model.HabitsData;
import com.fherrmann.habits.model.Mark;
import com.fherrmann.habits.repository.HabitsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HabitsServiceTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    // Mittwoch, 2. September 2026, 21:30 in Berlin - in UTC noch derselbe Tag.
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 2);
    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 31);

    private HabitsRepository repository;
    private FocusService focus;
    private FoodClient food;
    private StepsClient steps;
    private HabitsService service;
    private HabitsData data;

    @BeforeEach
    void setUp() {
        repository = mock(HabitsRepository.class);
        focus = mock(FocusService.class);
        food = mock(FoodClient.class);
        steps = mock(StepsClient.class);
        Clock clock = Clock.fixed(
                ZonedDateTime.of(2026, 9, 2, 21, 30, 0, 0, BERLIN).toInstant(), BERLIN);
        service = new HabitsService(repository, focus, food, steps, clock, 730);
        data = HabitsData.empty();
        when(repository.load()).thenAnswer(inv -> data);
        when(focus.minutesPerDay(any(), any())).thenReturn(Map.of());
    }

    // MARK: - BUILD je Woche / je Monat

    @Test
    void wochenHabitZaehltAbgehakteTageDerWocheGegenDasZiel() {
        // Mittwoch, 2. September 2026 - die Woche begann Montag, den 31. August.
        Habit h = new Habit("w1", "Zeitungsartikel lesen", HabitKind.BUILD, null, TODAY.minusDays(60),
                null, Period.WEEK, 1);
        // statusOf setzt die Daten selbst - die Haken gehen als Argumente mit.
        HabitStatus s = statusOf(h,
                new Mark("w1", TODAY.minusDays(9)),    // Vorwoche (Montag 24.8.)
                new Mark("w1", TODAY.minusDays(15)));  // Woche davor (Montag 17.8.)
        assertEquals(Period.WEEK, s.period());
        assertEquals(Unit.WEEKS, s.unit());
        assertEquals(1, s.timesPerPeriod());
        assertEquals(0, s.progress().value());
        assertFalse(s.doneToday());
        assertEquals(2, s.streak(), "zwei Wochen erfuellt, die laufende noch offen");
        assertTrue(s.atRisk());
        assertEquals(List.of(false, false, false, false, true, true, false), s.recent());

        s = statusOf(h, new Mark("w1", TODAY), new Mark("w1", TODAY.minusDays(9)));
        assertTrue(s.doneToday());
        assertEquals(1, s.progress().value());
        assertEquals(2, s.streak(), "laufende Woche erfuellt, plus die davor");
        assertFalse(s.atRisk());
    }

    @Test
    void monatsHabitBrauchtZweiTageImMonat() {
        Habit h = new Habit("m1", "Politisch aktiv", HabitKind.BUILD, null, TODAY.minusDays(120),
                null, Period.MONTH, 2);
        // September: ein Tag; August: zwei Tage; Juli: zwei Tage; Juni: einer.
        HabitStatus s = statusOf(h,
                new Mark("m1", TODAY.minusDays(1)),
                new Mark("m1", LocalDate.of(2026, 8, 3)), new Mark("m1", LocalDate.of(2026, 8, 20)),
                new Mark("m1", LocalDate.of(2026, 7, 1)), new Mark("m1", LocalDate.of(2026, 7, 31)),
                new Mark("m1", LocalDate.of(2026, 6, 15)));
        assertEquals(Unit.MONTHS, s.unit());
        assertEquals(1, s.progress().value());
        assertEquals(2, s.progress().goal());
        assertEquals(2, s.streak(), "August und Juli erfuellt, Juni nicht, September offen");
        assertTrue(s.atRisk());
        assertEquals(List.of(false, false, false, false, true, true, false), s.recent());
    }

    @Test
    void rhythmusNurBeimAufbauenUndImRahmen() {
        data = HabitsData.empty();
        HabitStatus created = service.create(new HabitRequest("Lesen", HabitKind.BUILD, null, null, Period.WEEK, 1));
        assertEquals(Period.WEEK, created.period());
        assertEquals(Unit.WEEKS, created.unit());
        assertThrows(ResponseStatusException.class,
                () -> service.create(new HabitRequest("x", HabitKind.BUILD, null, null, Period.WEEK, 8)));
        assertThrows(ResponseStatusException.class,
                () -> service.create(new HabitRequest("x", HabitKind.QUIT, null, null, Period.MONTH, 1)));
        // Taeglich bleibt taeglich, auch wenn die App den Rhythmus mitschickt.
        HabitStatus daily = service.create(new HabitRequest("y", HabitKind.BUILD, null, null, Period.DAY, 1));
        assertEquals(Period.DAY, daily.period());
        assertEquals(Unit.DAYS, daily.unit());
    }

    // MARK: - FOCUS

    @Test
    void fokusZeitZaehltDieSessionsDesTagesGegenDasZiel() {
        Habit h = new Habit("h1", "Fokus", HabitKind.FOCUS, null, TODAY.minusDays(10), 240);
        when(focus.minutesPerDay(any(), any())).thenReturn(Map.of(
                TODAY, 150,
                TODAY.minusDays(1), 260,
                TODAY.minusDays(2), 240,
                TODAY.minusDays(3), 100));
        HabitStatus s = statusOf(h);
        assertEquals(240, s.focusMinutesGoal());
        assertEquals(150, s.progress().value());
        assertEquals(240, s.progress().goal());
        assertFalse(s.doneToday(), "150 von 240 Minuten - noch nicht");
        assertEquals(2, s.streak(), "gestern und vorgestern erreicht, heute noch offen");
        assertTrue(s.atRisk());
        assertEquals(List.of(false, false, false, false, true, true, false), s.recent());

        when(focus.minutesPerDay(any(), any())).thenReturn(Map.of(TODAY, 250, TODAY.minusDays(1), 260));
        s = statusOf(h);
        assertTrue(s.doneToday());
        assertEquals(2, s.streak());
        assertFalse(s.atRisk());
    }

    @Test
    void fokusZeitBekommtOhneAngabeVierStundenUndLaesstSichNichtAbhaken() {
        data = HabitsData.empty();
        HabitStatus created = service.create(new HabitRequest("Fokus", HabitKind.FOCUS, null, null));
        assertEquals(240, created.focusMinutesGoal());
        assertEquals(HabitKind.FOCUS, created.kind());
        assertNotNull(created.progress());
        assertThrows(ResponseStatusException.class,
                () -> service.create(new HabitRequest("Fokus", HabitKind.FOCUS, null, 0)));
        assertThrows(ResponseStatusException.class,
                () -> service.create(new HabitRequest("Fokus", HabitKind.FOCUS, null, 2000)));
    }

    private Habit habit(HabitKind kind, LocalDate createdAt) {
        return new Habit("h1", "Test", kind, kind == HabitKind.STEPS ? 70_000 : null, createdAt);
    }

    private HabitStatus statusOf(Habit habit, Mark... marks) {
        data = new HabitsData(List.of(habit), List.of(marks));
        return service.status(habit, data, TODAY);
    }

    // MARK: - BUILD

    @Test
    void buildZaehltHakenUndIstOhneHakenHeuteGefaehrdet() {
        Habit h = habit(HabitKind.BUILD, TODAY.minusDays(10));
        HabitStatus s = statusOf(h, new Mark("h1", TODAY.minusDays(1)), new Mark("h1", TODAY.minusDays(2)));
        assertEquals(2, s.streak());
        assertFalse(s.doneToday());
        assertTrue(s.atRisk(), "gestern erledigt, heute offen: gefaehrdet, nicht gerissen");

        s = statusOf(h, new Mark("h1", TODAY), new Mark("h1", TODAY.minusDays(1)));
        assertEquals(2, s.streak());
        assertTrue(s.doneToday());
        assertFalse(s.atRisk());
    }

    @Test
    void abhakenUndWiederLoesen() {
        Habit h = habit(HabitKind.BUILD, TODAY.minusDays(10));
        data = new HabitsData(List.of(h), List.of());
        HabitStatus s = service.mark("h1", null);
        assertTrue(s.doneToday());
        assertEquals(1, s.streak());

        // Der Service speichert den neuen Stand - fuer den naechsten Aufruf
        // simulieren wir das, indem wir load() den Haken liefern lassen.
        data = new HabitsData(List.of(h), List.of(new Mark("h1", TODAY)));
        s = service.unmark("h1", TODAY);
        assertFalse(s.doneToday());
        assertEquals(0, s.streak());
    }

    @Test
    void hakenVorDemAnlegenUndInDerZukunftSindAbgelehnt() {
        Habit h = habit(HabitKind.BUILD, TODAY.minusDays(1));
        data = new HabitsData(List.of(h), List.of());
        assertThrows(ResponseStatusException.class, () -> service.mark("h1", TODAY.plusDays(1)));
        assertThrows(ResponseStatusException.class, () -> service.mark("h1", TODAY.minusDays(2)));
    }

    // MARK: - QUIT

    @Test
    void quitZaehltVonSelbstAbDemVorsatz() {
        Habit h = habit(HabitKind.QUIT, TODAY.minusDays(4));
        HabitStatus s = statusOf(h);
        // Vorsatz vor vier Tagen, heute eingeschlossen: fuenf Tage.
        assertEquals(5, s.streak());
        assertTrue(s.doneToday());
        assertFalse(s.atRisk(), "ein QUIT-Habit ist nie 'noch offen'");
    }

    @Test
    void einRueckfallSetztDieStraehneZurueck() {
        Habit h = habit(HabitKind.QUIT, TODAY.minusDays(10));
        HabitStatus s = statusOf(h, new Mark("h1", TODAY.minusDays(2)));
        assertEquals(2, s.streak(), "gestern und heute seit dem Rueckfall");

        s = statusOf(h, new Mark("h1", TODAY));
        assertEquals(0, s.streak());
        assertFalse(s.doneToday());
    }

    @Test
    void automatischeHabitsLassenSichNichtAbhaken() {
        Habit h = habit(HabitKind.STEPS, TODAY);
        data = new HabitsData(List.of(h), List.of());
        assertThrows(ResponseStatusException.class, () -> service.mark("h1", null));
    }

    // MARK: - FOOD

    @Test
    void trackFoodGiltMitAchtzigProzentOderDreiMahlzeiten() {
        assertTrue(HabitsService.isFoodDone(new FoodClient.Day(1840, 2300, Set.of())));
        assertFalse(HabitsService.isFoodDone(new FoodClient.Day(1839, 2300, Set.of("BREAKFAST"))));
        assertTrue(HabitsService.isFoodDone(
                new FoodClient.Day(900, 2300, Set.of("BREAKFAST", "LUNCH", "DINNER"))));
        assertFalse(HabitsService.isFoodDone(
                new FoodClient.Day(900, 2300, Set.of("BREAKFAST", "LUNCH", "SNACK"))),
                "ein Snack ersetzt kein Abendessen");
        assertFalse(HabitsService.isFoodDone(new FoodClient.Day(0, 0, Set.of())),
                "ohne Ziel gibt es keine 80 %");
    }

    @Test
    void trackFoodFragtDenKalorienzaehlerUndZeigtDenFortschritt() {
        Habit h = habit(HabitKind.FOOD, TODAY.minusDays(30));
        when(food.day(any())).thenReturn(new FoodClient.Day(500, 2300, Set.of("BREAKFAST")));
        when(food.day(eq(TODAY.minusDays(1)))).thenReturn(new FoodClient.Day(2000, 2300, Set.of()));
        when(food.day(eq(TODAY.minusDays(2)))).thenReturn(new FoodClient.Day(2000, 2300, Set.of()));

        HabitStatus s = statusOf(h);
        assertEquals(2, s.streak());
        assertFalse(s.doneToday());
        assertTrue(s.atRisk());
        assertNotNull(s.progress());
        assertEquals(500, s.progress().value());
        assertEquals(1840, s.progress().goal());
    }

    @Test
    void ohneKalorienzaehlerGibtEsEinenHinweisStattEinerNull() {
        Habit h = habit(HabitKind.FOOD, TODAY);
        when(food.day(any())).thenThrow(new SourceUnavailableException("Kalorienzähler", 503));
        HabitStatus s = statusOf(h);
        assertNotNull(s.unavailable());
        assertNull(s.progress());
    }

    // MARK: - STEPS

    @Test
    void schritteZaehlenWochenAbMontagUndZeigenDenStand() {
        Habit h = habit(HabitKind.STEPS, TODAY);
        Map<LocalDate, Integer> perDay = new java.util.HashMap<>();
        // Diese Woche: Mo-Mi je 20.000 = 60.000, noch nicht am Ziel.
        for (int i = 0; i < 3; i++) perDay.put(MONDAY.plusDays(i), 20_000);
        // Vorwoche komplett: 7 x 10.000 = 70.000, genau am Ziel.
        for (int i = 0; i < 7; i++) perDay.put(MONDAY.minusWeeks(1).plusDays(i), 10_000);
        // Die Woche davor: nur 69.999 - knapp daneben ist daneben.
        perDay.put(MONDAY.minusWeeks(2), 69_999);
        when(steps.steps(any(), any())).thenReturn(perDay);

        HabitStatus s = statusOf(h);
        assertEquals(1, s.streak());
        assertFalse(s.doneToday());
        assertEquals(60_000, s.progress().value());
        assertEquals(70_000, s.progress().goal());
        assertEquals(List.of(false, false, false, false, false, true, false), s.recent());
    }

    @Test
    void einSonntagGehoertNochZurVorwoche() {
        // Sonntag, 6. September 2026: die Woche ist immer noch die vom 31. August.
        Habit h = habit(HabitKind.STEPS, TODAY);
        Map<LocalDate, Integer> perDay = Map.of(LocalDate.of(2026, 9, 6), 70_000);
        when(steps.steps(any(), any())).thenReturn(perDay);
        HabitStatus s = service.status(h, new HabitsData(List.of(h), List.of()), LocalDate.of(2026, 9, 6));
        assertTrue(s.doneToday());
        // Montag darauf: neue Woche, wieder bei null - aber die Straehne lebt.
        s = service.status(h, new HabitsData(List.of(h), List.of()), LocalDate.of(2026, 9, 7));
        assertEquals(0, s.progress().value());
        assertEquals(1, s.streak());
    }

    // MARK: - Anlegen

    @Test
    void anlegenPrueftNameArtUndZiel() {
        assertThrows(ResponseStatusException.class,
                () -> service.create(new HabitRequest(" ", HabitKind.BUILD, null)));
        assertThrows(ResponseStatusException.class,
                () -> service.create(new HabitRequest("Lesen", null, null)));
        assertThrows(ResponseStatusException.class,
                () -> service.create(new HabitRequest("Schritte", HabitKind.STEPS, 0)));
        HabitStatus s = service.create(new HabitRequest("Lesen", HabitKind.BUILD, 5000));
        assertEquals("Lesen", s.name());
        assertNull(s.weeklyStepGoal(), "ein Wochenziel gibt es nur bei Schritten");
    }
}
