package com.fherrmann.habits.cohabit.api;

import com.fherrmann.habits.cohabit.model.Cohabit;
import com.fherrmann.habits.cohabit.model.CohabitType;

import java.time.Instant;

/**
 * @param autoSource die Quelle eines automatischen Co-Habits (FOOD, STEPS_WEEKLY, FOCUS ...), sonst
 *                   {@code null} - die iOS-App faerbt nach Typ und zeigt Balken nur fuer Schritte und Fokus-Zeit
 * @param createdAt fuer die Reihenfolge nach Typ und Anlegedatum wie in der klassischen Liste
 */
public record CohabitRef(String id, String name, String color, CohabitType type, String autoSource,
                         Instant createdAt) {

    public static CohabitRef of(Cohabit c) {
        return new CohabitRef(c.id, c.name, c.color, c.type, c.auto == null ? null : c.auto.source().name(), c.createdAt);
    }
}
