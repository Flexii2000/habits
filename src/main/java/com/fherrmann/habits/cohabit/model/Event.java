package com.fherrmann.habits.cohabit.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Ein Timeline-Ereignis. Titel und Unterzeile entstehen beim Lesen aus diesen
 * Feldern; nur was sich spaeter nicht mehr rekonstruieren liesse (die Serie im
 * Moment des Abhakens) steht als {@code detail} fest drin.
 */
public class Event {
    public String id;
    public String cohabitId;
    public EventKind kind;
    public String personId;
    public Instant at;
    public LocalDate day;
    public String checkinId;
    public LocalDate checkinDate;
    public String photoId;
    public String caption;
    public Double value;
    public String detail;
    public List<Reaction> reactions = new ArrayList<>();
}
