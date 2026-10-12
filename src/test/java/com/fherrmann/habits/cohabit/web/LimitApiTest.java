package com.fherrmann.habits.cohabit.web;

import com.fherrmann.habits.cohabit.support.ApiTestBase;
import com.fherrmann.habits.cohabit.support.TestBeans;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Abstinenz mit Tageslimit (12.10.): die Zahl je Tag steht im Logbook, coHabit liest sie und
 * schreibt beim Zaehlen dorthin. Ein Tag ueber dem Limit bricht die Serie - heute sofort.
 */
class LimitApiTest extends ApiTestBase {

    private static final String A = "b-a";
    private static final String YES_NO = "b-b";
    private static final LocalDate TODAY = TestBeans.START.toLocalDate();

    @BeforeEach
    void behaviors() {
        logbook.behavior("felix", A, "Verhalten A", "Tassen", false);
        logbook.behavior("felix", YES_NO, "Verhalten B", null, false);
    }

    private static Map<String, Object> limit(String behaviorId, Integer perDay) {
        return map("type", "ABSTINENCE", "name", "Gewohnheit A", "color", "mint",
                "abstinence", map("groupMode", false, "limit", map("behaviorId", behaviorId, "perDay", perDay)));
    }

    private Response count(String id, LocalDate date, Object count) {
        return put("/cohabit/api/cohabits/" + id + "/count/" + date, FELIX, map("count", count));
    }

    private JsonNode detail(String id) {
        return get("/cohabit/api/cohabits/" + id, FELIX).expect(200).json();
    }

    private long events() {
        return store.read(data -> (long) data.events().events.size());
    }

    // MARK: - Anlegen

    @Test
    void theBehaviorComesFromTheLogbookAndTheLimitStandsAlone() {
        JsonNode detail = create(FELIX, map("type", "ABSTINENCE", "name", "Gewohnheit A", "color", "mint",
                "timezone", "America/New_York", "backfillHours", 48, "membersCanInvite", true,
                "abstinence", map("groupMode", true, "limit", map("behaviorId", A, "perDay", 4,
                        "behaviorName", "Falsch", "unitLabel", "Falsch"))));
        String id = id(detail);

        JsonNode config = detail.path("config");
        JsonNode limit = config.path("abstinence").path("limit");
        assertEquals("LOGBOOK", limit.path("source").asString());
        assertEquals(A, limit.path("behaviorId").asString());
        assertEquals(4, limit.path("perDay").asInt());
        assertEquals("Verhalten A", limit.path("behaviorName").asString(), "der Name kommt aus dem Logbook");
        assertEquals("Tassen", limit.path("unitLabel").asString());
        assertFalse(config.path("abstinence").path("groupMode").asBoolean(), "allein - kein Gruppenmodus");
        assertFalse(config.path("membersCanInvite").asBoolean());
        assertEquals("Europe/Berlin", config.path("timezone").asString(), "Berliner Tage wie im Logbook");
        assertEquals(336, config.path("backfillHours").asInt(), "nachtragen wie im Logbook");
        assertFalse(detail.path("canInvite").asBoolean());
        assertTrue(detail.path("rules").toString().contains("Aus dem Logbook: Verhalten A"), detail.path("rules").toString());

        JsonNode summary = detail.path("summary");
        assertEquals("Limit · höchstens 4 Tassen am Tag", summary.path("typeLine").asString());
        assertEquals("1", summary.path("headline").path("value").asString(), "heute zaehlt mit");
        assertEquals("RUNNING", summary.path("status").asString());
        assertFalse(summary.path("canCheckIn").asBoolean(), "gezaehlt wird, nicht abgehakt");
        JsonNode today = summary.path("limit");
        assertEquals(TODAY.toString(), today.path("date").asString());
        assertEquals(0, today.path("count").asInt());
        assertEquals(4, today.path("perDay").asInt());
        assertEquals("Tassen", today.path("unit").asString());
        assertEquals("heute 0 von 4 Tassen", today.path("text").asString());
        assertFalse(today.path("over").asBoolean());
        assertTrue(today.path("canCount").asBoolean());

        assertEquals("Ein Limit aus dem Logbook führst du allein.",
                post("/cohabit/api/cohabits/" + id + "/invitations", FELIX, map("personIds", List.of("torben")))
                        .expect(400).message());
        assertEquals("Ein Limit aus dem Logbook führst du allein.",
                post("/cohabit/api/cohabits/" + id + "/invite-link", FELIX, null).expect(400).message());
        assertEquals("Dieses Co-Habit zählt aus dem Logbook.",
                post("/cohabit/api/cohabits/" + id + "/checkins", FELIX, map("kind", "BREAK")).expect(403).message());
        assertEquals("Dieses Co-Habit zählt aus dem Logbook.",
                post("/cohabit/api/classic/habits/" + id + "/marks", FELIX, map("date", TODAY.toString()))
                        .expect(403).message());
    }

