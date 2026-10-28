package com.fherrmann.habits.cohabit.web;

import com.fherrmann.habits.cohabit.support.ApiTestBase;
import com.fherrmann.habits.cohabit.support.TestBeans;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Minuten pro Woche mit Timer: Start und Stopp in der Liste, die Woche zaehlt die Summe. */
class TimerApiTest extends ApiTestBase {

    private static final String STOP1 = "11111111-1111-1111-1111-111111111111";
    private static final String STOP2 = "22222222-2222-2222-2222-222222222222";

    private static Map<String, Object> minutes(int perWeek, Boolean timer) {
        Map<String, Object> config = streak("Üben", map("kind", "MINUTES_PER_WEEK", "minutes", perWeek));
        config.put("tracking", timer == null ? map("mode", "CHECK") : map("mode", "CHECK", "timer", timer));
        config.put("backfillHours", 336);
        return config;
    }

    private String base(String id) {
        return "/cohabit/api/cohabits/" + id;
    }

    private JsonNode summary(String id) {
        return get(base(id), FELIX).expect(200).json().path("summary");
    }

    @Test
    void anlegenMitTimer() {
        Map<String, Object> config = minutes(140, true);
        config.put("photoRequired", true);
        JsonNode d = create(FELIX, config);
        assertEquals("{\"mode\":\"VALUE\",\"unit\":\"MINUTES\",\"timer\":true}", d.path("config").path("tracking").toString());
        assertEquals("{\"kind\":\"MINUTES_PER_WEEK\",\"minutes\":140}",
                d.path("config").path("streak").path("rhythm").toString());
        assertFalse(d.path("config").path("photoRequired").asBoolean(), "mit Timer keine Foto-Pflicht");
        JsonNode s = d.path("summary");
        assertEquals("Streak · 140 Min. pro Woche", s.path("typeLine").asString());
        assertEquals("0 von 140 Min.", s.path("listLine").asString());
        assertEquals("noch 140 Min. diese Woche", s.path("subline").asString());
        assertEquals("Minuten eintragen", s.path("checkInLabel").asString());
        assertEquals("MINUTES", s.path("valueUnit").asString());
        assertTrue(s.path("canCheckIn").asBoolean());
        assertEquals("OPEN", s.path("status").asString());
        assertEquals(140, s.path("progress").path("goal").asInt());
        assertTrue(s.path("timer").isObject());
        assertTrue(s.path("timer").path("startedAt").isNull());
        assertEquals("WEEKS", d.path("streak").path("unit").asString());

        JsonNode without = create(FELIX, minutes(60, null));
        assertEquals("{\"mode\":\"VALUE\",\"unit\":\"MINUTES\"}", without.path("config").path("tracking").toString());
        assertTrue(without.path("summary").path("timer").isNull(), "ohne Timer kein Timer-Feld");
        assertTrue(create(FELIX, streak("Abends", daily())).path("summary").path("timer").isNull());
    }

    @Test
    void pruefungBeimAnlegen() {
        Response zero = post("/cohabit/api/cohabits", FELIX, minutes(0, true));
        assertEquals(400, zero.status());
        assertEquals("Minuten pro Woche gehen von 1 bis 10.080.", zero.message());
        Map<String, Object> dailyTimer = streak("Abends", daily());
        dailyTimer.put("tracking", map("mode", "VALUE", "unit", "MINUTES", "timer", true));
        Response timer = post("/cohabit/api/cohabits", FELIX, dailyTimer);
        assertEquals(400, timer.status());
        assertEquals("Einen Timer gibt es nur bei Minuten pro Woche.", timer.message());
        Map<String, Object> health = minutes(140, false);
        health.put("health", map("metric", "WORKOUT_MINUTES"));
        Response h = post("/cohabit/api/cohabits", FELIX, health);
        assertEquals(400, h.status());
        assertEquals("Health passt nicht zu Minuten pro Woche.", h.message());
    }

