package com.fherrmann.habits.cohabit.model;

import java.time.Instant;
import java.time.LocalDate;

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
    public String photoId;
    public String caption;
    /** Nur bei Laufpunkten: Dauer in ganzen Minuten und Distanz in km. */
    public Integer durationMinutes;
    public Double distanceKm;
    public CheckinSource source;
    /** Das Timeline-Ereignis dazu (CHECKIN, PHOTO_CHECKIN, HEALTH oder BREAK). */
    public String eventId;
    /** Der Chat-Post bei einem Beweisfoto. */
    public String messageId;
}
