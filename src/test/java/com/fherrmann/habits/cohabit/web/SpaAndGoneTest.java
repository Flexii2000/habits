package com.fherrmann.habits.cohabit.web;

import com.fherrmann.habits.cohabit.support.ApiTestBase;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpaAndGoneTest extends ApiTestBase {

    @Test
    void weboberflaecheLiefertIndexFuerJedeUnterseite() {
        // MockMvc fuehrt Forwards nicht aus - hier genuegt, wohin es geht.
        assertEquals("/cohabit/index.html", get("/cohabit/", Who.nobody()).expect(200).raw.getForwardedUrl());
        Response index = get("/cohabit/index.html", Who.nobody()).expect(200);
        assertTrue(index.header("Content-Type").startsWith("text/html"));
        assertEquals("no-cache", index.header("Cache-Control"));
        for (String path : new String[]{"/cohabit/timeline", "/cohabit/c/c-123/chat", "/cohabit/join/abc",
                "/cohabit/profil", "/cohabit/index.html"}) {
            assertTrue(get(path, Who.nobody()).expect(200).body().contains("<title>coHabit</title>"), path);
        }
        assertTrue(get("/cohabit/rechtliches", Who.nobody()).expect(200).body().contains("Rechtliches"));
        assertEquals(302, get("/cohabit", Who.nobody()).status());
    }

    @Test
    void unbekannteApiPfadeBleiben404() {
        Response r = get("/cohabit/api/gibt-es-nicht", FELIX);
        assertEquals(404, r.status());
        assertEquals("Nicht gefunden.", r.message());
        assertEquals(401, get("/cohabit/api/gibt-es-nicht", Who.nobody()).status());
        assertEquals(404, post("/cohabit/api/dev/clock", FELIX, map("offsetSeconds", -86400)).status(),
                "die Demo-Uhr gibt es nur lokal");
    }

    @Test
    void dieAlteHabitApiIstUmgezogen() {
        for (String path : new String[]{"/habits/api/habits", "/habits/api/habits/abc/marks"}) {
            Response r = get(path, Who.nobody());
            assertEquals(410, r.status(), path);
            assertEquals("Die Habits sind nach coHabit umgezogen: https://fherrmann.com/cohabit/", r.message());
        }
        assertEquals(410, post("/habits/api/habits", FELIX, map("name", "x")).status());
    }
}
