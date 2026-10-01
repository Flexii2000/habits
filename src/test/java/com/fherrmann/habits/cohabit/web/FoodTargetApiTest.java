package com.fherrmann.habits.cohabit.web;

import com.fherrmann.habits.client.FoodClient;
import com.fherrmann.habits.cohabit.service.SchedulerService;
import com.fherrmann.habits.cohabit.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Im Kalorienziel, im Wochenmittel: eine Woche Mo-So zaehlt, wenn der Schnitt der
 * getrackten Tage (Regel wie "Track food") hoechstens beim kcal-Ziel liegt. Heute ist
 * Mittwoch, der 30.09.2026; die laufende Woche begann am 28.
 */
class FoodTargetApiTest extends ApiTestBase {

    private static final int TARGET = 2300;

    @Autowired
    SchedulerService scheduler;

    /** Ein getrackter Tag (ueber die drei Hauptmahlzeiten, auch unter 80 %). */
    private static FoodClient.Day tracked(int kcal) {
        return new FoodClient.Day(kcal, TARGET, Set.of("BREAKFAST", "LUNCH", "DINNER"));
    }

    /** Nicht getrackt: unter 80 % und nicht alle Hauptmahlzeiten - zaehlt nicht mit. */
    private static FoodClient.Day untracked(int kcal) {
        return new FoodClient.Day(kcal, TARGET, Set.of("SNACK"));
    }

    private Map<LocalDate, FoodClient.Day> diary;

    private String targetHabit() {
        Map<String, Object> config = streak("Im Ziel", daily());
        config.put("auto", map("source", "FOOD_TARGET_WEEKLY"));
        return createDaysAgo(TORBEN_APP, config, 30);
    }

    private JsonNode summary(String id) {
        return get("/cohabit/api/cohabits/" + id, TORBEN_APP).expect(200).json().path("summary");
    }

    private void setDiary() {
        diary = new java.util.HashMap<>();
        // 07.-13.: im Schnitt drueber (2.600) - davor endet die Serie
        for (int d = 7; d <= 13; d++) {
            diary.put(LocalDate.of(2026, 9, d), tracked(2600));
        }
        // 14.-20.: im Schnitt 2.200
        for (int d = 14; d <= 20; d++) {
            diary.put(LocalDate.of(2026, 9, d), tracked(2200));
        }
        // 21.-27.: 2.000, und ein vergessener Tag mit 500 zaehlt nicht mit
        for (int d = 21; d <= 27; d++) {
            diary.put(LocalDate.of(2026, 9, d), d == 26 ? untracked(500) : tracked(2000));
        }
        // laufende Woche: Mo 2.400, Di 2.100, heute erst ein Snack
        diary.put(LocalDate.of(2026, 9, 28), tracked(2400));
        diary.put(LocalDate.of(2026, 9, 29), tracked(2100));
        diary.put(LocalDate.of(2026, 9, 30), untracked(300));
        food.days = (person, day) -> diary.getOrDefault(day, untracked(0));
    }

    @Test
    void zweiWochenImZielUndDieLaufendeIstNochOffen() {
        setDiary();
        String id = targetHabit();
        JsonNode s = summary(id);
        assertEquals("Streak · Kalorienziel im Wochenmittel", s.path("typeLine").asString());
        assertEquals("2", s.path("headline").path("value").asString(), "14.-20. und 21.-27., davor zu viel");
        assertEquals("Wochen", s.path("headline").path("unit").asString());
        assertEquals("RUNNING", s.path("status").asString(), "nichts abzuhaken - entschieden wird nach Sonntag");
        assertEquals("Ø 2.250 von 2.300 kcal", s.path("listLine").asString(), "(2.400 + 2.100) / 2, heute zaehlt noch nicht");
        assertEquals("bisher im Ziel", s.path("subline").asString());
        assertFalse(s.path("canCheckIn").asBoolean());

        diary.put(LocalDate.of(2026, 9, 29), tracked(2700));
        assertEquals("bisher über dem Ziel", summary(id).path("subline").asString());
        assertEquals("2", summary(id).path("headline").path("value").asString(), "die laufende Woche bricht nichts");

        JsonNode classic = null;
        for (JsonNode h : get("/cohabit/api/classic/habits", TORBEN_APP).expect(200).json()) {
            if (h.path("id").asString().equals(id)) {
                classic = h;
            }
        }
        assertEquals("FOOD", classic.path("kind").asString());
        assertEquals("WEEKS", classic.path("unit").asString());
        assertEquals(2, classic.path("streak").asInt());
        assertEquals(2550, classic.path("progress").path("value").asInt());
        assertEquals(TARGET, classic.path("progress").path("goal").asInt());
        assertFalse(classic.path("atRisk").asBoolean());
    }

    @Test
    void ohneGetracktenTagIstKeineWocheImZiel() {
        food.days = (person, day) -> untracked(0);
        String id = targetHabit();
        JsonNode s = summary(id);
        assertEquals("0", s.path("headline").path("value").asString(), "leere Wochen sind nicht im Ziel");
        assertEquals("diese Woche noch nichts getrackt", s.path("subline").asString());
    }

    @Test
    void amSonntagabendKommtKeinGefaehrdetPush() {
        setDiary();
        String id = targetHabit();
        post("/cohabit/api/devices", TORBEN_APP, map("token", "torben-phone", "platform", "android")).expect(204);
        // Sonntag, 04.10., 20:00 - eine Serie ab 2 Wochen, die laufende Woche endet heute
        clock.set(LocalDate.of(2026, 10, 4).atTime(20, 0).atZone(ZoneId.of("Europe/Berlin")).toInstant());
        scheduler.tick(clock.instant());
        assertTrue(androidTransport.ofKind("streak-at-risk").stream().noneMatch(m -> id.equals(m.cohabitId())),
                "im Ziel bleiben laesst sich nicht nachholen");
    }

    @Test
    void healthyPersonenHabenDieQuelle() {
        assertTrue(get("/cohabit/api/me", TORBEN_APP).expect(200).json().path("sources").toString()
                .contains("FOOD_TARGET_WEEKLY"));
        assertTrue(get("/cohabit/api/me", FELIX).expect(200).json().path("sources").toString()
                .contains("FOOD_TARGET_WEEKLY"));
    }
}
