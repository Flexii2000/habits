package com.fherrmann.habits.repository;

import com.fherrmann.habits.model.FocusData;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Liest und schreibt {@code focus.json} - die Fokus-Sessions, eigene Datei
 * neben {@code habits.json}: die Sessions kommen von einem anderen Client-Teil
 * (dem Wald), wachsen anders und sollen die Habit-Datei nicht anfassen.
 */
@Repository
public class FocusRepository {

    private final Path dataFile;
    private final ObjectMapper objectMapper;

    public FocusRepository(
            @Value("${habits.focus-file:data/focus.json}") String dataFile,
            ObjectMapper objectMapper) {
        this.dataFile = Path.of(dataFile);
        this.objectMapper = objectMapper;
    }

    public synchronized FocusData load() {
        if (!Files.exists(dataFile)) {
            return FocusData.empty();
        }
        try {
            return objectMapper.readValue(Files.readAllBytes(dataFile), FocusData.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read focus file: " + dataFile, e);
        }
    }

    /** Erst daneben schreiben, dann umbenennen - wie bei den Habits. */
    public synchronized void save(FocusData data) {
        try {
            if (dataFile.getParent() != null) {
                Files.createDirectories(dataFile.getParent());
            }
            Path temp = dataFile.resolveSibling(dataFile.getFileName() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), data);
            Files.move(temp, dataFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write focus file: " + dataFile, e);
        }
    }
}
