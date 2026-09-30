package com.fherrmann.habits.cohabit.store;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Lesen und atomares Schreiben einzelner JSON-Dateien. */
public final class JsonFiles {

    private JsonFiles() {
    }

    public static <T> T read(ObjectMapper mapper, Path file, Class<T> type, T fallback) {
        if (!Files.exists(file)) {
            return fallback;
        }
        try {
            return mapper.readValue(Files.readAllBytes(file), type);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + file, e);
        }
    }

    /**
     * Erst daneben schreiben, dann umbenennen. Ein Absturz mitten im Schreiben
     * liesse sonst eine halbe Datei zurueck - und mit ihr jede Serie.
     */
    public static void write(ObjectMapper mapper, Path file, Object value) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(temp, mapper.writeValueAsBytes(value));
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + file, e);
        }
    }

    public static void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete " + file, e);
        }
    }
}
