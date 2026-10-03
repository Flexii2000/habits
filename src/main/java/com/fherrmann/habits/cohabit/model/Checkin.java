package com.fherrmann.habits.cohabit.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class Checkin {
    public String id;
    public String cohabitId;
    public String personId;
    public CheckinKind kind;
    public LocalDate date;
    public Instant createdAt;
    public Instant updatedAt;
    public Double value;
    public String note;
    /** Das erste Beweisfoto - aeltere Clients kennen nur dieses eine. */
    public String photoId;
    /** Alle Beweisfotos (hoechstens vier), das erste ist {@code photoId}; fehlt bei Eintraegen von vorher. */
    public List<String> photoIds;
    public String caption;
    /** Nur bei Laufpunkten: Dauer in ganzen Minuten und Distanz in km. */
    public Integer durationMinutes;
    public Double distanceKm;
    public CheckinSource source;
    /** Das Timeline-Ereignis dazu (CHECKIN, PHOTO_CHECKIN, HEALTH oder BREAK). */
    public String eventId;
    /** Der Chat-Post bei einem Beweisfoto. */
    public String messageId;

    /** Alle Fotos - auch bei Eintraegen, die nur {@code photoId} kennen. */
    public static List<String> photos(String photoId, List<String> photoIds) {
        if (photoIds != null && !photoIds.isEmpty()) {
            return List.copyOf(photoIds);
        }
        return photoId == null ? List.of() : List.of(photoId);
    }

    public List<String> photos() {
        return photos(photoId, photoIds);
    }

    public void setPhotos(List<String> photos) {
        photoId = photos.isEmpty() ? null : photos.getFirst();
        photoIds = photos.isEmpty() ? null : new ArrayList<>(photos);
    }
}
