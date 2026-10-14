package com.fherrmann.habits.cohabit.web;

import com.fherrmann.habits.cohabit.service.GymSyncAccess;
import com.fherrmann.habits.cohabit.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gym aus Healthy (Felix, 14.10.): eine Serie "2× pro Woche", die abgehakt wird, sobald im
 * Gym-Tab ein Training beendet ist - und von Hand weiterhin. Heute ist Mittwoch, der
 * 30.09.2026; die Nachtragsfrist ist 48 Stunden.
 */
class GymApiTest extends ApiTestBase {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 30);

    private static Map<String, Object> gymHabit() {
        Map<String, Object> config = streak("Gym", map("kind", "TIMES_PER_WEEK", "times", 2));
        config.put("health", map("metric", "GYM"));
        return config;
    }

    private JsonNode detail(String id, Who who) {
        GymSyncAccess.forget(gymSync);
        return get("/cohabit/api/cohabits/" + id, who).expect(200).json();
    }

    /** Die eigenen Eintraege als "Datum/Quelle", aufsteigend. */
    private static List<String> entries(JsonNode detail) {
        List<String> list = new ArrayList<>();
        detail.path("myCheckins").forEach(c -> list.add(c.path("date").asString() + "/" + c.path("source").asString()));
        list.sort(String::compareTo);
        return list;
    }

    private Response tick(String id, Who who, LocalDate day) {
        return post("/cohabit/api/cohabits/" + id + "/checkins", who,
                map("id", UUID.randomUUID().toString(), "kind", "DONE", "date", day.toString()));
    }

    @Test
    void anlegenHaktBeendeteTrainingsAbUndStimmtFuerDieAnlegerinZu() {
        gym.train("felix", TODAY.minusDays(3));
        gym.train("felix", TODAY.minusDays(2));
        gym.train("felix", TODAY);
        String id = createDaysAgo(FELIX, gymHabit(), 3);

        JsonNode d = detail(id, FELIX);
        assertEquals("GYM", d.path("health").path("metric").asString());
        assertEquals("Gym aus Healthy", d.path("health").path("label").asString());
        assertEquals("HEALTHY", d.path("health").path("source").asString());
        assertEquals("nur die Tage mit beendetem Training werden geteilt", d.path("health").path("shareText").asString());
        assertTrue(d.path("health").path("consent").asBoolean(), "die Wahl der Quelle ist die Einwilligung");
        assertEquals("CHECK", d.path("config").path("tracking").path("mode").asString(), "ein Haken, keine Menge");
        assertEquals(List.of("2026-09-28/HEALTH", "2026-09-30/HEALTH"), entries(d), "die Frist bis heute");
        assertEquals(List.of("2026-09-27=1", "2026-09-28=1", "2026-09-29=0", "2026-09-30=1"), series(id),
                "der Starttag kam schon beim Anlegen");
        d.path("myCheckins").forEach(c -> {
            assertTrue(c.path("value").isNull() || c.path("value").isMissingNode(), "ohne Wert");
            assertFalse(c.path("editable").asBoolean(), "was aus Healthy kommt, nimmt nur Healthy zurueck");
        });
    }

    @Test
    void vonHandAbhakenGehtWeiterOhneDoppeltenEintrag() {
        String id = createDaysAgo(FELIX, gymHabit(), 2);
        assertEquals(201, tick(id, FELIX, TODAY.minusDays(1)).status(), "ohne Training von Hand");

        gym.train("felix", TODAY.minusDays(1));
        gym.train("felix", TODAY);
        assertEquals(List.of("2026-09-29/MANUAL", "2026-09-30/HEALTH"), entries(detail(id, FELIX)),
                "von Hand abgehakt: kein zweiter Eintrag aus Healthy");
        assertEquals(409, tick(id, FELIX, TODAY).status(), "aus Healthy schon abgehakt");

        // Das Training von heute gibt es nicht mehr: nur der Haken aus Healthy geht.
        gym.trained.get("felix").remove(TODAY);
        gym.trained.get("felix").remove(TODAY.minusDays(1));
        assertEquals(1, gymSync.syncAll(clock.instant()));
        assertEquals(List.of("2026-09-29/MANUAL"), entries(detail(id, FELIX)));
    }

    @Test
    void derSchedulerHolt_undBeimOeffnenHoechstensEinmalJeMinute() {
        String id = createDaysAgo(FELIX, gymHabit(), 1);
        assertTrue(entries(detail(id, FELIX)).isEmpty());

        gym.train("felix", TODAY);
        assertEquals(1, gymSync.syncAll(clock.instant()));
        assertEquals(0, gymSync.syncAll(clock.instant()), "unveraendert: nichts schreiben");

        GymSyncAccess.forget(gymSync);
        int before = gym.calls.get();
        get("/cohabit/api/today", FELIX).expect(200);
        get("/cohabit/api/cohabits", FELIX).expect(200);
        assertEquals(before + 1, gym.calls.get(), "zweimal geoeffnet, einmal gefragt");
        clock.advance(Duration.ofSeconds(61));
        get("/cohabit/api/today", FELIX).expect(200);
        assertEquals(before + 2, gym.calls.get());
    }

    @Test
    void weightTrackerWegNichtsGehtVerlorenUndDieAnsichtKommt() {
        gym.train("felix", TODAY);
        String id = createDaysAgo(FELIX, gymHabit(), 1);
        assertEquals(List.of("2026-09-30/HEALTH"), entries(detail(id, FELIX)));

        gym.unavailable = true;
        assertEquals(0, gymSync.syncAll(clock.instant()));
        assertEquals(List.of("2026-09-30/HEALTH"), entries(detail(id, FELIX)), "Ansicht trotzdem, Haken bleibt");
    }

    @Test
    void gymNurAlsSerieUndDieAppsSchickenKeineTrainings() {
        Response goal = post("/cohabit/api/cohabits", FELIX, map("type", "GOAL", "name", "Trainings", "color", "peach",
                "health", map("metric", "GYM"),
                "goal", map("target", 20, "deadline", "2026-12-31", "counting", "ENTRIES", "mode", "TEAM")));
        assertEquals(400, goal.status());
        assertEquals("Gym aus Healthy gibt es nur als Serie.", goal.message());

        String id = createDaysAgo(FELIX, gymHabit(), 1);
        Response fromApp = put("/cohabit/api/cohabits/" + id + "/health/2026-09-30", FELIX, map("value", 1));
        assertEquals(400, fromApp.status());
        assertEquals("Die Trainings kommen aus Healthy.", fromApp.message());
    }

    @Test
    void ohneHealthyKeinAbgleichUndKeineEinwilligung() {
        Registered lena = register("Lena", "lena", FELIX);
        gym.train("lena", TODAY);
        JsonNode created = create(lena.who(), gymHabit());
        String id = id(created);
        JsonNode d = detail(id, lena.who());
        assertFalse(d.path("health").path("consent").asBoolean());
        assertTrue(entries(d).isEmpty());

        Response consent = put("/cohabit/api/cohabits/" + id + "/settings/me", lena.who(), map("healthConsent", true));
        assertEquals(400, consent.status());
        assertEquals("Dafür braucht es einen Healthy-Zugang.", consent.message());
    }

    @Test
    void wohlAberFuerTorbenNachSeinerEinwilligung() {
        String id = createDaysAgo(FELIX, gymHabit(), 1, TORBEN_APP);
        gym.train("torben", TODAY);
        assertTrue(entries(detail(id, TORBEN_APP)).isEmpty(), "beigetreten, aber noch nicht zugestimmt");

        JsonNode after = put("/cohabit/api/cohabits/" + id + "/settings/me", TORBEN_APP, map("healthConsent", true))
                .expect(200).json();
        assertEquals(List.of("2026-09-30/HEALTH"), entries(after), "gleich nach der Zustimmung");
    }

    /** Fuer die Effekte in Healthy: ab dem Start jeder Tag Ja (1) oder Nein (0), egal woher der Haken kam. */
    @Test
    void dieTagesreiheIstJaNein() {
        String id = createDaysAgo(FELIX, gymHabit(), 2);
        gym.train("felix", TODAY);
        tick(id, FELIX, TODAY.minusDays(2)).expect(201);
        gymSync.syncAll(clock.instant());

        JsonNode series = seriesNode(id);
        assertEquals("BINARY", series.path("kind").asString());
        assertEquals("GYM", series.path("healthMetric").asString());
        assertEquals(List.of("2026-09-28=1", "2026-09-29=0", "2026-09-30=1"), series(id),
                "von Hand, nichts, aus Healthy");
    }

    private JsonNode seriesNode(String id) {
        GymSyncAccess.forget(gymSync);
        JsonNode days = get("/cohabit/api/me/days?from=2026-09-24&to=2026-09-30", FELIX).expect(200).json();
        for (JsonNode s : days.path("series")) {
            if (s.path("ref").path("id").asString().equals(id)) {
                return s;
            }
        }
        throw new AssertionError("keine Reihe fuer " + id);
    }

    private List<String> series(String id) {
        List<String> values = new ArrayList<>();
        seriesNode(id).path("days").forEach(v -> values.add(v.path("date").asString() + "=" + v.path("value").asInt()));
        return values;
    }
}
