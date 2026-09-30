package com.fherrmann.habits.cohabit.web;

import com.fherrmann.habits.client.FoodClient;
import com.fherrmann.habits.client.SourceUnavailableException;
import com.fherrmann.habits.cohabit.support.ApiTestBase;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverviewApiTest extends ApiTestBase {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 30);

    private Map<String, Object> challenge(String name) {
        return map("type", "CHALLENGE", "name", name, "color", "butter",
                "challenge", map("start", "2026-09-28", "end", "2026-10-04", "scoring", "MOST_ENTRIES",
                        "stake", null, "recurrence", "NONE"));
    }

    @Test
    void heuteZaehltOffeneHakenUndSortiert() {
        Registered lena = register("Lena", "lena", FELIX);
        String laufen = withMembers(streak("Laufen", daily()), lena.who());
        create(FELIX, map("type", "ABSTINENCE", "name", "Ohne Zucker", "color", "mint"));
        String kochen = withMembers(challenge("Wer kocht öfter?"), lena.who());
        create(FELIX, map("type", "GOAL", "name", "100k Schritte", "color", "periwinkle",
                "tracking", map("mode", "VALUE", "unit", "STEPS"),
                "goal", map("target", 100000, "deadline", "2026-10-31", "counting", "AMOUNT", "mode", "TEAM")));
        JsonNode t = get("/cohabit/api/today", FELIX).expect(200).json();
        assertEquals("2026-09-30", t.path("date").asString());
        assertEquals(2, t.path("openCount").asInt());
        assertEquals("Noch 2 Haken offen", t.path("headline").asString());
        assertEquals(4, t.path("cohabits").size());
        assertEquals("Laufen", t.path("cohabits").get(0).path("ref").path("name").asString(), "Offen heute, nach Name");
        assertEquals("Wer kocht öfter?", t.path("cohabits").get(1).path("ref").path("name").asString());
        assertEquals("RUNNING", t.path("cohabits").get(2).path("section").asString());
        assertEquals(0, t.path("nudges").size());
        assertEquals(0, t.path("newPhotos").path("count").asInt());

        checkin(laufen, FELIX, map("id", "11111111-1111-1111-1111-111111111111"));
        t = get("/cohabit/api/today", FELIX).json();
        assertEquals("Noch 1 Haken offen", t.path("headline").asString());
        assertEquals("OPEN_TODAY", t.path("cohabits").get(0).path("section").asString(), "erledigt bleibt oben");
        checkin(kochen, FELIX, map("id", "22222222-2222-2222-2222-222222222222"));
        assertEquals("Alles erledigt", get("/cohabit/api/today", FELIX).json().path("headline").asString());
        JsonNode lenas = get("/cohabit/api/today", lena.who()).json();
        assertEquals(2, lenas.path("openCount").asInt());
        JsonNode card = lenas.path("cohabits").get(1);
        assertEquals("noch 1 bis Felix · endet in 4 Tagen", card.path("subline").asString());
        assertEquals(2, card.path("rank").path("mine").asInt());
        assertEquals(2, card.path("rank").path("of").asInt());
    }

    @Test
    void einladungenUndNeueBeweisfotosAufHeute() {
        Registered lena = register("Lena", "lena", FELIX);
        Map<String, Object> config = streak("Laufen", daily());
        config.put("photoRequired", true);
        String laufen = withMembers(config, lena.who());
        String photo = fakePhoto(lena.id());
        checkin(laufen, lena.who(), map("id", "11111111-1111-1111-1111-111111111111", "photoId", photo));
        JsonNode t = get("/cohabit/api/today", FELIX).json();
        assertEquals(1, t.path("newPhotos").path("count").asInt());
        assertEquals(photo, t.path("newPhotos").path("photoIds").get(0).asString());
        assertEquals(0, get("/cohabit/api/today", lena.who()).json().path("newPhotos").path("count").asInt(),
                "eigene Fotos sind nicht neu");
        String other = id(create(FELIX, streak("Lesen", daily())));
        post("/cohabit/api/cohabits/" + other + "/invitations", FELIX, map("personIds", java.util.List.of(lena.id())))
                .expect(200);
        JsonNode invitations = get("/cohabit/api/today", lena.who()).json().path("invitations");
        assertEquals(1, invitations.size());
        assertEquals("Lesen", invitations.get(0).path("cohabit").path("ref").path("name").asString());
        assertEquals("felix", invitations.get(0).path("from").path("id").asString());
    }

    @Test
    void widgetDaten() {
        Registered lena = register("Lena", "lena", FELIX);
        withMembers(streak("Laufen", daily()), lena.who());
        Map<String, Object> photo = streak("Kraft", map("kind", "TIMES_PER_WEEK", "times", 3));
        photo.put("photoRequired", true);
        create(FELIX, photo);
        create(FELIX, map("type", "ABSTINENCE", "name", "Ohne Zucker", "color", "mint"));
        String kochen = withMembers(challenge("Wer kocht öfter?"), lena.who());
        checkin(kochen, lena.who(), map("id", "11111111-1111-1111-1111-111111111111"));
        create(FELIX, map("type", "GOAL", "name", "100k Schritte", "color", "periwinkle",
                "tracking", map("mode", "VALUE", "unit", "STEPS"),
                "goal", map("target", 100000, "deadline", "2026-10-31", "counting", "AMOUNT", "mode", "TEAM")));
        JsonNode w = get("/cohabit/api/widget", FELIX).expect(200).json();
        assertEquals("2026-09-30T08:00:00Z", w.path("generatedAt").asString());
        assertEquals(3, w.path("openCount").asInt());
        JsonNode laufen = find(w.path("cohabits"), "Laufen");
        assertEquals("OPEN", laufen.path("status").asString());
        assertEquals("offen", laufen.path("statusText").asString());
        assertTrue(laufen.path("quickCheckIn").asBoolean());
        assertEquals("Tage · heute noch offen", laufen.path("sub").asString());
        JsonNode kraft = find(w.path("cohabits"), "Kraft");
        assertFalse(kraft.path("quickCheckIn").asBoolean(), "Foto-Pflicht");
        assertTrue(kraft.path("photoRequired").asBoolean());
        assertEquals("Wochen · 0/3", kraft.path("sub").asString());
        JsonNode zucker = find(w.path("cohabits"), "Ohne Zucker");
        assertEquals("1 T", zucker.path("statusText").asString());
        assertEquals("Tage · Rekord 1", zucker.path("sub").asString());
        assertFalse(zucker.path("quickCheckIn").asBoolean(), "eine Unterbrechung nie per Tipp");
        assertEquals("Wer kocht öfter?", w.path("challenge").path("ref").path("name").asString());
        assertEquals("endet in 4 Tagen", w.path("challenge").path("endsText").asString());
        assertEquals(2, w.path("challenge").path("myRank").asInt());
        assertEquals("Lena", w.path("challenge").path("leaderboard").get(0).path("name").asString());
        assertFalse(w.path("challenge").path("leaderboard").get(0).path("me").asBoolean());
        assertTrue(w.path("challenge").path("leaderboard").get(1).path("me").asBoolean());
        assertEquals(0, w.path("teamGoal").path("percent").asInt());
        assertEquals("Kraft · 0 Wochen", w.path("openStreak").path("text").asString());
    }

    private static JsonNode find(JsonNode items, String name) {
        for (JsonNode item : items) {
            if (item.path("ref").path("name").asString().equals(name)) {
                return item;
            }
        }
        throw new AssertionError(name + " fehlt");
    }

    @Test
    void statistikMitHeatmapUndQuote() {
        String laufen = createDaysAgo(FELIX, streak("Laufen", daily()), 8);
        String zucker = createDaysAgo(FELIX, map("type", "ABSTINENCE", "name", "Ohne Zucker", "color", "mint",
                "backfillHours", 336), 9);
        int n = 0;
        for (LocalDate d : new LocalDate[]{TODAY.minusDays(1), TODAY}) {
            checkin(laufen, FELIX, map("id", String.format("%08d-1111-1111-1111-111111111111", n++), "date", d.toString()));
        }
        checkin(zucker, FELIX, map("id", "99999999-1111-1111-1111-111111111111", "kind", "BREAK",
                "date", TODAY.minusDays(4).toString()));
        JsonNode s = get("/cohabit/api/stats?range=MONTH&anchor=2026-09-30", FELIX).expect(200).json();
        assertEquals("MONTH", s.path("range").asString());
        assertEquals("September", s.path("label").asString());
        assertEquals(30, s.path("heatmap").path("days").size());
        assertEquals("2026-09-01", s.path("heatmap").path("from").asString());
        assertEquals(1, s.path("heatmap").path("days").get(29).path("count").asInt());
        assertEquals(1, s.path("heatmap").path("days").get(29).path("level").asInt());
        assertEquals(0, s.path("heatmap").path("days").get(25).path("count").asInt(), "keine BREAKs in der Heatmap");
        // Laufen: 22.-29.9. abgeschlossen (8 Tage), 1 erfuellt, heute erfuellt -> 2/9.
        // Ohne Zucker: 21.-30.9., 10 Tage, 9 ohne Unterbrechung.
        assertEquals(Math.round(100.0 * 11 / 19), s.path("fulfillmentRate").asInt());
        JsonNode laufenRow = find(s.path("cohabits"), "Laufen");
        assertEquals("2/9", laufenRow.path("progressText").asString());
        assertEquals("9 T", find(s.path("cohabits"), "Ohne Zucker").path("progressText").asString());
        assertEquals("4 T", s.path("longestStreak").path("short").asString(), "Ohne Zucker seit 4 Tagen");
        assertEquals("KW 40", get("/cohabit/api/stats?range=WEEK&anchor=2026-09-30", FELIX).json().path("label").asString());
        JsonNode year = get("/cohabit/api/stats?range=YEAR&anchor=2026-09-30", FELIX).json();
        assertEquals("2026", year.path("label").asString());
        assertEquals(365, year.path("heatmap").path("days").size());
        assertEquals(400, get("/cohabit/api/stats?range=DAY", FELIX).status());
    }

    @Test
    void trackFoodRechnetAusDemKalorienzaehler() {
        Map<String, Object> config = streak("Track food", daily());
        config.put("auto", map("source", "FOOD"));
        // Vor dem Anlegen: vergangene Tage merkt sich der Dienst (wie die alte App).
        food.days = (person, day) -> {
            if (!person.equals("felix")) {
                throw new SourceUnavailableException("Kalorienzähler", 403);
            }
            if (day.equals(TODAY)) {
                return new FoodClient.Day(900, 2300, Set.of("BREAKFAST"));
            }
            // Auch vor dem Beitritt: die Quelle weiss, was vorher war.
            return day.isAfter(TODAY.minusDays(9)) ? new FoodClient.Day(2000, 2300, Set.of())
                    : new FoodClient.Day(0, 2300, Set.of());
        };
        String id = createDaysAgo(FELIX, config, 5);
        JsonNode d = get("/cohabit/api/cohabits/" + id, FELIX).expect(200).json();
        assertEquals("8", d.path("summary").path("headline").path("value").asString());
        assertEquals("OPEN", d.path("summary").path("status").asString());
        assertFalse(d.path("summary").path("canCheckIn").asBoolean());
        assertEquals("900 von 1.840 kcal", d.path("summary").path("listLine").asString());
        assertEquals(900, d.path("summary").path("progress").path("done").asInt());
        assertEquals(1840, d.path("summary").path("progress").path("goal").asInt());
        assertTrue(d.path("streak").path("atRisk").asBoolean());
        food.days = (person, day) -> {
            throw new SourceUnavailableException("Kalorienzähler", new java.io.IOException("down"));
        };
        autoSources.forgetHistory();
        JsonNode down = get("/cohabit/api/cohabits/" + id, FELIX).expect(200).json().path("summary");
        assertEquals("UNAVAILABLE", down.path("status").asString());
        assertEquals("Kalorienzähler nicht erreichbar", down.path("unavailableText").asString());
        assertEquals("–", down.path("headline").path("value").asString());
    }

    @Test
    void schritteProWocheUndFokusZeit() {
        Map<String, Object> steps70 = streak("70.000 Schritte / Woche", map("kind", "TIMES_PER_WEEK", "times", 1));
        steps70.put("auto", map("source", "STEPS_WEEKLY", "weeklyStepGoal", 70000));
        String id = createDaysAgo(FELIX, steps70, 20);
        steps.perDay = (person, day) -> day.isBefore(LocalDate.of(2026, 9, 28)) ? 10_000 : 20_000;
        JsonNode s = get("/cohabit/api/cohabits/" + id, FELIX).json().path("summary");
        assertEquals("Streak · 70.000 Schritte pro Woche", s.path("typeLine").asString());
        assertEquals(60000, s.path("progress").path("done").asInt(), "Mo-Mi je 20.000");
        assertEquals("60.000 von 70.000", s.path("listLine").asString());
        assertEquals("Wochen", s.path("headline").path("unit").asString());

        Map<String, Object> fokus = streak("Fokus", daily());
        fokus.put("auto", map("source", "FOCUS", "focusMinutesGoal", 60));
        String fid = createDaysAgo(FELIX, fokus, 3);
        Cookie privateCookie = new Cookie("fh_private", PRIVATE);
        post("/habits/api/focus/sessions", Who.cookies(privateCookie),
                "{\"id\":\"a\",\"start\":\"2026-09-29T06:00:00Z\",\"end\":\"2026-09-29T07:05:00Z\"}").expect(201);
        post("/habits/api/focus/sessions", Who.cookies(privateCookie),
                "{\"id\":\"b\",\"start\":\"2026-09-30T06:00:00Z\",\"end\":\"2026-09-30T06:30:00Z\"}").expect(201);
        JsonNode f = get("/cohabit/api/cohabits/" + fid, FELIX).json().path("summary");
        assertEquals("1", f.path("headline").path("value").asString());
        assertEquals("30 von 60 Min.", f.path("listLine").asString());
    }
}
