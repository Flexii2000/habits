package com.fherrmann.habits.cohabit.migration;

import com.fherrmann.habits.client.FoodClient;
import com.fherrmann.habits.cohabit.model.AutoSource;
import com.fherrmann.habits.cohabit.model.Checkin;
import com.fherrmann.habits.cohabit.model.CheckinKind;
import com.fherrmann.habits.cohabit.model.CheckinSource;
import com.fherrmann.habits.cohabit.model.Cohabit;
import com.fherrmann.habits.cohabit.model.CohabitType;
import com.fherrmann.habits.cohabit.model.Member;
import com.fherrmann.habits.cohabit.model.Person;
import com.fherrmann.habits.cohabit.model.RhythmKind;
import com.fherrmann.habits.cohabit.model.Role;
import com.fherrmann.habits.cohabit.rules.AutoFacts;
import com.fherrmann.habits.cohabit.rules.Evaluations;
import com.fherrmann.habits.cohabit.rules.StreakCalc;
import com.fherrmann.habits.cohabit.rules.StreakModel;
import com.fherrmann.habits.cohabit.service.AutoSources;
import com.fherrmann.habits.cohabit.store.CohabitStore;
import com.fherrmann.habits.cohabit.support.FakeSources;
import com.fherrmann.habits.legacy.Habit;
import com.fherrmann.habits.legacy.HabitKind;
import com.fherrmann.habits.legacy.HabitStatus;
import com.fherrmann.habits.legacy.HabitsData;
import com.fherrmann.habits.legacy.LegacyHabitsService;
import com.fherrmann.habits.legacy.Mark;
import com.fherrmann.habits.repository.FocusRepository;
import com.fherrmann.habits.security.HealthUsers;
import com.fherrmann.habits.service.FocusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Die Migration gegen eine Nachbildung von Felix' habits.json (9 Habits, 24
 * Eintraege, 16 Fokus-Sessions): jede Serie muss an jedem Stichtag genau das
 * sein, was die alte API geliefert haette - gerechnet mit der alten Logik im
 * Testbaum ({@link LegacyHabitsService}).
 */
class MigrationParityTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final LocalDate STICHTAG = LocalDate.of(2026, 9, 30);

    @TempDir
    Path dir;

    private HabitsData legacy;
    private CohabitStore store;
    private FakeSources.Food food;
    private FakeSources.Steps steps;
    private FocusService focus;
    private AutoSources sources;
    private byte[] originalHabits;

    @BeforeEach
    void setUp() throws IOException {
        Path habitsFile = dir.resolve("habits.json");
        Path focusFile = dir.resolve("focus.json");
        copy("/migration/habits.json", habitsFile);
        copy("/migration/focus.json", focusFile);
        originalHabits = Files.readAllBytes(habitsFile);
        JsonMapper mapper = JsonMapper.builder().build();
        legacy = mapper.readValue(originalHabits, HabitsData.class);

        HealthUsers users = new HealthUsers("felix", "");
        Clock clock = Clock.fixed(STICHTAG.atTime(21, 0).atZone(BERLIN).toInstant(), BERLIN);
        store = new CohabitStore(dir.resolve("cohabit").toString());
        food = new FakeSources.Food(users);
        // Ein Muster mit Luecken: jeder siebte Tag verfehlt, dazwischen mal ueber
        // kcal, mal ueber die drei Mahlzeiten erfuellt - und schon vor dem 2.9.
        food.days = (person, day) -> {
            int n = day.getDayOfYear();
            if (n % 7 == 3) {
                return new FoodClient.Day(900, 2300, Set.of("BREAKFAST", "SNACK"));
            }
            return n % 2 == 0
                    ? new FoodClient.Day(1900, 2300, Set.of())
                    : new FoodClient.Day(1200, 2300, Set.of("BREAKFAST", "LUNCH", "DINNER"));
        };
        steps = new FakeSources.Steps(users);
        steps.perDay = (person, day) -> 8000 + (day.getDayOfYear() % 5) * 1500;
        focus = new FocusService(new FocusRepository(focusFile.toString(), mapper), clock);
        sources = new AutoSources(food, steps, focus, users);

        new Migration(store, habitsFile.toString(), users, clock).run();
    }

    private static void copy(String resource, Path target) throws IOException {
        try (InputStream in = MigrationParityTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, resource);
            Files.write(target, in.readAllBytes());
        }
    }

    @Test
    void jedesHabitWirdEinCoHabitMitFelixAlsAdmin() {
        store.read(data -> {
            assertEquals(9, data.cohabits().cohabits.size());
            Person felix = data.person("felix").orElseThrow();
            assertEquals("Felix", felix.displayName);
            assertEquals("felix", felix.username);
            for (Habit habit : legacy.habits()) {
                Cohabit c = data.cohabit("c-" + habit.id()).orElseThrow();
                assertEquals(habit.name(), c.name);
                assertEquals("Europe/Berlin", c.timezone);
                assertEquals(336, c.backfillHours);
                assertFalse(c.photoRequired);
                assertNull(c.reminderTime);
                assertEquals(habit.createdAt(), c.startDate);
                assertEquals(1, c.members.size());
                Member m = c.members.getFirst();
                assertEquals("felix", m.personId);
                assertEquals(Role.ADMIN, m.role);
                switch (habit.kind()) {
                    case BUILD -> assertEquals(CohabitType.STREAK, c.type);
                    case QUIT -> assertEquals(CohabitType.ABSTINENCE, c.type);
                    default -> assertNotNull(c.auto);
                }
            }
            Cohabit politics = byName(data, "Politisch aktiv sein");
            assertEquals(RhythmKind.TIMES_PER_MONTH, politics.streak.rhythm().kind());
            assertEquals(2, politics.streak.rhythm().times());
            Cohabit paper = byName(data, "Zeitungsartikel lesen");
            assertEquals(RhythmKind.TIMES_PER_WEEK, paper.streak.rhythm().kind());
            Cohabit stepsCohabit = byName(data, "70.000 Schritte / Woche");
            assertEquals(AutoSource.STEPS_WEEKLY, stepsCohabit.auto.source());
            assertEquals(70_000, stepsCohabit.auto.weeklyStepGoal());
            assertEquals(1, stepsCohabit.streak.rhythm().times());
            Cohabit fokus = byName(data, "Fokus");
            assertEquals(AutoSource.FOCUS, fokus.auto.source());
            assertEquals(240, fokus.auto.focusMinutesGoal());
            assertEquals(AutoSource.FOOD, byName(data, "Track food").auto.source());
            // Die Farben reihum aus der Palette.
            assertEquals("peach", byName(data, "Track food").color);
            assertEquals("mint", byName(data, "Logbook").color);
            return null;
        });
    }

    @Test
    void dieEintraegeWerdenZuMigriertenCheckins() {
        store.read(data -> {
            int total = 0;
            for (Cohabit c : data.cohabits().cohabits) {
                for (Checkin checkin : data.checkins(c.id)) {
                    total++;
                    assertEquals(CheckinSource.MIGRATED, checkin.source);
                    assertEquals(c.type == CohabitType.ABSTINENCE ? CheckinKind.BREAK : CheckinKind.DONE, checkin.kind);
                    assertEquals(checkin.date.atTime(LocalTime.NOON).atZone(BERLIN).toInstant(), checkin.createdAt);
                    assertEquals("felix", checkin.personId);
                }
            }
            assertEquals(24, total);
            // Keine Systemmeldungen, keine Timeline.
            assertTrue(data.events().events.isEmpty());
            for (Cohabit c : data.cohabits().cohabits) {
                assertTrue(data.messages(c.id).isEmpty());
            }
            return null;
        });
    }

    @Test
    void habitsJsonBleibtUnveraendertUndEinZweiterLaufTutNichts() throws IOException {
        assertArrayEquals(originalHabits, Files.readAllBytes(dir.resolve("habits.json")));
        assertTrue(Files.exists(dir.resolve("cohabit/migrated.marker")));
        HealthUsers users = new HealthUsers("felix", "");
        Clock clock = Clock.fixed(STICHTAG.atStartOfDay(BERLIN).toInstant(), BERLIN);
        assertEquals(0, new Migration(store, dir.resolve("habits.json").toString(), users, clock).run());
        store.read(data -> {
            assertEquals(9, data.cohabits().cohabits.size());
            return null;
        });
    }

    @Test
    void ohneHabitsJsonEntstehtNurDerMarker(@TempDir Path empty) {
        CohabitStore fresh = new CohabitStore(empty.resolve("cohabit").toString());
        Clock clock = Clock.fixed(STICHTAG.atStartOfDay(BERLIN).toInstant(), BERLIN);
        assertEquals(0, new Migration(fresh, empty.resolve("habits.json").toString(),
                new HealthUsers("felix", ""), clock).run());
        assertTrue(Files.exists(empty.resolve("cohabit/migrated.marker")));
        fresh.read(data -> {
            assertTrue(data.cohabits().cohabits.isEmpty());
            return null;
        });
    }

    /**
     * Der Kern: am Stichtag 2026-09-30 und an jedem Tag davor und danach (mit dem
     * Stand, den es an dem Tag gab) dieselbe Serie wie die alte API.
     */
    @Test
    void dieSerienStimmenMitDerAltenRechnungUeberein() {
        for (LocalDate day = LocalDate.of(2026, 9, 23); !day.isAfter(LocalDate.of(2026, 10, 31)); day = day.plusDays(1)) {
            assertParity(day);
        }
    }

    @Test
    void amStichtagStimmtJedeSerie() {
        assertParity(STICHTAG);
    }

    private void assertParity(LocalDate day) {
        Clock clock = Clock.fixed(day.atTime(21, 0).atZone(BERLIN).toInstant(), BERLIN);
        LegacyHabitsService old = new LegacyHabitsService(
                d -> food.day("felix", d),
                (from, to) -> steps.steps("felix", from, to),
                focus::minutesPerDay,
                clock, 730);
        List<Mark> marksThen = legacy.marks().stream().filter(m -> !m.date().isAfter(day)).toList();
        HabitsData then = new HabitsData(legacy.habits(), marksThen);
        final LocalDate today = day;
        store.read(data -> {
            for (Habit habit : legacy.habits()) {
                HabitStatus expected = old.status(habit, then, today);
                Cohabit c = data.cohabit("c-" + habit.id()).orElseThrow();
                Member m = c.members.getFirst();
                List<Checkin> checkins = data.checkins(c.id).stream()
                        .filter(ch -> !ch.date.isAfter(today)).toList();
                String label = habit.name() + " am " + today;
                if (habit.kind() == HabitKind.QUIT) {
                    assertEquals(expected.streak(), Evaluations.abstinence(c, m, checkins, today).current(), label);
                    continue;
                }
                AutoFacts facts = c.auto == null ? null
                        : sources.fetch(c.auto, "felix", Evaluations.memberStart(c, m), today,
                        StreakModel.paused(m.pauses));
                StreakCalc.Outcome actual = Evaluations.streak(c, m, checkins, facts, today);
                assertEquals(expected.streak(), actual.current(), label);
                if (habit.kind() != HabitKind.STEPS) {
                    // Bei den Schritten gilt jetzt die allgemeine Regel "laufender Zeitraum
                    // offen = gefaehrdet"; die alte API meldete dort nie atRisk.
                    assertEquals(expected.atRisk(), actual.atRisk(), label + " (atRisk)");
                }
            }
            return null;
        });
    }

    @Test
    void amStichtagSindDieSerienPlausibel() {
        // Handgerechnet, damit die Paritaet nicht zwei gleich falsche Rechnungen vergleicht.
        store.read(data -> {
            assertEquals(4, current(data, "Logbook"), "26.-29.9. abgehakt, heute offen");
            assertEquals(3, current(data, "Morgens dehnen"));
            assertEquals(2, current(data, "Zeitungsartikel lesen"), "diese Woche (30.9.) und letzte (24.9.)");
            assertEquals(0, current(data, "Politisch aktiv sein"), "erst einmal im September");
            Cohabit sweets = byName(data, "Ohne Süßigkeiten");
            assertEquals(4, Evaluations.abstinence(sweets, sweets.members.getFirst(), data.checkins(sweets.id), STICHTAG)
                    .current(), "seit dem Rueckfall am 26.9.");
            Cohabit drinks = byName(data, "Ohne Energydrinks");
            assertEquals(1, Evaluations.abstinence(drinks, drinks.members.getFirst(), data.checkins(drinks.id),
                    STICHTAG).current(), "Rueckfall gestern, heute zaehlt");
            return null;
        });
    }

    private int current(CohabitStore.Data data, String name) {
        Cohabit c = byName(data, name);
        return Evaluations.streak(c, c.members.getFirst(), data.checkins(c.id), null, STICHTAG).current();
    }

    private static Cohabit byName(CohabitStore.Data data, String name) {
        return data.cohabits().cohabits.stream().filter(c -> c.name.equals(name)).findFirst().orElseThrow();
    }

    @Test
    void bestserieUndMeilensteineSindStillErfasst() {
        store.read(data -> {
            Cohabit logbook = byName(data, "Logbook");
            Member m = logbook.members.getFirst();
            assertEquals(4, m.bestStreak, "3.-6.9. und 26.-29.9. - vier Tage");
            assertFalse(m.baselinePending);
            Cohabit trackFood = byName(data, "Track food");
            assertTrue(trackFood.members.getFirst().baselinePending, "automatisch: erst beim ersten Lauf");
            Map<String, List<Integer>> sweets = byName(data, "Ohne Süßigkeiten").members.getFirst().milestones;
            assertEquals(1, sweets.size());
            return null;
        });
    }
}