    @Test
    void onlyAnActiveBehaviorWithAnAmountWillDo() {
        logbook.behavior("felix", "b-old", "Verhalten C", "Tassen", true);
        assertEquals("Unbekanntes Verhalten.",
                post("/cohabit/api/cohabits", FELIX, limit("b-x", 4)).expect(400).message());
        assertEquals("Das Verhalten ist archiviert.",
                post("/cohabit/api/cohabits", FELIX, limit("b-old", 4)).expect(400).message());
        assertEquals("Das Verhalten hat keine Menge.",
                post("/cohabit/api/cohabits", FELIX, limit(YES_NO, 4)).expect(400).message());
        assertEquals("Welches Verhalten aus dem Logbook?",
                post("/cohabit/api/cohabits", FELIX, limit(" ", 4)).expect(400).message());
        assertEquals("Das Tageslimit muss zwischen 0 und 1.000 liegen.",
                post("/cohabit/api/cohabits", FELIX, limit(A, null)).expect(400).message());
        assertEquals("Das Tageslimit muss zwischen 0 und 1.000 liegen.",
                post("/cohabit/api/cohabits", FELIX, limit(A, -1)).expect(400).message());

        Map<String, Object> invited = limit(A, 4);
        invited.put("invitePersonIds", List.of("torben"));
        assertEquals("Ein Limit aus dem Logbook führst du allein.",
                post("/cohabit/api/cohabits", FELIX, invited).expect(400).message());

        Registered lena = register("Lena", "lena", FELIX);
        assertEquals("Dafür braucht es einen Healthy-Zugang.",
                post("/cohabit/api/cohabits", lena.who(), limit(A, 4)).expect(403).message());

        logbook.unavailable = true;
        assertEquals("Logbook nicht erreichbar",
                post("/cohabit/api/cohabits", FELIX, limit(A, 4)).expect(503).message());
        boolean none = store.read(data -> data.cohabits().cohabits.isEmpty());
        assertTrue(none, "nichts halb angelegt");
    }

    // MARK: - Zaehlen

    @Test
    void countingWritesTheLogbookAndTheFifthBreaksTheSeriesAtOnce() {
        String id = createDaysAgo(FELIX, limit(A, 4), 9);
        logbook.value("felix", A, TODAY.minusDays(5), 3);
        assertEquals("10", detail(id).path("summary").path("headline").path("value").asString(),
                "zehn Tage ab dem Start, drei Tassen sind erlaubt");

        JsonNode detail = count(id, TODAY, 4).expect(200).json();
        // Mit frischen Zahlen wird gefeiert, was ansonsten der Scheduler gemeldet haette.
        List<String> texts = store.read(data -> data.messages(id).stream().map(m -> m.systemText).toList());
        assertTrue(texts.contains("Felix hat 7 Tage geschafft"), texts.toString());
        long events = events();
        assertEquals(4.0, logbook.valueOf("felix", A, TODAY));
        assertEquals(4, detail.path("summary").path("limit").path("count").asInt());
        assertEquals("10", detail.path("summary").path("headline").path("value").asString(), "vier sind noch drin");
        assertEquals("heute 4 von 4 · Rekord 10 Tage", detail.path("summary").path("listLine").asString());

        detail = count(id, TODAY, 5).expect(200).json();
        assertEquals("0", detail.path("summary").path("headline").path("value").asString(), "die fünfte bricht sofort");
        assertTrue(detail.path("summary").path("limit").path("over").asBoolean());
        assertEquals("heute 5 von 4 Tassen", detail.path("summary").path("limit").path("text").asString());
        assertEquals(9, detail.path("abstinence").path("record").asInt(), "die Serie bis gestern");

        detail = count(id, TODAY, 4).expect(200).json();
        assertEquals("10", detail.path("summary").path("headline").path("value").asString(), "korrigiert - zurück");
        assertEquals(events, events(), "keine Meldung je Tasse");
        assertTrue(iosTransport.sent.isEmpty(), "kein Push je Tasse");
    }

