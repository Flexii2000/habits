package com.fherrmann.habits.cohabit.model;

import java.util.List;

/** Die feste Farbpalette; die Hexwerte kennen die Clients, hier nur die Schluessel. */
public final class Palette {

    public static final List<String> KEYS = List.of("peach", "mint", "periwinkle", "butter", "rose", "aqua");

    private Palette() {
    }

    public static boolean isValid(String key) {
        return key != null && KEYS.contains(key);
    }

    /** Stabil aus der ID abgeleitet - dieselbe Person hat ueberall dieselbe Avatarfarbe. */
    public static String forId(String id) {
        return KEYS.get(Math.floorMod(id.hashCode(), KEYS.size()));
    }

    public static String roundRobin(int index) {
        return KEYS.get(Math.floorMod(index, KEYS.size()));
    }
}