    @Test
    void startenUndStoppenTraegtDieMinutenEin() {
        String id = id(create(FELIX, minutes(140, true)));
        Instant start = clock.instant();
        JsonNode started = post(base(id) + "/timer", FELIX, null).expect(200).json();
        assertEquals(start.toString(), started.path("summary").path("timer").path("startedAt").asString());

        clock.advance(Duration.ofMinutes(3));
        post(base(id) + "/timer", FELIX, map()).expect(200);
        assertEquals(start.toString(), summary(id).path("timer").path("startedAt").asString(),
                "ein zweiter Start (anderes Geraet, Postausgang) laesst den ersten stehen");

        clock.advance(Duration.ofMinutes(22).plusSeconds(20));
        Response stopped = post(base(id) + "/timer/stop", FELIX, map("id", STOP1)).expect(201);
        JsonNode checkin = stopped.json().path("checkin");
        assertEquals(25, checkin.path("value").asInt());
        assertEquals("25 Min.", checkin.path("valueText").asString());
        assertEquals("2026-09-30", checkin.path("date").asString());
        JsonNode s = stopped.json().path("cohabit").path("summary");
        assertTrue(s.path("timer").path("startedAt").isNull());
        assertEquals("25 von 140 Min.", s.path("listLine").asString());
        assertEquals("noch 115 Min. diese Woche", s.path("subline").asString());
        assertTrue(s.path("canCheckIn").asBoolean(), "nach einer Sitzung geht die naechste");

        Response again = post(base(id) + "/timer/stop", FELIX, map("id", STOP1)).expect(200);
        assertEquals(checkin.path("id").asString(), again.json().path("checkin").path("id").asString(),
                "ein Nachsenden traegt nichts doppelt ein");
        Response idle = post(base(id) + "/timer/stop", FELIX, map("id", STOP2));
        assertEquals(409, idle.status());
        assertEquals("Der Timer läuft nicht.", idle.message());

        JsonNode item = get("/cohabit/api/timeline", FELIX).expect(200).json().path("items").get(0);
        assertEquals("Felix hat 25 Min. Üben eingetragen", item.path("title").asString());
    }

    @Test
    void startUndStoppAusDemPostausgang() {
        String id = id(create(FELIX, minutes(140, true)));
        Instant now = clock.instant();
        post(base(id) + "/timer", FELIX, map("startedAt", now.minus(Duration.ofMinutes(50)).toString())).expect(200);
        JsonNode r = post(base(id) + "/timer/stop", FELIX,
                map("id", STOP1, "stoppedAt", now.minus(Duration.ofMinutes(10)).toString())).expect(201).json();
        assertEquals(40, r.path("checkin").path("value").asInt(), "gezaehlt wird von Start bis Stopp am Geraet");

        Response old = post(base(id) + "/timer", FELIX, map("startedAt", now.minus(Duration.ofHours(25)).toString()));
        assertEquals(400, old.status());
        assertEquals("Der Start liegt zu weit zurück.", old.message());
    }

    @Test
    void kurzerStoppOhneEintragUndVerwerfen() {
        String id = id(create(FELIX, minutes(140, true)));
        post(base(id) + "/timer", FELIX, null).expect(200);
        clock.advance(Duration.ofSeconds(20));
        JsonNode r = post(base(id) + "/timer/stop", FELIX, map("id", STOP1)).expect(200).json();
        assertTrue(r.path("checkin").isNull(), "unter einer halben Minute kein Eintrag");
        assertTrue(r.path("cohabit").path("summary").path("timer").path("startedAt").isNull());

        post(base(id) + "/timer", FELIX, null).expect(200);
        clock.advance(Duration.ofHours(25));
        Response tooLong = post(base(id) + "/timer/stop", FELIX, map("id", STOP2));
        assertEquals(400, tooLong.status());
        assertEquals("Der Timer lief länger als 24 Stunden.", tooLong.message());
        assertFalse(summary(id).path("timer").path("startedAt").isNull(), "der vergessene Timer bleibt zum Verwerfen");
        JsonNode discarded = delete(base(id) + "/timer", FELIX).expect(200).json();
        assertTrue(discarded.path("summary").path("timer").path("startedAt").isNull());
        assertEquals(0, discarded.path("myCheckins").size());
        delete(base(id) + "/timer", FELIX).expect(200);
    }

