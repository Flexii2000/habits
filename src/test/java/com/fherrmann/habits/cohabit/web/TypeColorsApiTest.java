package com.fherrmann.habits.cohabit.web;

import com.fherrmann.habits.cohabit.model.Palette;
import com.fherrmann.habits.cohabit.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Farben je Typ, je Person einstellbar (05.10.). */
class TypeColorsApiTest extends ApiTestBase {

    private static final String DEFAULTS =
            "{\"STREAK\":\"peach\",\"ABSTINENCE\":\"mint\",\"GOAL\":\"periwinkle\",\"CHALLENGE\":\"butter\",\"AUTOMATIC\":\"aqua\"}";

    @Test
    void vorgabenBisJemandSieAendert() {
        assertEquals(DEFAULTS, get("/cohabit/api/me/type-colors", FELIX).expect(200).json().toString());
        assertEquals(DEFAULTS, get("/cohabit/api/me", FELIX).json().path("typeColors").toString());
        assertEquals(DEFAULTS, get("/cohabit/api/widget", FELIX).json().path("typeColors").toString());
    }

    @Test
    void jedePersonHatIhreEigenen() {
        Registered lena = register("Lena", "lena", FELIX);
        JsonNode mine = put("/cohabit/api/me/type-colors", FELIX, Map.of("STREAK", "sky", "ABSTINENCE", "coral"))
                .expect(200).json();
        assertEquals("sky", mine.path("STREAK").asString());
        assertEquals("coral", mine.path("ABSTINENCE").asString());
        assertEquals("periwinkle", mine.path("GOAL").asString(), "nicht genannte bleiben");
        assertEquals("sky", get("/cohabit/api/me", FELIX).json().path("typeColors").path("STREAK").asString());
        assertEquals("sky", get("/cohabit/api/widget", FELIX).json().path("typeColors").path("STREAK").asString());
        assertEquals(DEFAULTS, get("/cohabit/api/me/type-colors", lena.who()).json().toString(), "Lena sieht ihre");

        // null heisst: zurueck zur Vorgabe.
        Map<String, String> reset = new HashMap<>();
        reset.put("STREAK", null);
        assertEquals("peach", put("/cohabit/api/me/type-colors", FELIX, reset).expect(200).json()
                .path("STREAK").asString());
        assertEquals("coral", get("/cohabit/api/me/type-colors", FELIX).json().path("ABSTINENCE").asString());
    }

    @Test
    void unbekannteTypenUndFarbenAendernNichts() {
        assertEquals("Unbekannte Farbe.", put("/cohabit/api/me/type-colors", FELIX,
                Map.of("STREAK", "sky", "GOAL", "black")).message());
        assertEquals("Unbekannter Typ.", put("/cohabit/api/me/type-colors", FELIX, Map.of("HABIT", "sky")).message());
        assertEquals(DEFAULTS, get("/cohabit/api/me/type-colors", FELIX).json().toString(), "nichts halb gesetzt");
        assertEquals(401, get("/cohabit/api/me/type-colors", Who.nobody()).status());
    }

    @Test
    void dieNeuenFarbenGehenAuchFuerCoHabits() {
        Map<String, Object> config = streak("Lesen", daily());
        config.put("color", "sage");
        JsonNode created = create(FELIX, config);
        assertEquals("sage", created.path("summary").path("ref").path("color").asString());
    }

    @Test
    void avatarfarbenBleibenBeiDenAltenSechs() {
        // Neue Farben duerfen die abgeleiteten Avatarfarben nicht verschieben.
        for (int i = 0; i < 200; i++) {
            String color = Palette.forId("u-" + Integer.toHexString(i * 7919));
            assertTrue(Palette.KEYS.indexOf(color) < 6, color);
        }
        assertEquals("peach", Palette.roundRobin(0));
        assertEquals("peach", Palette.roundRobin(6));
    }
}
