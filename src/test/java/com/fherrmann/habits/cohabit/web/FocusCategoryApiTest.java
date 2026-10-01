package com.fherrmann.habits.cohabit.web;

import com.fherrmann.habits.cohabit.support.ApiTestBase;
import com.fherrmann.habits.dto.FocusSessionRequest;
import com.fherrmann.habits.service.FocusService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fokus-Habits nach Kategorie: "1 h Bachelorarbeit am Tag" zaehlt nur Baeume mit der
 * Kategorie Bachelorarbeit, "2 h pro Woche" die Summe Mo-So. Heute ist Mittwoch, der
 * 30.09.2026, 10:00 - die Woche begann Montag, den 28.
 */
class FocusCategoryApiTest extends ApiTestBase {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    @Autowired
    FocusService focus;

    private static Instant at(int day, int hour, int minute) {
        return LocalDate.of(2026, 9, day).atTime(hour, minute).atZone(BERLIN).toInstant();
    }

    private void tree(String id, int day, int fromHour, int minutes, String categoryId) {
        Instant start = at(day, fromHour, 0);
        focus.record(new FocusSessionRequest(id, start, start.plusSeconds(minutes * 60L), categoryId));
    }

    private Map<String, Object> focusHabit(String name, int minutes, String categoryId, String period) {
        Map<String, Object> config = streak(name, daily());
        config.put("auto", map("source", "FOCUS", "focusMinutesGoal", minutes, "focusCategoryId", categoryId,
                "focusPeriod", period));
        return config;
    }

    private JsonNode summary(String id) {
        return get("/cohabit/api/cohabits/" + id, FELIX).expect(200).json().path("summary");
    }

    @Test
    void nurDieBaeumeDerKategorieZaehlenTaeglichUndProWoche() {
        String thesis = focus.createCategory(null, "Bachelorarbeit").category().id();
        String uni = focus.createCategory(null, "Uni").category().id();

        List<String> names = new ArrayList<>();
        get("/cohabit/api/focus/categories", FELIX).expect(200).json().forEach(c -> names.add(c.path("name").asString()));
        assertEquals(List.of("Bachelorarbeit", "Uni"), names);
        assertTrue(get("/cohabit/api/focus/categories", TORBEN_APP).expect(200).json().isEmpty(),
                "die Baeume gehoeren Felix");

        String daily = createDaysAgo(FELIX, focusHabit("1h Fokus für Bachelorarbeit", 60, thesis, "DAY"), 3);
        String weekly = createDaysAgo(FELIX, focusHabit("Bachelorarbeit pro Woche", 120, thesis, "WEEK"), 3);
        String uniDaily = createDaysAgo(FELIX, focusHabit("Uni", 60, uni, "DAY"), 3);

        tree("t1", 28, 9, 60, thesis);
        tree("t2", 29, 9, 30, thesis);
        tree("u1", 29, 14, 60, uni);
        tree("t3", 30, 8, 60, thesis);
        tree("ohne", 30, 7, 50, null);

        JsonNode d = summary(daily);
        assertEquals("Streak · 60 Min. Bachelorarbeit täglich", d.path("typeLine").asString());
        assertEquals("1", d.path("headline").path("value").asString(), "heute 60 Min. - der 29. hatte nur 30");
        assertEquals("DONE", d.path("status").asString());

        JsonNode u = summary(uniDaily);
        assertEquals("OPEN", u.path("status").asString(), "heute kein Uni-Baum - der ohne Kategorie zaehlt nicht");
        assertEquals("1", u.path("headline").path("value").asString(), "gestern 60 Min. Uni");

        JsonNode w = summary(weekly);
        assertEquals("Streak · 120 Min. Bachelorarbeit pro Woche", w.path("typeLine").asString());
        assertEquals(150, w.path("progress").path("done").asInt(), "60 + 30 + 60 seit Montag");
        assertEquals(120, w.path("progress").path("goal").asInt());
        assertEquals("1", w.path("headline").path("value").asString(), "diese Woche geschafft, die davor nicht");
        assertEquals("Woche", w.path("headline").path("unit").asString());

        JsonNode config = get("/cohabit/api/cohabits/" + daily, FELIX).json().path("config").path("auto");
        assertEquals(thesis, config.path("focusCategoryId").asString());
        assertEquals("Bachelorarbeit", config.path("focusCategoryName").asString());
        assertEquals("DAY", config.path("focusPeriod").asString());

        focus.renameCategory(thesis, "Thesis");
        assertEquals("Streak · 60 Min. Thesis täglich", summary(daily).path("typeLine").asString(),
                "der neue Name kommt in coHabit an");
    }