    @Test
    void ohneTimerKeinStart() {
        String id = id(create(FELIX, minutes(140, null)));
        Response r = post(base(id) + "/timer", FELIX, null);
        assertEquals(400, r.status());
        assertEquals("Dieses Co-Habit hat keinen Timer.", r.message());
    }

    @Test
    void mehrereEintraegeAmTagUndMinutenPflicht() {
        String id = id(create(FELIX, minutes(140, true)));
        checkin(id, FELIX, map("value", 60));
        JsonNode second = checkin(id, FELIX, map("value", 59.6));
        assertEquals(60, second.path("checkin").path("value").asInt(), "ganze Minuten");
        Response missing = post(base(id) + "/checkins", FELIX, map());
        assertEquals("Die Minuten fehlen.", missing.message());
        Response zero = post(base(id) + "/checkins", FELIX, map("value", 0));
        assertEquals("Die Minuten müssen zwischen 1 und 1.440 liegen.", zero.message());
        JsonNode done = checkin(id, FELIX, map("value", 20)).path("cohabit");
        assertEquals("DONE", done.path("summary").path("status").asString());
        assertEquals("diese Woche geschafft", done.path("streak").path("remainingText").asString());
        assertEquals("140 von 140 Min.", done.path("summary").path("listLine").asString());
    }

    @Test
    void serieInWochenAusDerSummeDerMinuten() {
        // Angelegt am Dienstag der Vorwoche (22.09.); Mittwoch, 30.09., ist heute.
        String id = createDaysAgo(FELIX, minutes(140, false), 8);
        checkin(id, FELIX, map("date", "2026-09-23", "value", 100));
        checkin(id, FELIX, map("date", "2026-09-27", "value", 40));
        JsonNode d = get(base(id), FELIX).expect(200).json();
        assertEquals(1, d.path("streak").path("current").asInt(), "100 + 40 Minuten machen die Vorwoche voll");
        assertEquals("Woche", d.path("streak").path("unitLabel").asString());

        clock.set(TestBeans.START.plusDays(5).toInstant());
        JsonNode next = get(base(id), FELIX).expect(200).json();
        assertEquals(0, next.path("streak").path("current").asInt(), "die Woche bis 04.10. blieb leer");
    }

    @Test
    void timerAbschaltenVerwirftDenLaufenden() {
        String id = id(create(FELIX, minutes(140, true)));
        post(base(id) + "/timer", FELIX, null).expect(200);
        Map<String, Object> edit = minutes(150, null);
        edit.remove("tracking");
        JsonNode kept = put(base(id), FELIX, edit).expect(200).json();
        assertTrue(kept.path("config").path("tracking").path("timer").asBoolean(),
                "ein Bearbeiten ohne timer laesst ihn stehen");
        assertFalse(kept.path("summary").path("timer").path("startedAt").isNull());
        assertEquals("Streak · 150 Min. pro Woche", kept.path("summary").path("typeLine").asString());

        JsonNode off = put(base(id), FELIX, minutes(150, false)).expect(200).json();
        assertTrue(off.path("summary").path("timer").isNull());
    }

    @Test
    void klassischeListeZeigtMinutenUndTimer() {
        String id = id(create(FELIX, minutes(140, true)));
        checkin(id, FELIX, map("value", 45));
        JsonNode habit = null;
        for (JsonNode h : get("/cohabit/api/classic/habits", FELIX).expect(200).json()) {
            if (h.path("id").asString().equals(id)) {
                habit = h;
            }
        }
        assertEquals("BUILD", habit.path("kind").asString());
        assertEquals("WEEKS", habit.path("unit").asString());
        assertTrue(habit.path("period").isMissingNode() || habit.path("period").isNull());
        assertEquals(45, habit.path("progress").path("value").asInt());
        assertEquals(140, habit.path("progress").path("goal").asInt());
        assertFalse(habit.path("atRisk").asBoolean());
        assertTrue(habit.path("summary").path("timer").isObject());

        put("/cohabit/api/classic/habits/" + id, FELIX, map("name", "Üben", "period", "DAY")).expect(200);
        assertEquals("Streak · 140 Min. pro Woche", summary(id).path("typeLine").asString(),
                "das alte Formular macht keine tägliche Serie daraus");
    }
}
