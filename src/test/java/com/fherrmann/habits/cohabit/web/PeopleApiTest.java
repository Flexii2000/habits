package com.fherrmann.habits.cohabit.web;

import com.fherrmann.habits.cohabit.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeopleApiTest extends ApiTestBase {

    @Test
    void profilBearbeitenMitRegeln() {
        JsonNode me = put("/cohabit/api/me", FELIX, map("displayName", " Felix  Herrmann ", "username", "@Felix.H"))
                .expect(200).json();
        assertEquals("Felix Herrmann", me.path("person").path("displayName").asString());
        assertEquals("felix.h", me.path("person").path("username").asString());
        assertEquals("FH", me.path("person").path("initials").asString());
        assertEquals(400, put("/cohabit/api/me", FELIX, map("displayName", "", "username", "felix")).status());
        assertEquals(400, put("/cohabit/api/me", FELIX, map("displayName", "F", "username", "1abc")).status());
        assertEquals(400, put("/cohabit/api/me", FELIX, map("displayName", "F", "username", "ab")).status());
        assertEquals(400, put("/cohabit/api/me", FELIX, map("displayName", "F", "username", "a-b-c")).status());
        get("/cohabit/api/me", TORBEN_APP).expect(200);
        Response taken = put("/cohabit/api/me", TORBEN_APP, map("displayName", "T", "username", "felix.h"));
        assertEquals(409, taken.status());
        assertEquals("Der Nutzername ist schon vergeben.", taken.message());
    }

    @Test
    void benachrichtigungenLassenSichEinzelnSchalten() {
        JsonNode all = get("/cohabit/api/me/notifications", FELIX).expect(200).json();
        assertEquals(8, all.size());
        all.properties().forEach(e -> assertTrue(e.getValue().asBoolean(), e.getKey()));
        JsonNode changed = put("/cohabit/api/me/notifications", FELIX, map("chat", false)).expect(200).json();
        assertFalse(changed.path("chat").asBoolean());
        assertTrue(changed.path("checkins").asBoolean(), "was fehlt, bleibt");
    }

    @Test
    void geraeteWandernZurNeuenPerson() {
        post("/cohabit/api/devices", FELIX, map("token", "abc123", "platform", "ios")).expect(204);
        post("/cohabit/api/devices", TORBEN_APP, map("token", "abc123", "platform", "ios")).expect(204);
        store.read(data -> {
            assertTrue(data.person("felix").orElseThrow().devices.isEmpty());
            assertEquals(1, data.person("torben").orElseThrow().devices.size());
            return null;
        });
        assertEquals(400, post("/cohabit/api/devices", FELIX, map("token", "x", "platform", "windows")).status());
        delete("/cohabit/api/devices/abc123", TORBEN_APP).expect(204);
        store.read(data -> {
            assertTrue(data.person("torben").orElseThrow().devices.isEmpty());
            return null;
        });
    }
}
