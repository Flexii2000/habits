package com.fherrmann.habits.controller;

import com.fherrmann.habits.cohabit.support.ApiTestBase;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Der Wald der Fokus-App: {@code /habits/api/focus/sessions} bleibt, wie er war. */
class FocusControllerTest extends ApiTestBase {

    private static final Who PRIVATE_COOKIE = Who.cookies(new Cookie("fh_private", PRIVATE));
    private static final String BODY =
            "{\"id\":\"s1\",\"start\":\"2026-09-30T06:00:00Z\",\"end\":\"2026-09-30T06:45:00Z\"}";

    @Test
    void ohneCookieVerbotenUndNurDieEigentuemerinDarf() {
        get("/habits/api/focus/sessions?from=2026-09-01&to=2026-09-30", Who.nobody()).expect(403);
        get("/habits/api/focus/sessions?from=2026-09-01&to=2026-09-30", TORBEN_APP).expect(403);
        get("/habits/api/focus/sessions?from=2026-09-01&to=2026-09-30", PRIVATE_COOKIE).expect(200);
    }

    @Test
    void neuerBaum201BekannterBaum200UndFaellen204() {
        Response created = post("/habits/api/focus/sessions", PRIVATE_COOKIE, BODY).expect(201);
        assertEquals(45, created.json().path("minutes").asInt());
        assertEquals("2026-09-30", created.json().path("day").asString());
        post("/habits/api/focus/sessions", PRIVATE_COOKIE, BODY).expect(200);
        Response list = get("/habits/api/focus/sessions?from=2026-09-01&to=2026-09-30", PRIVATE_COOKIE).expect(200);
        assertEquals(1, list.json().size());
        delete("/habits/api/focus/sessions/s1", PRIVATE_COOKIE).expect(204);
        delete("/habits/api/focus/sessions/s1", PRIVATE_COOKIE).expect(404);
    }

    @Test
    void kategorienUeberHttpUndEinBaumMitKategorie() {
        get("/habits/api/focus/categories", TORBEN_APP).expect(403);
        Response created = post("/habits/api/focus/categories", PRIVATE_COOKIE,
                "{\"id\":\"kat-thesis-0001\",\"name\":\"Bachelorarbeit\"}").expect(201);
        assertEquals("Bachelorarbeit", created.json().path("name").asString());
        post("/habits/api/focus/categories", PRIVATE_COOKIE,
                "{\"id\":\"kat-thesis-0001\",\"name\":\"Bachelorarbeit\"}").expect(200);
        Response twice = post("/habits/api/focus/categories", PRIVATE_COOKIE, "{\"name\":\"bachelorarbeit\"}").expect(409);
        assertEquals("Diese Kategorie gibt es schon.", twice.body());
        assertEquals("Thesis", put("/habits/api/focus/categories/kat-thesis-0001", PRIVATE_COOKIE,
                "{\"name\":\"Thesis\"}").expect(200).json().path("name").asString());

        Response tree = post("/habits/api/focus/sessions", PRIVATE_COOKIE,
                "{\"id\":\"s2\",\"start\":\"2026-09-30T06:00:00Z\",\"end\":\"2026-09-30T07:00:00Z\","
                        + "\"categoryId\":\"kat-thesis-0001\"}").expect(201);
        assertEquals("Thesis", tree.json().path("categoryName").asString());
        post("/habits/api/focus/sessions", PRIVATE_COOKIE,
                "{\"id\":\"s3\",\"start\":\"2026-09-30T07:00:00Z\",\"end\":\"2026-09-30T07:30:00Z\","
                        + "\"categoryId\":\"gibt-es-nicht\"}").expect(400);

        assertEquals(1, get("/habits/api/focus/categories", PRIVATE_COOKIE).expect(200).json().size());
        assertEquals(1, get("/cohabit/api/focus/categories", FELIX).expect(200).json().size(),
                "dieselbe Liste in coHabit");
        delete("/habits/api/focus/categories/kat-thesis-0001", PRIVATE_COOKIE).expect(204);
        assertTrue(get("/habits/api/focus/categories", PRIVATE_COOKIE).expect(200).json().isEmpty());
        assertEquals("Thesis", get("/habits/api/focus/sessions?from=2026-09-30&to=2026-09-30", PRIVATE_COOKIE)
                .json().get(0).path("categoryName").asString(), "der Baum behaelt den Namen");
    }

    @Test
    void fehlerKommenHierWeiterAlsKlartext() {
        Response bad = post("/habits/api/focus/sessions", PRIVATE_COOKIE,
                "{\"id\":\"kurz\",\"start\":\"2026-09-30T06:00:00Z\",\"end\":\"2026-09-30T06:00:00Z\"}").expect(400);
        assertEquals("Eine Session dauert mindestens eine Minute.", bad.body());
        assertTrue(bad.header("Content-Type").startsWith("text/plain"));
    }
}
