package com.fherrmann.habits.cohabit.web;

import com.fherrmann.habits.cohabit.push.PushMessage;
import com.fherrmann.habits.cohabit.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Laufpunkte: Basis je Tag, volle km, volle Minuten-Bloecke, Pace-Grenze. */
class RunPointsApiTest extends ApiTestBase {

    private static final String RUN1 = "11111111-1111-1111-1111-111111111111";
    private static final String RUN2 = "22222222-2222-2222-2222-222222222222";
    private static final String RUN3 = "33333333-3333-3333-3333-333333333333";
    private static final String RUN4 = "44444444-4444-4444-4444-444444444444";

    private static Map<String, Object> runChallenge(Object run) {
        return map("type", "CHALLENGE", "name", "Laufen", "color", "butter", "photoRequired", false,
                "tracking", map("mode", "VALUE", "unit", "KM"),
                "challenge", map("start", "2026-09-28", "end", "2026-10-31", "scoring", "RUN_POINTS",
                        "stake", "Döner", "run", run));
    }

    private static Map<String, Object> run(String id, Object minutes, Object km) {
        return map("id", id, "durationMinutes", minutes, "distanceKm", km);
    }

    private Response postRun(String cohabitId, Who who, Map<String, Object> body) {
        return post("/cohabit/api/cohabits/" + cohabitId + "/checkins", who, body);
    }

    @Test
    void anlegenMitVorgabenUndEigenemEintragsblatt() {
        JsonNode d = create(FELIX, runChallenge(null));
        JsonNode ch = d.path("config").path("challenge");
        assertEquals("RUN_POINTS", ch.path("scoring").asString());
        assertEquals("{\"basePoints\":10,\"pointsPerKm\":1,\"minutesPerPoint\":6,\"baseMinMinutes\":20,"
                + "\"paceLimitSeconds\":480}", ch.path("run").toString());
        assertEquals("{\"mode\":\"CHECK\"}", d.path("config").path("tracking").toString(),
                "ein Lauf hat Dauer und Distanz statt eines Werts");
        assertEquals("Laufpunkte", d.path("challenge").path("scoringText").asString());
        assertEquals("0 P", d.path("challenge").path("leaderboard").get(0).path("scoreText").asString());
        JsonNode s = d.path("summary");
        assertTrue(s.path("runEntry").asBoolean());
        assertEquals("Lauf eintragen", s.path("checkInLabel").asString());
        assertTrue(s.path("valueUnit").isNull());
        assertEquals(List.of("Basis 10 P ab 20 Min.", "1 P je km", "1 P je 6 Min.", "Pace unter 8:00 min/km",
                "Einsatz: Döner"), d.path("rules").valueStream().map(JsonNode::asString).toList().subList(0, 5));

        JsonNode widget = get("/cohabit/api/widget", FELIX).json().path("cohabits").get(0);
        assertFalse(widget.path("quickCheckIn").asBoolean(), "das Widget kennt weder Dauer noch Distanz");

        JsonNode streak = create(FELIX, streak("Abends", daily()));
        assertFalse(streak.path("summary").path("runEntry").asBoolean());
    }

    @Test
    void eigeneGewichteUndPruefung() {
        JsonNode d = create(FELIX, runChallenge(map("basePoints", 0, "pointsPerKm", 2, "minutesPerPoint", 10,
                "baseMinMinutes", 0, "paceLimitSeconds", 420)));
        assertEquals(List.of("2 P je km", "1 P je 10 Min.", "Pace unter 7:00 min/km"),
                d.path("rules").valueStream().map(JsonNode::asString).toList().subList(0, 3),
                "ohne Basis kein Basis-Chip");
        Response zero = post("/cohabit/api/cohabits", FELIX, runChallenge(map("minutesPerPoint", 0)));
        assertEquals(400, zero.status());
        assertEquals("Ein Punkt je 1 bis 600 Minuten.", zero.message());
        Response pace = post("/cohabit/api/cohabits", FELIX, runChallenge(map("paceLimitSeconds", 30)));
        assertEquals("Die Pace-Grenze muss zwischen 1:00 und 60:00 min/km liegen.", pace.message());
        Map<String, Object> withHealth = runChallenge(null);
        withHealth.put("health", map("metric", "RUNNING_DISTANCE"));
        Response health = post("/cohabit/api/cohabits", FELIX, withHealth);
        assertEquals(400, health.status());
        assertEquals("Laufpunkte trägt man von Hand ein.", health.message());
    }

