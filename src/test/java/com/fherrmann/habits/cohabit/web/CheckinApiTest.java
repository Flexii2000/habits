package com.fherrmann.habits.cohabit.web;

import com.fherrmann.habits.cohabit.push.PushMessage;
import com.fherrmann.habits.cohabit.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckinApiTest extends ApiTestBase {

    private static final String UUID1 = "11111111-1111-1111-1111-111111111111";
    private static final String UUID2 = "22222222-2222-2222-2222-222222222222";

    @Test
    void abhakenEinmalProTagUndIdempotent() {
        get("/cohabit/api/me", TORBEN_APP).expect(200);
        post("/cohabit/api/devices", TORBEN_APP, map("token", "torben-phone", "platform", "android")).expect(204);
        String id = withMembers(streak("Laufen", daily()), TORBEN_APP);
        JsonNode r = checkin(id, FELIX, map("id", UUID1, "kind", "DONE"));
        assertEquals(UUID1, r.path("checkin").path("id").asString());
        assertEquals("2026-09-30", r.path("checkin").path("date").asString());
        assertEquals("MANUAL", r.path("checkin").path("source").asString());
        assertTrue(r.path("checkin").path("editable").asBoolean());
        JsonNode s = r.path("cohabit").path("summary");
        assertEquals("DONE", s.path("status").asString());
        assertEquals("Heute erledigt", s.path("checkInLabel").asString());
        assertFalse(s.path("canCheckIn").asBoolean());
        assertEquals("1", s.path("headline").path("value").asString());
        assertEquals("[\"felix\"]", s.path("doneTodayBy").toString());

        Response again = post("/cohabit/api/cohabits/" + id + "/checkins", FELIX, map("id", UUID1, "kind", "DONE"));
        assertEquals(200, again.status(), "dieselbe ID noch einmal: nichts doppelt");
        assertEquals(UUID1, again.json().path("checkin").path("id").asString());
        Response twice = post("/cohabit/api/cohabits/" + id + "/checkins", FELIX, map("id", UUID2));
        assertEquals(409, twice.status());
        assertEquals("Heute schon erledigt.", twice.message());
        assertEquals(409, post("/cohabit/api/cohabits/" + id + "/checkins", TORBEN_APP, map("id", UUID1)).status(),
                "eine fremde ID");

        List<PushMessage> pushes = androidTransport.to("torben-phone");
        assertEquals(1, pushes.size());
        assertEquals("checkin", pushes.getFirst().kind());
        assertEquals("Felix hat Laufen abgehakt", pushes.getFirst().title());
        assertEquals("cohabit://cohabit/" + id, pushes.getFirst().link());
        assertEquals("Serie 1 Tag", pushes.getFirst().body());
        store.read(data -> {
            assertEquals(1, data.events().events.size());
            assertEquals("CHECKIN", data.events().events.getFirst().kind.name());
            return null;
        });
        JsonNode torbenView = get("/cohabit/api/cohabits/" + id, TORBEN_APP).json().path("summary");
        assertEquals("Felix heute schon", torbenView.path("subline").asString());
    }

    @Test
    void nachtragenInnerhalbDerFrist() {
        String id = createDaysAgo(FELIX, streak("Laufen", daily()), 5);
        checkin(id, FELIX, map("id", UUID1, "date", "2026-09-29"));
        checkin(id, FELIX, map("id", UUID2, "date", "2026-09-28"));
        Response tooOld = post("/cohabit/api/cohabits/" + id + "/checkins", FELIX,
                map("id", "33333333-3333-3333-3333-333333333333", "date", "2026-09-27"));
        assertEquals(400, tooOld.status());
        assertEquals("Außerhalb der Nachtragsfrist.", tooOld.message());
        assertEquals(400, post("/cohabit/api/cohabits/" + id + "/checkins", FELIX,
                map("id", "44444444-4444-4444-4444-444444444444", "date", "2026-10-01")).status());
        JsonNode d = get("/cohabit/api/cohabits/" + id, FELIX).json();
        assertEquals("2026-09-28", d.path("backfillFrom").asString());
        assertEquals(2, d.path("myCheckins").size());
        assertEquals("2026-09-29", d.path("myCheckins").get(0).path("date").asString(), "neueste zuerst");
        assertEquals(2, d.path("streak").path("current").asInt());
        assertTrue(d.path("streak").path("atRisk").asBoolean());
        assertEquals("heute noch offen", d.path("streak").path("remainingText").asString());
        // Die Frist laeuft mit der Uhr: in drei Tagen ist der 29. nicht mehr bearbeitbar.
        clock.advance(Duration.ofDays(3));
        assertEquals(400, put("/cohabit/api/cohabits/" + id + "/checkins/" + UUID1, FELIX, map("note", "x")).status());
    }

    @Test
    void beweisfotoPflichtUndChatPost() {
        get("/cohabit/api/me", TORBEN_APP).expect(200);
        post("/cohabit/api/devices", TORBEN_APP, map("token", "torben-phone", "platform", "android")).expect(204);
        Map<String, Object> config = streak("Laufen", daily());
        config.put("photoRequired", true);
        String id = withMembers(config, TORBEN_APP);
        JsonNode d = get("/cohabit/api/cohabits/" + id, FELIX).json();
        assertEquals("Beweisfoto & abhaken", d.path("summary").path("checkInLabel").asString());
        assertEquals("[\"Beweisfoto-Pflicht\",\"Nachtragen bis 48 h\",\"Europe/Berlin\"]", d.path("rules").toString());
        Response missing = post("/cohabit/api/cohabits/" + id + "/checkins", FELIX, map("id", UUID1));
        assertEquals(400, missing.status());
        assertEquals("Ein Beweisfoto ist Pflicht.", missing.message());
        String foreign = fakePhoto("torben");
        assertEquals(400, post("/cohabit/api/cohabits/" + id + "/checkins", FELIX,
                map("id", UUID1, "photoId", foreign)).status());
        String photo = fakePhoto("felix");
        JsonNode r = checkin(id, FELIX, map("id", UUID1, "photoId", photo, "caption", "Regenlauf zählt doppelt."));
        assertEquals(photo, r.path("checkin").path("photoId").asString());
        assertEquals(400, post("/cohabit/api/cohabits/" + id + "/checkins", TORBEN_APP,
                map("id", UUID2, "photoId", photo)).status(), "fremdes Foto");
        String used = fakePhoto("torben");
        store.update(tx -> tx.photosW().photos.stream().filter(p -> p.id.equals(used)).findFirst().orElseThrow()
                .cohabitId = "c-anderes");
        assertEquals(409, post("/cohabit/api/cohabits/" + id + "/checkins", TORBEN_APP,
                map("id", UUID2, "photoId", used)).status(), "schon verwendet");
        store.read(data -> {
            var post = data.messages(id).stream().filter(m -> m.kind.name().equals("CHECKIN")).findFirst().orElseThrow();
            assertEquals(UUID1, post.checkinId);
            assertEquals("felix", post.authorId);
            assertNotNull(post.eventId);
            var event = data.events().events.stream().filter(e -> e.id.equals(post.eventId)).findFirst().orElseThrow();
            assertEquals("PHOTO_CHECKIN", event.kind.name());
            assertEquals("Regenlauf zählt doppelt.", event.caption);
            assertEquals(id, data.photos().photos.stream().filter(p -> p.id.equals(photo)).findFirst().orElseThrow().cohabitId);
            return null;
        });
        PushMessage push = androidTransport.to("torben-phone").getLast();
        assertEquals("photo", push.kind());
        assertEquals("Regenlauf zählt doppelt.", push.body());
        assertEquals("cohabit://cohabit/" + id + "/chat", push.link());
        assertEquals(1, get("/cohabit/api/cohabits/" + id, TORBEN_APP).json().path("unreadMessages").asInt());
    }

    @Test
    void werteWoSieGebrauchtWerden() {
        String goal = id(create(FELIX, map("type", "GOAL", "name", "100k", "color", "periwinkle",
                "tracking", map("mode", "VALUE", "unit", "STEPS"),
                "goal", map("target", 100000, "deadline", "2026-10-31", "counting", "AMOUNT", "mode", "TEAM"))));
        Response missing = post("/cohabit/api/cohabits/" + goal + "/checkins", FELIX, map("id", UUID1));
        assertEquals(400, missing.status());
        assertEquals("Der Wert fehlt.", missing.message());
        JsonNode r = checkin(goal, FELIX, map("id", UUID1, "value", 8200));
        assertEquals(8200, r.path("checkin").path("value").asInt());
        assertEquals("8.200 Schritte", r.path("checkin").path("valueText").asString());
        JsonNode g = r.path("cohabit").path("goal");
        assertEquals("8.200 von 100.000", g.path("totalText").asString());
        assertEquals(8, g.path("percent").asInt());
        // 1 von 32 Tagen verstrichen: Soll 3.125, Ist 8.200.
        assertEquals(5075, g.path("planDelta").asInt());
        assertEquals("+5.075 vor Plan", g.path("planDeltaText").asString());
        assertEquals("8%", r.path("cohabit").path("summary").path("headline").path("value").asString());
        checkin(goal, FELIX, map("id", UUID2, "value", 1000));
        assertEquals(9200, get("/cohabit/api/cohabits/" + goal, FELIX).json().path("goal").path("total").asInt(),
                "beliebig viele Eintraege je Tag");
    }

    @Test
    void abstinenzNurUnterbrechungenUndNurGeteiltSichtbar() {
        String id = id(create(FELIX, map("type", "ABSTINENCE", "name", "Ohne Zucker", "color", "mint")));
        assertEquals(400, post("/cohabit/api/cohabits/" + id + "/checkins", FELIX, map("id", UUID1, "kind", "DONE")).status());
        JsonNode r = checkin(id, FELIX, map("id", UUID1, "kind", "BREAK"));
        assertEquals("BREAK", r.path("checkin").path("kind").asString());
        assertEquals(0, r.path("cohabit").path("abstinence").path("currentDays").asInt());
        assertFalse(r.path("cohabit").path("summary").path("canCheckIn").asBoolean());
        store.read(data -> {
            assertTrue(data.events().events.isEmpty(), "ohne Freigabe keine Timeline");
            assertTrue(data.messages(id).isEmpty());
            return null;
        });
        String other = createDaysAgo(FELIX, map("type", "ABSTINENCE", "name", "Morgens dehnen", "color", "mint"), 3);
        put("/cohabit/api/cohabits/" + other + "/settings/me", FELIX, map("shareBreaks", true)).expect(200);
        checkin(other, FELIX, map("id", UUID2, "kind", "BREAK", "date", "2026-09-29"));
        store.read(data -> {
            assertEquals(1, data.events().events.stream().filter(e -> e.kind.name().equals("BREAK")).count());
            assertEquals("Felix hat eine Unterbrechung eingetragen", data.messages(other).getLast().systemText);
            return null;
        });
    }

    @Test
    void automatischeCoHabitsLassenSichNichtAbhaken() {
        Map<String, Object> food = streak("Track food", daily());
        food.put("auto", map("source", "FOOD"));
        String id = id(create(FELIX, food));
        Response r = post("/cohabit/api/cohabits/" + id + "/checkins", FELIX, map("id", UUID1));
        assertEquals(403, r.status());
        assertEquals("Dieses Co-Habit wird automatisch erfasst.", r.message());
    }

    @Test
    void eigeneEintraegeBearbeitenUndLoeschen() {
        Registered lena = register("Lena", "lena", FELIX);
        Map<String, Object> config = streak("Laufen", daily());
        config.put("tracking", map("mode", "VALUE", "unit", "KM"));
        config.put("photoRequired", true);
        String id = withMembers(config, lena.who());
        String photo = fakePhoto("felix");
        checkin(id, FELIX, map("id", UUID1, "value", 5, "photoId", photo, "caption", "alt"));
        JsonNode edited = put("/cohabit/api/cohabits/" + id + "/checkins/" + UUID1, FELIX,
                map("value", 5.25, "note", "Regen", "caption", "neu")).expect(200).json();
        assertEquals(5.25, edited.path("checkin").path("value").asDouble(), 1e-9);
        assertEquals("5,25 km", edited.path("checkin").path("valueText").asString());
        assertEquals("Regen", edited.path("checkin").path("note").asString());
        assertEquals(403, put("/cohabit/api/cohabits/" + id + "/checkins/" + UUID1, lena.who(), map("note", "x")).status());
        JsonNode after = delete("/cohabit/api/cohabits/" + id + "/checkins/" + UUID1, FELIX).expect(200).json();
        assertEquals(0, after.path("myCheckins").size());
        store.read(data -> {
            assertTrue(data.checkins(id).isEmpty());
            assertTrue(data.events().events.isEmpty());
            assertTrue(data.messages(id).stream().noneMatch(m -> m.kind.name().equals("CHECKIN")));
            assertTrue(data.photos().photos.isEmpty(), "das Foto geht mit");
            return null;
        });
    }

    @Test
    void neueBestserieUndMeilenstein() {
        Registered lena = register("Lena", "lena", FELIX);
        post("/cohabit/api/devices", lena.who(), map("token", "lena-phone", "platform", "ios")).expect(204);
        Map<String, Object> config = streak("Laufen", daily());
        config.put("backfillHours", 336);
        String id = createDaysAgo(FELIX, config, 10, lena.who());
        for (int i = 7; i >= 1; i--) {
            checkin(id, FELIX, map("id", "0000000" + i + "-0000-0000-0000-000000000000",
                    "date", clock.instant().atZone(java.time.ZoneId.of("Europe/Berlin")).toLocalDate().minusDays(i).toString()));
        }
        List<String> texts = store.read(data -> data.messages(id).stream().map(m -> m.systemText).toList());
        // Von alt nach neu nachgetragen: die laufende Serie entsteht erst mit dem gestrigen Tag - dann gleich mit 7.
        assertTrue(texts.contains("Felix hat eine neue Bestserie: 7 Tage"), texts.toString());
        assertEquals(1, texts.stream().filter(t -> t != null && t.contains("Bestserie")).count(), "einmal je Serie");
        assertTrue(texts.contains("Felix hat 7 Tage geschafft"));
        store.read(data -> {
            assertEquals(1, data.events().events.stream().filter(e -> e.kind.name().equals("MILESTONE")).count());
            assertEquals(1, data.events().events.stream().filter(e -> e.kind.name().equals("NEW_BEST")).count());
            return null;
        });
        assertTrue(iosTransport.to("lena-phone").stream().anyMatch(p -> p.kind().equals("milestone")
                && p.title().equals("Felix hat 7 Tage geschafft") && p.link().equals("cohabit://timeline")));
        JsonNode d = get("/cohabit/api/cohabits/" + id, FELIX).json();
        assertEquals(7, d.path("streak").path("record").path("value").asInt());
        assertEquals("7 T", d.path("streak").path("record").path("short").asString());
        assertEquals("felix", d.path("streak").path("record").path("person").path("id").asString());
    }

    @Test
    void bestserieEinmalJeSerieUndWiederNachEinemRiss() {
        String id = id(create(FELIX, streak("Lesen", daily())));
        int n = 0;
        // Tag 1-3 abgehakt, Tag 4 verpasst, Tag 5-8 abgehakt.
        boolean[] plan = {true, true, true, false, true, true, true, true};
        for (boolean doIt : plan) {
            if (doIt) {
                checkin(id, FELIX, map("id", String.format("%08d-0000-0000-0000-000000000000", n)));
            }
            n++;
            clock.advance(Duration.ofDays(1));
        }
        List<String> bests = store.read(data -> data.messages(id).stream().map(m -> m.systemText)
                .filter(t -> t != null && t.contains("Bestserie")).toList());
        assertEquals(List.of("Felix hat eine neue Bestserie: 2 Tage", "Felix hat eine neue Bestserie: 4 Tage"), bests,
                "erste Serie bei 2, die zweite erst, als sie die 3 uebertraf");
    }

    @Test
    void werZuerstDasZielErreichtBeendetDieRundeSofort() {
        Registered lena = register("Lena", "lena", FELIX);
        post("/cohabit/api/devices", lena.who(), map("token", "lena-phone", "platform", "ios")).expect(204);
        String id = withMembers(map("type", "CHALLENGE", "name", "Erster bei 20 km", "color", "butter",
                "tracking", map("mode", "VALUE", "unit", "KM"),
                "challenge", map("start", "2026-09-28", "end", "2026-10-04", "scoring", "FIRST_TO_TARGET",
                        "target", 20, "stake", "Verlierer kocht für alle", "recurrence", "WEEKLY")), lena.who());
        checkin(id, lena.who(), map("id", UUID2, "value", 12));
        JsonNode r = checkin(id, FELIX, map("id", UUID1, "value", 21));
        JsonNode ch = r.path("cohabit").path("challenge");
        assertEquals(2, ch.path("round").asInt(), "die naechste Runde steht schon");
        assertEquals("2026-10-05", ch.path("start").asString());
        assertEquals("2026-10-11", ch.path("end").asString());
        assertEquals(1, ch.path("pastRounds").size());
        assertEquals("felix", ch.path("pastRounds").get(0).path("winners").get(0).path("id").asString());
        assertFalse(ch.path("finished").asBoolean(), "wiederkehrend - es geht weiter");
        assertEquals("startet am 05.10.", ch.path("endsInText").asString());

        JsonNode dialog = get("/cohabit/api/cohabits/" + id, lena.who()).json().path("dialog");
        assertEquals("challenge-1", dialog.path("id").asString());
        assertEquals("CHALLENGE", dialog.path("kind").asString());
        assertEquals("Felix gewinnt „Erster bei 20 km“", dialog.path("title").asString());
        assertEquals(2, dialog.path("podium").size());
        assertEquals("21 km", dialog.path("podium").get(0).path("scoreText").asString());
        assertEquals("Verlierer kocht für alle: Lena ist dran.", dialog.path("stakeText").asString());
        assertEquals("Die nächste Runde startet am 5. Oktober automatisch.", dialog.path("nextText").asString());
        assertTrue(dialog.path("reactionTarget").asString().startsWith("message:"));
        PushMessage ended = iosTransport.to("lena-phone").stream().filter(p -> p.kind().equals("challenge-ended"))
                .findFirst().orElseThrow();
        assertEquals("Du bist auf Platz 2. Verlierer kocht für alle: du bist dran.", ended.body());

        Response closed = post("/cohabit/api/cohabits/" + id + "/checkins", lena.who(),
                map("id", "33333333-3333-3333-3333-333333333333", "value", 30));
        assertEquals(400, closed.status());
        assertEquals("Die Challenge läuft gerade nicht.", closed.message());
        post("/cohabit/api/cohabits/" + id + "/dialogs/challenge-1/seen", lena.who(), null).expect(204);
        assertTrue(get("/cohabit/api/cohabits/" + id, lena.who()).json().path("dialog").isNull());
        assertEquals(1, get("/cohabit/api/me", FELIX).json().path("counts").path("wins").asInt());
        assertEquals(0, get("/cohabit/api/me", lena.who()).json().path("counts").path("wins").asInt());
    }

    @Test
    void healthWerteLegenJeTagEinenEintragAnUndAktualisierenIhn() {
        String id = createDaysAgo(FELIX, map("type", "GOAL", "name", "100k Schritte", "color", "periwinkle",
                "health", map("metric", "STEPS"),
                "goal", map("target", 100000, "deadline", "2026-10-31", "counting", "AMOUNT", "mode", "TEAM")), 3);
        JsonNode d = get("/cohabit/api/cohabits/" + id, FELIX).json();
        assertEquals("{\"mode\":\"VALUE\",\"unit\":\"STEPS\"}", d.path("config").path("tracking").toString());
        assertEquals("Schritte", d.path("health").path("label").asString());
        assertEquals("nur die Schrittzahl wird geteilt", d.path("health").path("shareText").asString());
        assertFalse(d.path("health").path("consent").asBoolean());
        assertEquals(403, put("/cohabit/api/cohabits/" + id + "/health/2026-09-30", FELIX, map("value", 8200)).status());
        put("/cohabit/api/cohabits/" + id + "/settings/me", FELIX, map("healthConsent", true)).expect(200);
        put("/cohabit/api/cohabits/" + id + "/health/2026-09-30", FELIX, map("value", 8200)).expect(200);
        JsonNode after = put("/cohabit/api/cohabits/" + id + "/health/2026-09-30", FELIX, map("value", 9100))
                .expect(200).json();
        assertEquals(9100, after.path("goal").path("total").asInt());
        assertEquals("2026-09-30T08:00:00Z", after.path("health").path("lastSyncAt").asString());
        store.read(data -> {
            assertEquals(1, data.checkins(id).size(), "aktualisiert, nicht verdoppelt");
            assertEquals("HEALTH", data.checkins(id).getFirst().source.name());
            assertEquals(1, data.events().events.size());
            assertEquals(9100.0, data.events().events.getFirst().value);
            return null;
        });
        put("/cohabit/api/cohabits/" + id + "/health/2026-09-29", FELIX, map("value", 4000)).expect(200);
        assertEquals(400, put("/cohabit/api/cohabits/" + id + "/health/2026-09-20", FELIX, map("value", 1)).status());
        put("/cohabit/api/cohabits/" + id + "/health/2026-09-29", FELIX, map("value", 0)).expect(200);
        assertEquals(9100, get("/cohabit/api/cohabits/" + id, FELIX).json().path("goal").path("total").asInt());
        assertNull(store.read(data -> data.checkins(id).stream().filter(c -> c.date.toString().equals("2026-09-29"))
                .findFirst().orElse(null)));
        assertEquals(400, put("/cohabit/api/cohabits/" + id + "/checkins/" + store.read(data ->
                data.checkins(id).getFirst().id), FELIX, map("value", 1)).status(), "Health-Werte kommen aus der App");
    }
}
