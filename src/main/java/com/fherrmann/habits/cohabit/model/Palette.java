package com.fherrmann.habits.cohabit.model;

import java.util.List;

/** Die feste Farbpalette; die Hexwerte kennen die Clients, hier nur die Schluessel. */
public final class Palette {

    /**
     * Alle Farben, die ein Co-Habit oder eine Typfarbe tragen darf. Die letzten vier
     * (Lavendel, Himmelblau, Salbei, Koralle) kamen am 05.10. fuer die Typfarben dazu.
     */
    public static final List<String> KEYS = List.of("peach", "mint", "periwinkle", "butter", "rose", "aqua",
            "lavender", "sky", "sage", "coral");

    /**
     * Die ersten sechs - daraus werden Avatar- und Anlegefarben abgeleitet. Bleibt fest:
     * mit mehr Farben ergaebe {@link #forId} fuer neue Personen andere Farben als bisher.
     */
    static final List<String> DERIVED = KEYS.subList(0, 6);

    private Palette() {
    }

    public static boolean isValid(String key) {
        return key != null && KEYS.contains(key);
    }

    /** Stabil aus der ID abgeleitet - dieselbe Person hat ueberall dieselbe Avatarfarbe. */
    public static String forId(String id) {
        return DERIVED.get(Math.floorMod(id.hashCode(), DERIVED.size()));
    }

    public static String roundRobin(int index) {
        return DERIVED.get(Math.floorMod(index, DERIVED.size()));
    }
}