    /** Was im Logbook steht, zaehlt - egal, wo es eingetragen wurde. */
    @Test
    void anEarlierDayCanBeCorrectedAndTheSeriesComesBack() {
        String id = createDaysAgo(FELIX, limit(A, 4), 9);
        logbook.value("felix", A, TODAY.minusDays(3), 6);
        JsonNode detail = detail(id);
        assertEquals("3", detail.path("summary").path("headline").path("value").asString());
        JsonNode days = detail.path("abstinence").path("limitDays");
        assertEquals(10, days.size(), "vom Start bis heute, neueste zuerst");
        assertEquals(TODAY.toString(), days.get(0).path("date").asString());
        assertEquals("–", days.get(0).path("countText").asString(), "nichts eingetragen");
        assertTrue(days.get(0).path("count").isNull());
        assertEquals("6", days.get(3).path("countText").asString());
        assertTrue(days.get(3).path("over").asBoolean());

        detail = count(id, TODAY.minusDays(3), 2.5).expect(200).json();
        assertEquals("10", detail.path("summary").path("headline").path("value").asString());
        assertEquals("2,5", detail.path("abstinence").path("limitDays").get(3).path("countText").asString());
        assertEquals(2.5, logbook.valueOf("felix", A, TODAY.minusDays(3)));
    }

    @Test
    void countingKeepsToTheWindowAndToLimits() {
        String id = createDaysAgo(FELIX, limit(A, 4), 20);
        assertEquals("Außerhalb der Nachtragsfrist.", count(id, TODAY.plusDays(1), 1).expect(400).message());
        assertEquals("Außerhalb der Nachtragsfrist.", count(id, TODAY.minusDays(15), 1).expect(400).message());
        count(id, TODAY.minusDays(14), 1).expect(200);
        assertEquals("Die Zahl muss zwischen 0 und 10.000 liegen.", count(id, TODAY, -1).expect(400).message());
        assertEquals("Die Zahl muss zwischen 0 und 10.000 liegen.", count(id, TODAY, 10_001).expect(400).message());
        assertEquals("Die Zahl muss zwischen 0 und 10.000 liegen.", count(id, TODAY, null).expect(400).message());

        String other = id(create(FELIX, streak("Gewohnheit B", daily())));
        assertEquals("Dieses Co-Habit zählt nicht aus dem Logbook.", count(other, TODAY, 1).expect(400).message());
        assertEquals(404, put("/cohabit/api/cohabits/" + id + "/count/" + TODAY, TORBEN_APP, map("count", 1)).status());

        // Im Logbook geloescht: dessen Grund, nicht "nicht erreichbar".
        logbook.behaviors.put("felix", new ArrayList<>());
        assertEquals("Das Verhalten gibt es im Logbook nicht mehr.", count(id, TODAY, 1).expect(400).message());
    }

    @Test
    void withoutTheLogbookThereIsNoNumberAndNoSeries() {
        String id = createDaysAgo(FELIX, limit(A, 4), 9);
        logbook.unavailable = true;

        JsonNode summary = get("/cohabit/api/cohabits", FELIX).expect(200).json().get(0);
        assertEquals("UNAVAILABLE", summary.path("status").asString());
        assertEquals("–", summary.path("headline").path("value").asString(), "nie eine falsche 0");
        assertEquals("Logbook nicht erreichbar", summary.path("unavailableText").asString());
        assertTrue(summary.path("limit").path("count").isNull());
        assertFalse(summary.path("limit").path("canCount").asBoolean());
        assertTrue(detail(id).path("abstinence").path("limitDays").isNull());

        assertEquals("Logbook nicht erreichbar", count(id, TODAY, 1).expect(503).message());
        assertEquals("Logbook nicht erreichbar",
                get("/cohabit/api/logbook/behaviors", FELIX).expect(503).message());

        logbook.unavailable = false;
        logbook.behaviors.put("felix", new ArrayList<>());
        summary = get("/cohabit/api/cohabits", FELIX).expect(200).json().get(0);
        assertEquals("Das Verhalten gibt es im Logbook nicht mehr.", summary.path("unavailableText").asString(),
                "ein geloeschtes Verhalten ist keine perfekte Serie");
    }

