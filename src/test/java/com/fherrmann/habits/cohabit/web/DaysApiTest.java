package com.fherrmann.habits.cohabit.web;

import com.fherrmann.habits.client.FoodClient;
import com.fherrmann.habits.cohabit.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GET /me/days}: jedes eigene Co-Habit als Tagesreihe - das Logbook in Healthy
 * rechnet sie gegen die Recovery. Heute ist Mittwoch, der 30.09.2026.
 */
class DaysApiTest extends ApiTestBase {

    private static final String RANGE = "/cohabit/api/me/days?from=2026-09-24&to=2026-09-30";

    /** Ein taegliches Co-Habit mit 14 Tagen Nachtragsfrist, angelegt vor fuenf Tagen. */
    private String daily(Who who, String name, Who... members) {
        Map<String, Object> config = streak(name, daily());
        config.put("backfillHours", 336);
        return createDaysAgo(who, config, 5, members);
    }

    private void done(String cohabitId, Who who, String date) {
        checkin(cohabitId, who, map("id", UUID.randomUUID().toString(), "kind", "DONE", "date", date));
    }

    private static JsonNode seriesOf(JsonNode view, String cohabitId) {
        for (JsonNode s : view.path("series")) {
            if (s.path("ref").path("id").asString().equals(cohabitId)) {
                return s;
            }
        }
        throw new AssertionError("keine Reihe fuer " + cohabitId);
    }

    private static Map<String, Double> days(JsonNode series) {
        Map<String, Double> m = new LinkedHashMap<>();
        series.path("days").forEach(d -> m.put(d.path("date").asString(), d.path("value").asDouble()));
        return m;
    }

    @Test
    void aDailyStreakComesAsOnesAndZerosFromItsStart() {
        String id = daily(FELIX, "Gewohnheit A");
        done(id, FELIX, "2026-09-26");
        done(id, FELIX, "2026-09-29");

        JsonNode s = seriesOf(get(RANGE, FELIX).expect(200).json(), id);

        assertEquals("BINARY", s.path("kind").asString());
        assertTrue(s.path("unit").isNull());
        // Angelegt am 25.: der 24. fehlt, "nie vor dem Start".
        Map<String, Double> expected = new LinkedHashMap<>();
        expected.put("2026-09-25", 0.0);
        expected.put("2026-09-26", 1.0);
        expected.put("2026-09-27", 0.0);
        expected.put("2026-09-28", 0.0);
        expected.put("2026-09-29", 1.0);
        expected.put("2026-09-30", 0.0);
        assertEquals(expected, days(s));
    }

    @Test
    void aSharedCohabitShowsOnlyTheOwnEntries() {
        String id = daily(FELIX, "Gewohnheit B", TORBEN_APP);
        done(id, TORBEN_APP, "2026-09-28");
        done(id, FELIX, "2026-09-29");

        Map<String, Double> felix = days(seriesOf(get(RANGE, FELIX).expect(200).json(), id));
        Map<String, Double> torben = days(seriesOf(get(RANGE, TORBEN_APP).expect(200).json(), id));

        assertEquals(0.0, felix.get("2026-09-28"));
        assertEquals(1.0, felix.get("2026-09-29"));
        assertEquals(1.0, torben.get("2026-09-28"));
        assertEquals(0.0, torben.get("2026-09-29"));
    }

    @Test
    void trackFoodComesFromTheCalorieCounterInOneCall() {
        food.days = (person, day) -> day.getDayOfMonth() % 2 == 0
                ? new FoodClient.Day(2000, 2300, Set.of("BREAKFAST", "LUNCH", "DINNER"))
                : new FoodClient.Day(300, 2300, Set.of("SNACK"));
        Map<String, Object> config = streak("Track food", daily());
        config.put("auto", map("source", "FOOD"));
        String id = createDaysAgo(FELIX, config, 3);
        food.calls.set(0);

        JsonNode s = seriesOf(get(RANGE, FELIX).expect(200).json(), id);

        assertEquals("BINARY", s.path("kind").asString());
        assertEquals("FOOD", s.path("ref").path("autoSource").asString());
        Map<String, Double> expected = new LinkedHashMap<>();
        expected.put("2026-09-27", 0.0);
        expected.put("2026-09-28", 1.0);
        expected.put("2026-09-29", 0.0);
        expected.put("2026-09-30", 1.0);
        assertEquals(expected, days(s));
        assertEquals(1, food.calls.get(), "ein Bereichsaufruf statt einer Anfrage je Tag");
    }

    @Test
    void aSourceThatIsDownMarksOnlyItsOwnCohabit() {
        Map<String, Object> config = streak("Track food", daily());
        config.put("auto", map("source", "FOOD"));
        String auto = createDaysAgo(FELIX, config, 3);
        String manual = daily(FELIX, "Gewohnheit C");
        food.unavailable = true;

        JsonNode view = get(RANGE, FELIX).expect(200).json();

        assertEquals("Kalorienzähler antwortete mit HTTP 503",
                seriesOf(view, auto).path("unavailableText").asString());
        assertTrue(seriesOf(view, auto).path("days").isEmpty());
        assertEquals(6, seriesOf(view, manual).path("days").size());
    }

    @Test
    void archivedCohabitsStayInTheirHistory() {
        String id = daily(FELIX, "Gewohnheit D");
        done(id, FELIX, "2026-09-27");
        post("/cohabit/api/cohabits/" + id + "/archive", FELIX, null).expect(200);

        JsonNode s = seriesOf(get(RANGE, FELIX).expect(200).json(), id);

        assertTrue(s.path("archived").asBoolean());
        assertEquals(1.0, days(s).get("2026-09-27"));
    }

    @Test
    void aValueStreakNamesItsUnit() {
        Map<String, Object> config = streak("Gewohnheit E", daily());
        config.put("tracking", map("mode", "VALUE", "unit", "KM"));
        config.put("backfillHours", 336);
        String id = createDaysAgo(FELIX, config, 2);
        checkin(id, FELIX, map("id", UUID.randomUUID().toString(), "kind", "DONE", "date", "2026-09-29",
                "value", 5.5));

        JsonNode s = seriesOf(get(RANGE, FELIX).expect(200).json(), id);

        assertEquals("AMOUNT", s.path("kind").asString());
        assertEquals("KM", s.path("unit").asString());
        assertEquals("Kilometer", s.path("unitLabel").asString());
        assertEquals(5.5, days(s).get("2026-09-29"));
        assertEquals(0.0, days(s).get("2026-09-30"));
    }

    @Test
    void theRangeIsChecked() {
        assertEquals("Höchstens 400 Tage auf einmal.",
                get("/cohabit/api/me/days?from=2025-01-01&to=2026-09-30", FELIX).expect(400).message());
        get("/cohabit/api/me/days?from=2026-09-30&to=2026-09-01", FELIX).expect(400);
        get("/cohabit/api/me/days?from=2026-09-01", FELIX).expect(400);
        get(RANGE, Who.nobody()).expect(401);
        LocalDate from = LocalDate.of(2026, 9, 30).minusDays(399);
        get("/cohabit/api/me/days?from=" + from + "&to=2026-09-30", FELIX).expect(200);
    }
}
