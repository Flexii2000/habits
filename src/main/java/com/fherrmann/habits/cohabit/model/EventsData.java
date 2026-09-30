package com.fherrmann.habits.cohabit.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Alles in {@code events.json}: Timeline, Stupser und wer die Timeline bis wohin gesehen hat. */
public class EventsData {
    public List<Event> events = new ArrayList<>();
    public List<Nudge> nudges = new ArrayList<>();
    public Map<String, String> timelineSeen = new LinkedHashMap<>();
}
