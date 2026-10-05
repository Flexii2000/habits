package com.fherrmann.habits.cohabit.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Welche Farbe jede Person fuer jeden Typ sieht (seit 05.10.): Heute, Detail, Chat,
 * Timeline, Widgets faerben ein Co-Habit nach seinem Typ, und jede Person darf sich die
 * Farben selbst aussuchen. Automatische Co-Habits haben ihren eigenen Platz.
 */
public final class TypeColors {

    /** Die Plaetze in fester Reihenfolge - so zeigen die Clients sie auch an. */
    public static final List<String> SLOTS = List.of("STREAK", "ABSTINENCE", "GOAL", "CHALLENGE", "AUTOMATIC");

    /** Wie die Typkarten im Anlegen-Schritt 1; automatische in Aqua. */
    public static final Map<String, String> DEFAULTS = Map.of(
            "STREAK", "peach",
            "ABSTINENCE", "mint",
            "GOAL", "periwinkle",
            "CHALLENGE", "butter",
            "AUTOMATIC", "aqua");

    private TypeColors() {
    }

    /** Alle fuenf Plaetze: die eigene Wahl, sonst die Vorgabe. */
    public static Map<String, String> effective(Map<String, String> chosen) {
        Map<String, String> colors = new LinkedHashMap<>();
        for (String slot : SLOTS) {
            String own = chosen == null ? null : chosen.get(slot);
            colors.put(slot, Palette.isValid(own) ? own : DEFAULTS.get(slot));
        }
        return colors;
    }
}
