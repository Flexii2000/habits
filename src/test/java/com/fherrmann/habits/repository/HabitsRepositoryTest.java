package com.fherrmann.habits.repository;

import com.fherrmann.habits.model.HabitKind;
import com.fherrmann.habits.model.HabitsData;
import com.fherrmann.habits.model.Mark;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HabitsRepositoryTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final Clock CLOCK = Clock.fixed(
            ZonedDateTime.of(2026, 9, 2, 12, 0, 0, 0, ZoneId.of("Europe/Berlin")).toInstant(),
            ZoneId.of("Europe/Berlin"));

    @TempDir
    Path dir;

    @Test
    void ersteInstallationBeginntMitDenDreiHabitsVomAnfang() {
        HabitsRepository repository = new HabitsRepository(dir.resolve("habits.json").toString(), MAPPER, CLOCK);
        HabitsData data = repository.load();
        assertEquals(3, data.habits().size());
        assertEquals(List.of(HabitKind.FOOD, HabitKind.BUILD, HabitKind.STEPS),
                data.habits().stream().map(h -> h.kind()).toList());
        assertEquals(70_000, data.habits().get(2).weeklyStepGoal());
        assertEquals(LocalDate.of(2026, 9, 2), data.habits().get(0).createdAt());
        assertTrue(Files.exists(dir.resolve("habits.json")), "der Anfangsstand wird gleich gespeichert");
    }

    @Test
    void speichernUndWiederLesen() {
        HabitsRepository repository = new HabitsRepository(dir.resolve("habits.json").toString(), MAPPER, CLOCK);
        HabitsData data = repository.load();
        HabitsData mitHaken = new HabitsData(data.habits(),
                List.of(new Mark(data.habits().get(1).id(), LocalDate.of(2026, 9, 1))));
        repository.save(mitHaken);
        assertEquals(mitHaken, repository.load());
    }
}
