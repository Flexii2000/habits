package com.fherrmann.habits.cohabit.service;

import com.fherrmann.habits.cohabit.model.PhotoMeta;
import com.fherrmann.habits.cohabit.store.CohabitStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Wo die Fotos liegen ({@code photos/<id>.jpg}, {@code photos/<id>_thumb.jpg}) - und wie sie verschwinden. */
@Component
public class PhotoFiles {

    private static final Logger log = LoggerFactory.getLogger(PhotoFiles.class);

    private final CohabitStore store;

    public PhotoFiles(CohabitStore store) {
        this.store = store;
    }

    public Path full(String id) {
        return store.photosDir().resolve(CohabitStoreIds.require(id) + ".jpg");
    }

    public Path thumb(String id) {
        return store.photosDir().resolve(CohabitStoreIds.require(id) + "_thumb.jpg");
    }

    /**
     * Entfernt die Metadaten sofort und die Dateien nach dem Speichern - wird die
     * Transaktion zurueckgerollt, bleiben die Dateien liegen.
     */
    public void delete(CohabitStore.Tx tx, List<String> photoIds) {
        if (photoIds.isEmpty()) {
            return;
        }
        List<String> ids = new ArrayList<>(photoIds);
        tx.photosW().photos.removeIf(p -> ids.contains(p.id));
        tx.afterCommit(() -> {
            for (String id : ids) {
                deleteFiles(id);
            }
        });
    }

    public void delete(CohabitStore.Tx tx, String photoId) {
        if (photoId != null) {
            delete(tx, List.of(photoId));
        }
    }

    public void deleteFiles(String id) {
        try {
            Files.deleteIfExists(full(id));
            Files.deleteIfExists(thumb(id));
        } catch (IOException | IllegalArgumentException e) {
            log.warn("Foto {} liess sich nicht loeschen", id, e);
        }
    }

    public static List<String> usedIn(CohabitStore.Data data, String cohabitId) {
        return data.photos().photos.stream().filter(p -> cohabitId.equals(p.cohabitId)).map(p -> p.id).toList();
    }

    public static PhotoMeta meta(CohabitStore.Data data, String id) {
        return data.photos().photos.stream().filter(p -> p.id.equals(id)).findFirst().orElse(null);
    }

    /** Fotos-IDs landen in Dateinamen - nur sichere Zeichen. */
    static final class CohabitStoreIds {
        static String require(String id) {
            if (!CohabitStore.isSafeId(id)) {
                throw new IllegalArgumentException("Unsafe photo id");
            }
            return id;
        }
    }
}