    @Test
    void basisEinmalJeTagVolleKmUndVolleSechsMinuten() {
        post("/cohabit/api/devices", TORBEN_APP, map("token", "torben-phone", "platform", "android")).expect(204);
        String id = createDaysAgo(FELIX, runChallenge(null), 2, TORBEN_APP);

        JsonNode first = checkin(id, FELIX, run(RUN1, 35, 5.8)).path("checkin");
        JsonNode r = first.path("run");
        assertEquals(35, r.path("durationMinutes").asInt());
        assertEquals(5.8, r.path("distanceKm").asDouble());
        assertEquals("6:02 min/km", r.path("paceText").asString());
        assertEquals(20, r.path("points").asInt(), "10 Basis + 5 volle km + 5 volle 6 Minuten");
        assertEquals("+20 P", r.path("pointsText").asString());
        assertEquals("Basis 10 · Distanz 5 · Dauer 5", r.path("breakdownText").asString());
        assertEquals("5,8 km · 35 Min. · 6:02 min/km · +20 P", first.path("valueText").asString());

        JsonNode second = checkin(id, FELIX, run(RUN2, 25, 4)).path("checkin").path("run");
        assertEquals(8, second.path("points").asInt(), "Zweitlauf: nur km- und Zeitpunkte");
        assertEquals("Distanz 4 · Dauer 4 · Basis heute schon vergeben", second.path("breakdownText").asString());

        JsonNode shortRun = checkin(id, FELIX, map("id", RUN3, "date", "2026-09-29", "durationMinutes", 15,
                "distanceKm", 2.5)).path("checkin").path("run");
        assertEquals(4, shortRun.path("points").asInt());
        assertEquals("Distanz 2 · Dauer 2 · Basis erst ab 20 Min.", shortRun.path("breakdownText").asString());
        JsonNode later = checkin(id, FELIX, map("id", RUN4, "date", "2026-09-29", "durationMinutes", 22,
                "distanceKm", 3)).path("checkin").path("run");
        assertEquals(16, later.path("points").asInt(), "der erste lange Lauf des Tages bekommt die Basis");

        JsonNode torben = checkin(id, TORBEN_APP, run("55555555-5555-5555-5555-555555555555", 30, 6));
        JsonNode board = torben.path("cohabit").path("challenge").path("leaderboard");
        assertEquals("felix", board.get(0).path("person").path("id").asString());
        assertEquals(48, board.get(0).path("score").asInt());
        assertEquals("48 P", board.get(0).path("scoreText").asString());
        assertEquals("21 P", board.get(1).path("scoreText").asString());
        assertEquals("noch 27 P bis Felix", torben.path("cohabit").path("summary").path("rank").path("gapText").asString());

        PushMessage push = androidTransport.to("torben-phone").getFirst();
        assertEquals("Felix ist 5,8 km in 35 Min. gelaufen · +20 P", push.title());
        JsonNode items = get("/cohabit/api/timeline", TORBEN_APP).json().path("items");
        List<String> titles = items.valueStream().map(i -> i.path("title").asString()).toList();
        assertTrue(titles.contains("Felix ist 4 km in 25 Min. gelaufen · +8 P"), titles.toString());

        delete("/cohabit/api/cohabits/" + id + "/checkins/" + RUN1, FELIX).expect(200);
        JsonNode mine = get("/cohabit/api/cohabits/" + id, FELIX).json().path("myCheckins");
        JsonNode moved = mine.valueStream().filter(c -> c.path("id").asString().equals(RUN2)).findFirst().orElseThrow();
        assertEquals("+18 P", moved.path("run").path("pointsText").asString(), "die Basis wandert zum naechsten Lauf");
    }

    @Test
    void zuLangsamZaehltNichtUndPflichtfelder() {
        String id = id(create(FELIX, runChallenge(null)));
        Response slow = postRun(id, FELIX, run(RUN1, 40, 5));
        assertEquals(400, slow.status());
        assertEquals("Ø-Pace 8:00 min/km – zählt nur unter 8:00 min/km.", slow.message(), "genau auf der Grenze");
        assertEquals("Die Dauer fehlt.", postRun(id, FELIX, map("id", RUN1, "distanceKm", 5)).message());
        assertEquals("Die Distanz fehlt.", postRun(id, FELIX, map("id", RUN1, "durationMinutes", 30)).message());
        assertEquals("Die Distanz muss zwischen 0,01 und 500 km liegen.",
                postRun(id, FELIX, run(RUN1, 30, 0)).message());
        assertEquals("Die Dauer muss zwischen 1 und 1.440 Minuten liegen.",
                postRun(id, FELIX, run(RUN1, 0, 5)).message());
        store.read(data -> {
            assertTrue(data.checkins(id).isEmpty(), "nichts gespeichert");
            return null;
        });
        checkin(id, FELIX, run(RUN1, 39, 5));
    }

    @Test
    void bearbeitenPrueftDiePaceUndLaesstFehlendesStehen() {
        String id = id(create(FELIX, runChallenge(null)));
        checkin(id, FELIX, run(RUN1, 30, 5));
        JsonNode edited = put("/cohabit/api/cohabits/" + id + "/checkins/" + RUN1, FELIX,
                map("distanceKm", 6.2, "caption", "Regen")).expect(200).json().path("checkin");
        assertEquals(30, edited.path("run").path("durationMinutes").asInt(), "null heisst unveraendert");
        assertEquals("+21 P", edited.path("run").path("pointsText").asString());
        assertEquals("Regen", edited.path("caption").asString());
        Response slow = put("/cohabit/api/cohabits/" + id + "/checkins/" + RUN1, FELIX, map("durationMinutes", 60));
        assertEquals(400, slow.status());
        assertEquals("Ø-Pace 9:41 min/km – zählt nur unter 8:00 min/km.", slow.message());
    }

    @Test
    void gewichteEinerLaufendenRundeBleiben() {
        JsonNode d = create(FELIX, runChallenge(null));
        Map<String, Object> changed = runChallenge(map("basePoints", 20));
        changed.remove("type");
        Response r = put("/cohabit/api/cohabits/" + id(d), FELIX, changed);
        assertEquals(400, r.status());
        assertEquals("Die Wertung einer laufenden Runde lässt sich nicht ändern.", r.message());
        Map<String, Object> same = runChallenge(null);
        same.remove("type");
        put("/cohabit/api/cohabits/" + id(d), FELIX, same).expect(200);
    }
}
