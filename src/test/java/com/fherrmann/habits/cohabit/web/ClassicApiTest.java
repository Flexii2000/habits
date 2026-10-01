package com.fherrmann.habits.cohabit.web;

import com.fherrmann.habits.client.FoodClient;
import com.fherrmann.habits.client.SourceUnavailableException;
import com.fherrmann.habits.cohabit.support.ApiTestBase;
import com.fherrmann.habits.cohabit.support.TestBeans;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Die klassische Liste: die Antwortform der alten Habits-API fuer Streaks und
 * Abstinenz - auch geteilte -, und Abhaken, Anlegen, Aendern, Loeschen ueber die
 * normalen Dienste. Heute ist der 30.09.2026 (Mittwoch).
 */
class ClassicApiTest extends ApiTestBase {

    private static final String BASE = "/cohabit/api/classic/habits";
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 30);

    private List<JsonNode> list(Who who) {
        List<JsonNode> list = new ArrayList<>();
        get(BASE, who).expect(200).json().forEach(list::add);
        return list;
    }

    private JsonNode find(Who who, String id) {
        return list(who).stream().filter(h -> h.path("id").asString().equals(id)).findFirst().orElse(null);
    }

    /** Legt ueber die klassische Liste an - vor {@code daysAgo} Tagen. */
    private JsonNode createClassic(Who who, Map<String, Object> draft, int daysAgo) {
        java.time.Instant now = clock.instant();
        clock.set(now.minus(Duration.ofDays(daysAgo)));
        try {
            return post(BASE, who, draft).expect(201).json();
        } finally {
            clock.set(now);
        }
    }

    private JsonNode mark(Who who, String id, LocalDate day) {
        return post(BASE + "/" + id + "/marks", who, map("date", day.toString())).expect(200).json();
    }

    private JsonNode unmark(Who who, String id, LocalDate day) {
        return delete(BASE + "/" + id + "/marks/" + day, who).expect(200).json();
    }

    private static List<Boolean> bools(JsonNode array) {
        List<Boolean> list = new ArrayList<>();
        array.forEach(n -> list.add(n.asBoolean()));
        return list;
    }

    private static List<String> strings(JsonNode array) {
        List<String> list = new ArrayList<>();
        array.forEach(n -> list.add(n.asString()));
        return list;
    }

    @Test
    void alleAktivenInAnlegereihenfolgeAuchZieleUndChallenges() {
        String reading = createClassic(FELIX, map("name", "Lesen", "kind", "BUILD"), 0).path("id").asString();
        String sugar = createClassic(FELIX, map("name", "Ohne Zucker", "kind", "QUIT"), 0).path("id").asString();
        String goal = id(create(FELIX, map("type", "GOAL", "name", "100k Schritte", "color", "periwinkle",
                "tracking", map("mode", "VALUE", "unit", "STEPS"),
                "goal", map("target", 100000, "deadline", "2026-10-31", "counting", "AMOUNT", "mode", "TEAM"))));
        String cooking = withMembers(map("type", "CHALLENGE", "name", "Wer kocht öfter?", "color", "butter",
                "challenge", map("start", "2026-09-28", "end", "2026-10-04", "scoring", "MOST_ENTRIES")), TORBEN_APP);
        String archived = id(create(FELIX, streak("Alt", daily())));
        post("/cohabit/api/cohabits/" + archived + "/archive", FELIX, null).expect(200);

        List<JsonNode> list = list(FELIX);
        assertEquals(List.of(reading, sugar, goal, cooking), list.stream().map(h -> h.path("id").asString()).toList(),
                "alles, was auch die neue Liste zeigt - ohne Archiviertes");
        assertEquals("BUILD", list.get(0).path("kind").asString());
        assertEquals("QUIT", list.get(1).path("kind").asString());
        assertTrue(list.get(0).path("summary").isNull(), "die alten Arten brauchen keine Zusammenfassung");

        JsonNode g = list.get(2);
        assertEquals("GOAL", g.path("kind").asString());
        assertEquals("DAYS", g.path("unit").asString(), "neutral, damit aeltere Apps nicht stolpern");
        assertEquals(0, g.path("streak").asInt());
        assertEquals(7, g.path("recent").size(), "sieben Tage, wie bei den anderen");
        assertEquals("0%", g.path("summary").path("headline").path("value").asString());
        assertTrue(g.path("summary").path("canCheckIn").asBoolean());
        assertEquals("100k Schritte", g.path("summary").path("ref").path("name").asString());

        JsonNode ch = list.get(3);
        assertEquals("CHALLENGE", ch.path("kind").asString());
        assertTrue(ch.path("shared").asBoolean());
        assertTrue(ch.path("admin").asBoolean());
        assertFalse(ch.path("doneToday").asBoolean());
        checkin(cooking, FELIX, map("id", java.util.UUID.randomUUID().toString()));
        assertEquals(List.of(false, false, false, false, false, false, false), bools(ch.path("recent")));
        JsonNode after = find(FELIX, cooking);
        assertTrue(after.path("doneToday").asBoolean(), "heute eingetragen");
        assertEquals(List.of(false, false, false, false, false, false, true), bools(after.path("recent")),
                "die Punkte wie bei Track food - heute gefuellt");
        assertEquals("#1", after.path("summary").path("headline").path("value").asString());

        assertEquals(List.of(cooking), list(TORBEN_APP).stream().map(h -> h.path("id").asString()).toList(),
                "jede Person sieht nur ihre eigenen");
        delete(BASE + "/" + cooking, TORBEN_APP).expect(204);
        assertTrue(list(TORBEN_APP).isEmpty(), "auch eine Challenge laesst sich aus der Liste verlassen");
        assertNotNull(find(FELIX, cooking), "Felix bleibt drin");
    }

    @Test
    void abhakenUndZuruecknehmenWieFrueher() {
        String id = createClassic(FELIX, map("name", "Laufen", "kind", "BUILD", "period", "DAY"), 5)
                .path("id").asString();
        JsonNode fresh = find(FELIX, id);
        assertEquals("DAYS", fresh.path("unit").asString());
        assertEquals("DAY", fresh.path("period").asString());
        assertEquals("2026-09-25", fresh.path("createdAt").asString());
        assertEquals("2026-09-25", fresh.path("backfillFrom").asString(), "14 Tage Frist, aber nie vor dem Start");
        assertEquals(0, fresh.path("streak").asInt());
        assertFalse(fresh.path("atRisk").asBoolean());
        assertTrue(fresh.path("progress").isNull(), "taeglich: kein Zeitraum-Stand");

        for (int day = 27; day <= 29; day++) {
            mark(FELIX, id, LocalDate.of(2026, 9, day));
        }
        JsonNode open = find(FELIX, id);
        assertEquals(3, open.path("streak").asInt());
        assertTrue(open.path("atRisk").asBoolean(), "heute noch offen");
        assertFalse(open.path("doneToday").asBoolean());
        assertEquals(List.of(false, false, false, true, true, true, false), bools(open.path("recent")));
        assertEquals(List.of("2026-09-27", "2026-09-28", "2026-09-29"), strings(open.path("markedDays")));

        JsonNode done = mark(FELIX, id, TODAY);
        assertEquals(4, done.path("streak").asInt());
        assertTrue(done.path("doneToday").asBoolean());
        assertFalse(done.path("atRisk").asBoolean());

        JsonNode undone = unmark(FELIX, id, TODAY);
        assertEquals(3, undone.path("streak").asInt());
        assertFalse(undone.path("doneToday").asBoolean());
        assertEquals(undone.toString(), unmark(FELIX, id, TODAY).toString(), "kein Eintrag: nichts passiert");

        Response beforeStart = post(BASE + "/" + id + "/marks", FELIX, map("date", "2026-09-20"));
        assertEquals(400, beforeStart.status());
        Response twice = post(BASE + "/" + id + "/marks", FELIX, map("date", "2026-09-29"));
        assertEquals(409, twice.status());
    }

    @Test
    void lassenZaehltVonSelbstUndEinRueckfallSetztZurueck() {
        String id = createClassic(FELIX, map("name", "Ohne Zucker", "kind", "QUIT"), 5).path("id").asString();
        JsonNode clean = find(FELIX, id);
        assertEquals("QUIT", clean.path("kind").asString());
        assertEquals(6, clean.path("streak").asInt(), "25. bis 30., heute zaehlt mit");
        assertTrue(clean.path("doneToday").asBoolean(), "erledigt heisst: kein Rueckfall");
        assertFalse(clean.path("atRisk").asBoolean());
        assertEquals(List.of(false, true, true, true, true, true, true), bools(clean.path("recent")));

        JsonNode relapse = mark(FELIX, id, TODAY);
        assertEquals(0, relapse.path("streak").asInt());
        assertFalse(relapse.path("doneToday").asBoolean());
        assertEquals(List.of("2026-09-30"), strings(relapse.path("markedDays")));

        assertEquals(6, unmark(FELIX, id, TODAY).path("streak").asInt(), "Doch nicht");
    }

    @Test
    void wochenrhythmusZeigtDenStandDerWoche() {
        String id = createClassic(FELIX, map("name", "Zeitung", "kind", "BUILD", "period", "WEEK",
                "timesPerPeriod", 2), 10).path("id").asString();
        mark(FELIX, id, LocalDate.of(2026, 9, 21));
        mark(FELIX, id, LocalDate.of(2026, 9, 23));
        JsonNode h = mark(FELIX, id, TODAY);
        assertEquals("WEEKS", h.path("unit").asString());
        assertEquals("WEEK", h.path("period").asString());
        assertEquals(2, h.path("timesPerPeriod").asInt());
        assertEquals(1, h.path("streak").asInt(), "die Woche 21.-27. war erfuellt");
        assertTrue(h.path("atRisk").asBoolean(), "diese Woche erst einmal");
        assertTrue(h.path("doneToday").asBoolean(), "heute abgehakt");
        assertEquals(1, h.path("progress").path("value").asInt());
        assertEquals(2, h.path("progress").path("goal").asInt());
        assertEquals(List.of(false, false, false, false, false, true, false), bools(h.path("recent")));
    }

    @Test
    void mitFotoPflichtGehtAbhakenNurImBeweisfotoBlatt() {
        Map<String, Object> config = streak("Laufen", daily());
        config.put("photoRequired", true);
        String id = id(create(FELIX, config));
        assertTrue(find(FELIX, id).path("photoRequired").asBoolean());
        Response r = post(BASE + "/" + id + "/marks", FELIX, map("date", TODAY.toString()));
        assertEquals(400, r.status());
        assertEquals("Ein Beweisfoto ist Pflicht.", r.message());
    }

    @Test
    void geteilteErscheinenUndLoeschenHeisstDortVerlassen() {
        String id = withMembers(streak("Laufen", daily()), TORBEN_APP);
        JsonNode forTorben = find(TORBEN_APP, id);
        assertTrue(forTorben.path("shared").asBoolean());
        assertFalse(forTorben.path("admin").asBoolean());
        assertTrue(find(FELIX, id).path("admin").asBoolean());

        mark(TORBEN_APP, id, TODAY);
        assertTrue(find(TORBEN_APP, id).path("doneToday").asBoolean());
        assertFalse(find(FELIX, id).path("doneToday").asBoolean(), "jede Person hat ihre eigene Serie");

        assertEquals(403, put(BASE + "/" + id, TORBEN_APP, map("name", "Joggen", "kind", "BUILD")).status());
        delete(BASE + "/" + id, TORBEN_APP).expect(204);
        assertNull(find(TORBEN_APP, id));
        assertFalse(find(FELIX, id).path("shared").asBoolean(), "Felix behaelt es");

        delete(BASE + "/" + id, FELIX).expect(204);
        assertEquals(404, get("/cohabit/api/cohabits/" + id, FELIX).status(), "allein: ganz weg");
    }

    @Test
    void anlegenWieInDerAltenAppMitAllenArten() {
        JsonNode daily = createClassic(FELIX, map("name", "Logbook", "kind", "BUILD", "period", "DAY"), 0);
        JsonNode weekly = createClassic(FELIX, map("name", "Zeitung", "kind", "BUILD", "period", "WEEK",
                "timesPerPeriod", 3), 0);
        JsonNode monthly = createClassic(FELIX, map("name", "Politik", "kind", "BUILD", "period", "MONTH",
                "timesPerPeriod", 2), 0);
        JsonNode quit = createClassic(FELIX, map("name", "Ohne Zucker", "kind", "QUIT"), 0);
        JsonNode food = createClassic(FELIX, map("name", "Track food", "kind", "FOOD"), 0);
        JsonNode steps = createClassic(FELIX, map("name", "70.000 Schritte / Woche", "kind", "STEPS",
                "weeklyStepGoal", 70000), 0);
        JsonNode focus = createClassic(FELIX, map("name", "Fokus", "kind", "FOCUS", "focusMinutesGoal", 240), 0);

        assertEquals("DAY", daily.path("period").asString());
        assertEquals("MONTHS", monthly.path("unit").asString());
        assertEquals(3, weekly.path("timesPerPeriod").asInt());
        assertEquals("QUIT", quit.path("kind").asString());
        assertEquals("FOOD", food.path("kind").asString());
        assertEquals("DAYS", food.path("unit").asString());
        assertEquals("STEPS", steps.path("kind").asString());
        assertEquals("WEEKS", steps.path("unit").asString());
        assertEquals(70000, steps.path("weeklyStepGoal").asInt());
        assertEquals("FOCUS", focus.path("kind").asString());
        assertEquals(240, focus.path("focusMinutesGoal").asInt());
        assertTrue(food.path("markedDays").isEmpty());

        JsonNode config = get("/cohabit/api/cohabits/" + daily.path("id").asString(), FELIX).expect(200).json()
                .path("config");
        assertEquals(336, config.path("backfillHours").asInt(), "14 Tage wie frueher");
        assertEquals("Europe/Berlin", config.path("timezone").asString());
        assertFalse(config.path("photoRequired").asBoolean());
        assertEquals("peach", config.path("color").asString(), "Farben reihum");
        assertEquals("mint", get("/cohabit/api/cohabits/" + weekly.path("id").asString(), FELIX).json()
                .path("config").path("color").asString());

        Response noSource = post(BASE, TORBEN_APP, map("name", "Fokus", "kind", "FOCUS"));
        assertEquals(403, noSource.status());
        assertEquals(400, post(BASE, FELIX, map("name", "Ohne Art")).status());
        assertEquals(400, post(BASE, FELIX, map("name", "Zu oft", "kind", "BUILD", "period", "WEEK",
                "timesPerPeriod", 8)).status());
    }

    @Test
    void aendernLaesstStehenWasDasAlteFormularNichtKennt() {
        Map<String, Object> config = streak("Lesen", map("kind", "WEEKDAYS", "weekdays", List.of(1, 2, 3, 4, 5)));
        config.put("reminderTime", "07:30");
        config.put("photoRequired", true);
        config.put("backfillHours", 72);
        config.put("color", "rose");
        String id = id(create(FELIX, config));
        assertTrue(find(FELIX, id).path("period").isNull(), "Wochentage kennt das alte Design nicht");

        JsonNode renamed = put(BASE + "/" + id, FELIX, map("name", "Lesen am Abend", "kind", "BUILD",
                "period", null, "timesPerPeriod", null)).expect(200).json();
        assertEquals("Lesen am Abend", renamed.path("name").asString());
        JsonNode c = get("/cohabit/api/cohabits/" + id, FELIX).expect(200).json().path("config");
        assertEquals("WEEKDAYS", c.path("streak").path("rhythm").path("kind").asString());
        assertEquals("07:30", c.path("reminderTime").asString());
        assertTrue(c.path("photoRequired").asBoolean());
        assertEquals(72, c.path("backfillHours").asInt());
        assertEquals("rose", c.path("color").asString());

        JsonNode weekly = put(BASE + "/" + id, FELIX, map("name", "Lesen am Abend", "kind", "BUILD",
                "period", "WEEK", "timesPerPeriod", 3)).expect(200).json();
        assertEquals("WEEK", weekly.path("period").asString());
        assertEquals(3, weekly.path("timesPerPeriod").asInt());

        Response kindChange = put(BASE + "/" + id, FELIX, map("name", "x", "kind", "QUIT"));
        assertEquals(400, kindChange.status());
        assertEquals("Die Art lässt sich nicht ändern.", kindChange.message());

        String steps = createClassic(FELIX, map("name", "Schritte", "kind", "STEPS", "weeklyStepGoal", 70000), 0)
                .path("id").asString();
        JsonNode goal = put(BASE + "/" + steps, FELIX, map("name", "Schritte", "kind", "STEPS",
                "weeklyStepGoal", 80000)).expect(200).json();
        assertEquals(80000, goal.path("weeklyStepGoal").asInt());
    }

    @Test
    void automatischeWieFrueher() {
        LocalDate since = LocalDate.of(2026, 9, 20);
        food.days = (person, day) -> day.isBefore(since) ? new FoodClient.Day(0, 2300, Set.of())
                : day.equals(TODAY) ? new FoodClient.Day(1500, 2300, Set.of())
                : new FoodClient.Day(2000, 2300, Set.of());
        steps.perDay = (person, day) -> day.isBefore(LocalDate.of(2026, 9, 14)) ? 0 : 12000;
        String foodId = createClassic(FELIX, map("name", "Track food", "kind", "FOOD"), 10).path("id").asString();
        String stepsId = createClassic(FELIX, map("name", "Schritte", "kind", "STEPS", "weeklyStepGoal", 70000), 10)
                .path("id").asString();

        JsonNode f = find(FELIX, foodId);
        assertEquals(10, f.path("streak").asInt(), "20. bis 29.");
        assertTrue(f.path("atRisk").asBoolean());
        assertFalse(f.path("doneToday").asBoolean());
        assertEquals(1500, f.path("progress").path("value").asInt());
        assertEquals(1840, f.path("progress").path("goal").asInt(), "80 % von 2300");
        assertEquals(List.of(true, true, true, true, true, true, false), bools(f.path("recent")));

        JsonNode s = find(FELIX, stepsId);
        assertEquals(2, s.path("streak").asInt(), "Wochen ab 14. und 21.");
        assertFalse(s.path("atRisk").asBoolean(), "die alte App meldete die Schritte-Woche nie als gefaehrdet");
        assertFalse(s.path("doneToday").asBoolean());
        assertEquals(36000, s.path("progress").path("value").asInt(), "Mo-Mi je 12.000");
        assertEquals(70000, s.path("progress").path("goal").asInt());
        assertEquals(List.of(false, false, false, false, true, true, false), bools(s.path("recent")));

        assertEquals(403, post(BASE + "/" + foodId + "/marks", FELIX, map("date", TODAY.toString())).status());

        food.days = (person, day) -> {
            throw new SourceUnavailableException("Kalorienzähler", 503);
        };
        autoSources.forgetHistory();
        JsonNode down = find(FELIX, foodId);
        assertEquals("Kalorienzähler antwortete mit HTTP 503", down.path("unavailable").asString());
        assertEquals(0, down.path("streak").asInt());
        assertTrue(down.path("recent").isEmpty());
    }

    @Test
    void nachsendenAusDemPostausgangLegtKeinenZweitenEintragAn() {
        String id = createClassic(FELIX, map("name", "Laufen", "kind", "BUILD"), 0).path("id").asString();
        Map<String, Object> body = map("date", TODAY.toString(), "id", "classic-0000-aaaa");
        post(BASE + "/" + id + "/marks", FELIX, body).expect(200);
        post(BASE + "/" + id + "/marks", FELIX, body).expect(200);
        assertEquals(1, get("/cohabit/api/cohabits/" + id, FELIX).json().path("myCheckins").size());
    }

    @Test
    void zieleTraegtManImCoHabitEinUndFremdeGibtEsNicht() {
        String goal = id(create(FELIX, map("type", "GOAL", "name", "100k", "color", "periwinkle",
                "tracking", map("mode", "VALUE", "unit", "STEPS"),
                "goal", map("target", 100000, "deadline", "2026-10-31", "counting", "AMOUNT", "mode", "TEAM"))));
        Response r = post(BASE + "/" + goal + "/marks", FELIX, map("date", TODAY.toString()));
        assertEquals(400, r.status());
        assertEquals("Ziele und Challenges trägst du im Co-Habit ein.", r.message());
        assertEquals(400, put(BASE + "/" + goal, FELIX, map("name", "x", "kind", "BUILD")).status());
        String felixOnly = createClassic(FELIX, map("name", "Privat", "kind", "BUILD"), 0).path("id").asString();
        assertEquals(404, post(BASE + "/" + felixOnly + "/marks", TORBEN_APP, map("date", TODAY.toString())).status());
        assertEquals(404, delete(BASE + "/" + felixOnly, TORBEN_APP).status());
        assertEquals(401, get(BASE, Who.nobody()).status());
        assertEquals(TestBeans.START.toLocalDate(), TODAY);
    }
}