    @Test
    void dieKategorieMussEsGebenUndIhrNameKommtVomWald() {
        String thesis = focus.createCategory(null, "Bachelorarbeit").category().id();
        Map<String, Object> unknown = focusHabit("x", 60, "gibt-es-nicht", "DAY");
        Response r = post("/cohabit/api/cohabits", FELIX, unknown);
        assertEquals(400, r.status());
        assertEquals("Unbekannte Kategorie.", r.message());

        Map<String, Object> forged = streak("Thesis", daily());
        forged.put("auto", map("source", "FOCUS", "focusMinutesGoal", 60, "focusCategoryId", thesis,
                "focusCategoryName", "Gefälscht", "focusPeriod", "DAY"));
        String id = id(create(FELIX, forged));
        assertEquals("Bachelorarbeit", get("/cohabit/api/cohabits/" + id, FELIX).json()
                .path("config").path("auto").path("focusCategoryName").asString());

        assertEquals(400, post("/cohabit/api/cohabits", FELIX, focusHabit("zu viel", 1441, thesis, "DAY")).status());
        assertEquals(201, post("/cohabit/api/cohabits", FELIX, focusHabit("Woche", 1441, thesis, "WEEK")).status(),
                "pro Woche darf es mehr als ein Tag sein");

        focus.deleteCategory(thesis);
        assertEquals(400, post("/cohabit/api/cohabits", FELIX, focusHabit("neu", 60, thesis, "DAY")).status(),
                "eine geloeschte Kategorie gibt es zur Auswahl nicht mehr");
        Map<String, Object> rename = focusHabit("Thesis täglich", 60, thesis, "DAY");
        assertEquals(200, put("/cohabit/api/cohabits/" + id, FELIX, rename).status(),
                "ein bestehendes Co-Habit behaelt sie aber");
    }

    @Test
    void dieKlassischeListeKenntKategorieUndWoche() {
        String thesis = focus.createCategory(null, "Bachelorarbeit").category().id();
        String uni = focus.createCategory(null, "Uni").category().id();
        JsonNode created = post("/cohabit/api/classic/habits", FELIX, map("name", "Uni pro Woche", "kind", "FOCUS",
                "focusMinutesGoal", 120, "focusCategoryId", uni, "focusPeriod", "WEEK")).expect(201).json();
        assertEquals("FOCUS", created.path("kind").asString());
        assertEquals("WEEKS", created.path("unit").asString());
        assertEquals(uni, created.path("focus").path("categoryId").asString());
        assertEquals("Uni", created.path("focus").path("categoryName").asString());
        assertEquals("WEEK", created.path("focus").path("period").asString());
        String id = created.path("id").asString();

        tree("u1", 28, 9, 60, uni);
        tree("t1", 29, 9, 90, thesis);
        JsonNode h = classic(id);
        assertEquals(60, h.path("progress").path("value").asInt(), "nur der Uni-Baum dieser Woche");
        assertEquals(120, h.path("progress").path("goal").asInt());
        assertFalse(h.path("atRisk").asBoolean(), "eine Woche ist nie gefaehrdet - wie die Schritte");

        JsonNode kept = put("/cohabit/api/classic/habits/" + id, FELIX, map("name", "Uni je Woche", "kind", "FOCUS"))
                .expect(200).json();
        assertEquals(uni, kept.path("focus").path("categoryId").asString(), "ohne Feld bleibt die Kategorie");
        JsonNode all = put("/cohabit/api/classic/habits/" + id, FELIX, map("name", "Fokus je Woche", "kind", "FOCUS",
                "focusCategoryId", "")).expect(200).json();
        assertTrue(all.path("focus").path("categoryId").isNull(), "leer heisst alle Baeume");
        assertEquals(150, all.path("progress").path("value").asInt());
        JsonNode daily = put("/cohabit/api/classic/habits/" + id, FELIX, map("name", "Thesis täglich", "kind", "FOCUS",
                "focusCategoryId", thesis, "focusPeriod", "DAY", "focusMinutesGoal", 60)).expect(200).json();
        assertEquals("DAY", daily.path("focus").path("period").asString());
        assertEquals("DAYS", daily.path("unit").asString());
        assertEquals("Bachelorarbeit", daily.path("focus").path("categoryName").asString());
    }

    private JsonNode classic(String id) {
        for (JsonNode h : get("/cohabit/api/classic/habits", FELIX).expect(200).json()) {
            if (h.path("id").asString().equals(id)) {
                return h;
            }
        }
        throw new AssertionError("nicht in der klassischen Liste: " + id);
    }
}
