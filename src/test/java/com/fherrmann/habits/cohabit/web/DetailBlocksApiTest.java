package com.fherrmann.habits.cohabit.web;

import com.fherrmann.habits.cohabit.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Was die Detailseiten zeigen: Wochenraster, Gruppen-Streak, Abstinenz- und Ziel-Block. */
class DetailBlocksApiTest extends ApiTestBase {

    private String day(int daysAgo) {
        return java.time.LocalDate.of(2026, 9, 30).minusDays(daysAgo).toString();
    }

    private void done(String id, Who who, int daysAgo) {
        checkin(id, who, map("id", UUID.randomUUID().toString(), "date", day(daysAgo)));
    }

    @Test
    void wochenrasterMitPausenWochentagenUndGruppe() {
        Registered lena = register("Lena", "lena", FELIX);
        Map<String, Object> config = streak("Laufen", map("kind", "WEEKDAYS", "weekdays", List.of(1, 3, 5)));
        config.put("backfillHours", 72);
        config.put("streak", map("rhythm", map("kind", "WEEKDAYS", "weekdays", List.of(1, 3, 5)), "groupStreak", true));
        String id = createDaysAgo(FELIX, config, 5, lena.who());
        done(id, FELIX, 2);   // Montag
        done(id, lena.who(), 2);
        post("/cohabit/api/cohabits/" + id + "/pauses", lena.who(), map("from", day(0), "to", day(-2))).expect(200);
        JsonNode d = get("/cohabit/api/cohabits/" + id, FELIX).json();
        JsonNode rows = d.path("streak").path("week").path("rows");
        assertEquals("felix", rows.get(0).path("person").path("id").asString(), "ich zuerst");
        // Mo 28. erledigt, Di nicht faellig, Mi heute offen, Rest Zukunft.
        assertEquals("[\"DONE\",\"NOT_DUE\",\"OPEN\",\"FUTURE\",\"FUTURE\",\"FUTURE\",\"FUTURE\"]",
                rows.get(0).path("cells").toString());
        assertEquals("[\"DONE\",\"NOT_DUE\",\"PAUSED\",\"FUTURE\",\"FUTURE\",\"FUTURE\",\"FUTURE\"]",
                rows.get(1).path("cells").toString());
        assertEquals(1, d.path("streak").path("current").asInt());
        assertEquals("Tag", d.path("streak").path("unitLabel").asString());
        assertEquals(1, d.path("streak").path("group").path("current").asInt(), "Lena pausiert heute - zaehlt nicht dagegen");
        assertEquals("Tag", d.path("streak").path("group").path("unitLabel").asString());
        JsonNode members = d.path("members");
        assertEquals("PAUSED", members.get(1).path("state").asString());
        assertEquals("[\"Gruppen-Streak\",\"Nachtragen bis 72 h\",\"Europe/Berlin\"]", d.path("rules").toString());
        JsonNode lenas = get("/cohabit/api/cohabits/" + id, lena.who()).json();
        assertEquals("pausiert", lenas.path("streak").path("remainingText").asString());
        assertEquals("RUNNING", lenas.path("summary").path("status").asString());
        assertEquals("Streak · Mo, Mi, Fr", lenas.path("summary").path("typeLine").asString());
        assertEquals("Mo, Mi, Fr · pausiert", lenas.path("summary").path("listLine").asString());
    }

    @Test
    void verpassteTageSindMissed() {
        String id = createDaysAgo(FELIX, streak("Lesen", daily()), 3);
        done(id, FELIX, 1);
        JsonNode cells = get("/cohabit/api/cohabits/" + id, FELIX).json().path("streak").path("week")
                .path("rows").get(0).path("cells");
        assertEquals("[\"MISSED\",\"DONE\",\"OPEN\",\"FUTURE\",\"FUTURE\",\"FUTURE\",\"FUTURE\"]", cells.toString());
        JsonNode summary = get("/cohabit/api/cohabits/" + id, FELIX).json().path("summary");
        assertEquals(1, summary.path("progress").path("done").asInt(), "Wochenpunkte: 1 von 7 faelligen Tagen");
        assertEquals(7, summary.path("progress").path("goal").asInt());
    }

