package com.fherrmann.habits.cohabit.web;

import com.fherrmann.habits.client.FoodClient;
import com.fherrmann.habits.client.SourceUnavailableException;
import com.fherrmann.habits.cohabit.service.KcalSync;
import com.fherrmann.habits.cohabit.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * kcal aus Healthy als Wert eines Co-Habits: je Mitglied mit Einwilligung und
 * Healthy-Zugang holt der Dienst die kcal des Tages selbst aus dem Kalorienzaehler -
 * ein Wert je Tag, in der Nachtragsfrist, mindestens heute und gestern. Heute ist der
 * 30.09.2026.
 */
class KcalApiTest extends ApiTestBase {

    @Autowired
    KcalSync kcalSync;

    /** Ein Teamziel ueber kcal, gestartet vor fuenf Tagen, Torben ist dabei. */
    private String kcalGoal() {
        return createDaysAgo(FELIX, map("type", "GOAL", "name", "Kalorien", "color", "peach",
                "health", map("metric", "KCAL"),
                "goal", map("target", 50000, "deadline", "2026-10-31", "counting", "AMOUNT", "mode", "TEAM")), 5,
                TORBEN_APP);
    }

    private static List<String> entries(JsonNode detail) {
        List<String> list = new ArrayList<>();
        detail.path("myCheckins").forEach(c -> list.add(c.path("date").asString() + "=" + c.path("value").asLong()));
        list.sort(String::compareTo);
        return list;
    }

    private JsonNode detail(String id, Who who) {
        return get("/cohabit/api/cohabits/" + id, who).expect(200).json();
    }

    @Test
    void zustimmenHoltDieKcalDerNachtragsfristSofort() {
        food.days = (person, day) -> person.equals("torben")
                ? new FoodClient.Day(day.getDayOfMonth() * 100, 2300, Set.of())
                : new FoodClient.Day(1800, 2300, Set.of());
        String id = kcalGoal();
        JsonNode before = detail(id, TORBEN_APP);
        assertEquals("KCAL", before.path("health").path("metric").asString());
        assertEquals("kcal aus Healthy", before.path("health").path("label").asString());
        assertEquals("HEALTHY", before.path("health").path("source").asString());
        assertEquals("nur die kcal des Tages werden geteilt", before.path("health").path("shareText").asString());
        assertEquals("KCAL", before.path("config").path("tracking").path("unit").asString());
        assertFalse(before.path("health").path("consent").asBoolean(), "ohne Zustimmung kommt nichts");
        assertTrue(entries(before).isEmpty());

        JsonNode after = put("/cohabit/api/cohabits/" + id + "/settings/me", TORBEN_APP,
                map("healthConsent", true)).expect(200).json();
        assertTrue(after.path("health").path("consent").asBoolean());
        assertEquals(List.of("2026-09-28=2800", "2026-09-29=2900", "2026-09-30=3000"), entries(after),
                "48 h Frist: vorgestern bis heute");
        assertFalse(after.path("health").path("lastSyncAt").isNull());
        assertEquals("8.700 von 50.000", detail(id, FELIX).path("goal").path("totalText").asString(),
                "Felix hat nicht zugestimmt - nur Torbens kcal zaehlen");
        assertTrue(entries(detail(id, FELIX)).isEmpty());
    }

    @Test
    void derSchedulerAktualisiertHeuteUndGesternOhneDoppelteEintraege() {
        int[] today = {1200};
        food.days = (person, day) -> new FoodClient.Day(day.equals(LocalDate.of(2026, 9, 30)) ? today[0] : 2000,
                2300, Set.of());
        String id = kcalGoal();
        put("/cohabit/api/cohabits/" + id + "/settings/me", TORBEN_APP, map("healthConsent", true)).expect(200);
        assertEquals(List.of("2026-09-28=2000", "2026-09-29=2000", "2026-09-30=1200"), entries(detail(id, TORBEN_APP)));

        today[0] = 2450;
        assertEquals(1, kcalSync.syncAll(clock.instant()), "nur heute hat sich geaendert");
        assertEquals(List.of("2026-09-28=2000", "2026-09-29=2000", "2026-09-30=2450"), entries(detail(id, TORBEN_APP)));
        assertEquals(0, kcalSync.syncAll(clock.instant()), "unveraendert: nichts schreiben");

        today[0] = 0;
        kcalSync.syncAll(clock.instant());
        assertEquals(List.of("2026-09-28=2000", "2026-09-29=2000"), entries(detail(id, TORBEN_APP)),
                "nichts gegessen: kein Eintrag");

        food.days = (person, day) -> {
            throw new SourceUnavailableException("Kalorienzähler", 503);
        };
        assertEquals(0, kcalSync.syncAll(clock.instant()));
        assertEquals(2, entries(detail(id, TORBEN_APP)).size(), "Kalorienzaehler weg: nichts geht verloren");

        put("/cohabit/api/cohabits/" + id + "/settings/me", TORBEN_APP, map("healthConsent", false)).expect(200);
        food.days = (person, day) -> new FoodClient.Day(3000, 2300, Set.of());
        assertEquals(0, kcalSync.syncAll(clock.instant()), "widerrufen: nichts mehr holen");
    }

    @Test
    void zustimmenNurMitHealthyUndAppsSchickenKeineKcal() {
        Registered lena = register("Lena", "lena", FELIX);
        String id = kcalGoal();
        String code = post("/cohabit/api/cohabits/" + id + "/invite-link", FELIX, null).expect(200)
                .json().path("code").asString();
        post("/cohabit/api/invite-links/" + code + "/accept", lena.who(), null).expect(200);

        Response noHealthy = put("/cohabit/api/cohabits/" + id + "/settings/me", lena.who(), map("healthConsent", true));
        assertEquals(400, noHealthy.status());
        assertEquals("Dafür braucht es einen Healthy-Zugang.", noHealthy.message());

        put("/cohabit/api/cohabits/" + id + "/settings/me", TORBEN_APP, map("healthConsent", true)).expect(200);
        Response fromApp = put("/cohabit/api/cohabits/" + id + "/health/2026-09-30", TORBEN_APP, map("value", 2500));
        assertEquals(400, fromApp.status());
        assertEquals("Die kcal kommen aus Healthy.", fromApp.message());
    }

    @Test
    void auchInEinerChallengeZaehlenDieKcal() {
        food.days = (person, day) -> new FoodClient.Day(person.equals("felix") ? 2100 : 1900, 2300, Set.of());
        String id = withMembers(map("type", "CHALLENGE", "name", "Wer isst mehr?", "color", "butter",
                "health", map("metric", "KCAL"),
                "challenge", map("start", "2026-09-28", "end", "2026-10-04", "scoring", "HIGHEST_SUM")), TORBEN_APP);
        put("/cohabit/api/cohabits/" + id + "/settings/me", FELIX, map("healthConsent", true)).expect(200);
        put("/cohabit/api/cohabits/" + id + "/settings/me", TORBEN_APP, map("healthConsent", true)).expect(200);
        JsonNode board = detail(id, FELIX).path("challenge").path("leaderboard");
        assertEquals("felix", board.get(0).path("person").path("id").asString(), "Felix vorn mit 2.100");
        assertEquals(2100, board.get(0).path("score").asLong());
        assertEquals(1900, board.get(1).path("score").asLong());
    }
}
