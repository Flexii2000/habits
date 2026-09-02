package com.fherrmann.habits.repository;

import com.fherrmann.habits.model.Habit;
import com.fherrmann.habits.model.HabitKind;
import com.fherrmann.habits.model.HabitsData;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Liest und schreibt {@code habits.json}.
 *
 * <p>Gibt es die Datei noch nicht, beginnt der Dienst mit den drei Habits, mit
 * denen Felix angefangen hat - statt mit einer leeren Liste, die erst einmal
 * nach Arbeit aussieht.
 */
@Repository
public class HabitsRepository {

    private final Path dataFile;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public HabitsRepository(
            @Value("${habits.data-file:data/habits.json}") String dataFile,
            ObjectMapper objectMapper,
            Clock clock) {
        this.dataFile = Path.of(dataFile);
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public synchronized HabitsData load() {
        if (!Files.exists(dataFile)) {
            HabitsData seed = seed(LocalDate.now(clock));
            save(seed);
            return seed;
        }
        try {
            return objectMapper.readValue(Files.readAllBytes(dataFile), HabitsData.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read habits file: " + dataFile, e);
        }
    }

    /**
     * Schreibt erst daneben und benennt dann um.
     *
     * <p>Ein Absturz mitten im Schreiben liesse sonst eine halbe Datei zurueck -
     * und mit ihr jede Straehne, die je gezaehlt wurde.
     */
    public synchronized void save(HabitsData data) {
        try {
            if (dataFile.getParent() != null) {
                Files.createDirectories(dataFile.getParent());
            }
            Path temp = dataFile.resolveSibling(dataFile.getFileName() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), data);
            Files.move(temp, dataFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write habits file: " + dataFile, e);
        }
    }

    /** Die drei Habits vom Anfang. Namen bewusst so, wie Felix sie genannt hat. */
    static HabitsData seed(LocalDate today) {
        return new HabitsData(List.of(
                new Habit(newId(), "Track food", HabitKind.FOOD, null, today),
                new Habit(newId(), "Logbook", HabitKind.BUILD, null, today),
                new Habit(newId(), "70.000 Schritte / Woche", HabitKind.STEPS, 70_000, today)),
                List.of());
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }
}