    @Test
    void abstinenzBlockMitSerienUndRekorden() {
        Registered sara = register("Sara", "sara_a", FELIX);
        Map<String, Object> config = map("type", "ABSTINENCE", "name", "Ohne Zucker", "color", "mint",
                "backfillHours", 336, "abstinence", map("groupMode", true));
        String id = createDaysAgo(FELIX, config, 12, sara.who());
        checkin(id, FELIX, map("id", UUID.randomUUID().toString(), "kind", "BREAK", "date", day(8)));
        checkin(id, sara.who(), map("id", UUID.randomUUID().toString(), "kind", "BREAK", "date", day(11)));
        JsonNode d = get("/cohabit/api/cohabits/" + id, FELIX).json();
        JsonNode a = d.path("abstinence");
        assertEquals(8, a.path("currentDays").asInt());
        assertEquals(8, a.path("record").asInt());
        assertEquals("neuer Rekord", a.path("toRecordText").asString());
        assertEquals(8, a.path("group").path("days").asInt(), "seit Felix' Unterbrechung");
        assertEquals("8", d.path("summary").path("headline").path("value").asString(), "Gruppenmodus: die Gruppe");
        assertEquals("Abstinenz · Gruppenmodus", d.path("summary").path("typeLine").asString());
        JsonNode series = a.path("series");
        assertEquals("September", series.get(0).path("label").asString());
        assertEquals(4, series.get(0).path("days").asInt());
        assertEquals("aktuell", series.get(1).path("label").asString());
        assertTrue(series.get(1).path("current").asBoolean());
        JsonNode members = a.path("members");
        assertEquals("felix", members.get(0).path("person").path("id").asString());
        assertTrue(members.get(0).path("newPersonalRecord").asBoolean(), "8 schlaegt die 4 von vorher");
        assertEquals(11, members.get(1).path("days").asInt());
        assertTrue(members.get(1).path("newPersonalRecord").asBoolean(), "11 schlaegt die eine Tag vorher");
    }

    @Test
    void einzelzielJedeGegenDasGleicheZiel() {
        Registered lena = register("Lena", "lena", FELIX);
        String id = createDaysAgo(FELIX, map("type", "GOAL", "name", "10 Läufe", "color", "periwinkle",
                "backfillHours", 336,
                "goal", map("target", 10, "deadline", "2026-10-09", "counting", "ENTRIES", "mode", "INDIVIDUAL")),
                10, lena.who());
        for (int i = 1; i <= 4; i++) {
            done(id, lena.who(), i);
        }
        done(id, FELIX, 1);
        JsonNode felix = get("/cohabit/api/cohabits/" + id, FELIX).json();
        assertEquals("10%", felix.path("summary").path("headline").path("value").asString(), "mein eigener Stand");
        assertEquals("Einzelziel · bis 09.10.", felix.path("summary").path("typeLine").asString());
        assertEquals("Eintrag hinzufügen", felix.path("summary").path("checkInLabel").asString());
        JsonNode g = felix.path("goal");
        assertEquals("1 von 10", g.path("totalText").asString());
        assertEquals("Einträge", g.path("unitLabel").asString());
        assertEquals("INDIVIDUAL", g.path("mode").asString());
        assertEquals("noch 9 Tage", g.path("remainingText").asString());
        JsonNode first = g.path("contributions").get(0);
        assertEquals(lena.id(), first.path("person").path("id").asString());
        assertEquals(0.4, first.path("fraction").asDouble(), 1e-9, "gegen das Ziel, nicht gegen die Beste");
        // 11 von 20 Tagen verstrichen: Soll 5,5 -> Lena +1,5 -> gerundet +2 (Eintraege sind ganz).
        JsonNode lenas = get("/cohabit/api/cohabits/" + id, lena.who()).json().path("goal");
        assertEquals("4 von 10", lenas.path("totalText").asString());
        assertEquals(-2, lenas.path("planDelta").asInt());
        assertEquals("−2 hinter Plan", lenas.path("planDeltaText").asString());
    }
}