    // MARK: - Bearbeiten

    @Test
    void editingChangesOnlyTheLimitAndFollowsTheLogbooksName() {
        String id = id(create(FELIX, limit(A, 4)));
        logbook.behavior("felix", A, "Verhalten C", "Becher", false);

        Map<String, Object> config = limit(A, 3);
        JsonNode limit = put("/cohabit/api/cohabits/" + id, FELIX, config).expect(200).json()
                .path("config").path("abstinence").path("limit");
        assertEquals(3, limit.path("perDay").asInt());
        assertEquals("Verhalten C", limit.path("behaviorName").asString());
        assertEquals("Becher", limit.path("unitLabel").asString());

        // Ein aelterer Client kennt das Limit nicht - es bleibt.
        put("/cohabit/api/cohabits/" + id, FELIX,
                map("type", "ABSTINENCE", "name", "Gewohnheit A", "abstinence", map("groupMode", true))).expect(200);
        JsonNode kept = detail(id).path("config").path("abstinence");
        assertEquals(3, kept.path("limit").path("perDay").asInt());
        assertFalse(kept.path("groupMode").asBoolean());

        assertEquals("Das Verhalten lässt sich nicht ändern.",
                put("/cohabit/api/cohabits/" + id, FELIX, limit(YES_NO, 3)).expect(400).message());

        String plain = id(create(FELIX, map("type", "ABSTINENCE", "name", "Gewohnheit B", "color", "mint")));
        assertEquals("Ein Co-Habit zählt nachträglich nicht aus dem Logbook.",
                put("/cohabit/api/cohabits/" + plain, FELIX, limit(A, 4)).expect(400).message());
    }

    // MARK: - Drumherum

    @Test
    void theBehaviorsToChooseFromAreTheActiveOnesWithAnAmount() {
        logbook.behavior("felix", "b-old", "Verhalten C", "Tassen", true);
        JsonNode list = get("/cohabit/api/logbook/behaviors", FELIX).expect(200).json();
        assertEquals(1, list.size());
        assertEquals(A, list.get(0).path("id").asString());
        assertEquals("Verhalten A", list.get(0).path("name").asString());
        assertEquals("Tassen", list.get(0).path("unitLabel").asString());

        Registered lena = register("Lena", "lena", FELIX);
        assertEquals("Dafür braucht es einen Healthy-Zugang.",
                get("/cohabit/api/logbook/behaviors", lena.who()).expect(403).message());
    }

    @Test
    void theOverviewsShowTodaysCount() {
        String id = createDaysAgo(FELIX, limit(A, 4), 9);
        logbook.value("felix", A, TODAY, 2);

        JsonNode item = get("/cohabit/api/widget", FELIX).expect(200).json().path("cohabits").get(0);
        assertEquals("Tage · 2/4", item.path("sub").asString());

        JsonNode classic = get("/cohabit/api/classic/habits", FELIX).expect(200).json().get(0);
        assertEquals("QUIT", classic.path("kind").asString());
        assertEquals(10, classic.path("streak").asInt());
        assertEquals(2, classic.path("summary").path("limit").path("count").asInt(), "die Zeile zeigt 2/4");

        // Fuer die Effekte im Logbook ist das Limit keine eigene Reihe - es kommt ja von dort.
        JsonNode series = get("/cohabit/api/me/days?from=2026-09-24&to=2026-09-30", FELIX).expect(200).json()
                .path("series");
        for (JsonNode s : series) {
            assertFalse(id.equals(s.path("ref").path("id").asString()));
        }

        logbook.unavailable = true;
        classic = get("/cohabit/api/classic/habits", FELIX).expect(200).json().get(0);
        assertEquals("Logbook nicht erreichbar", classic.path("unavailable").asString());
        assertEquals(0, classic.path("recent").size(), "keine Punkte ohne Zahl");
        item = get("/cohabit/api/widget", FELIX).expect(200).json().path("cohabits").get(0);
        assertEquals("Logbook nicht erreichbar", item.path("sub").asString());
    }
}
