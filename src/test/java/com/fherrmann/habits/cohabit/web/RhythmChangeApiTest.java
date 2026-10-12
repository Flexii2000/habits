package com.fherrmann.habits.cohabit.web;

import com.fherrmann.habits.cohabit.model.Member;
import com.fherrmann.habits.cohabit.support.ApiTestBase;
import com.fherrmann.habits.cohabit.support.TestBeans;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Rhythmus aendern: Bestserie und Meilensteine gelten danach fuer die neue Regel. Vorher
 * blieben 28 Tage als Bestserie stehen und wurden als „28 Wo." gezeigt (12.10.).
 */
class RhythmChangeApiTest extends ApiTestBase {

    /** Vier Wochen lang taeglich, dann 4x pro Woche: der Rekord zaehlt in Wochen, gemeldet wird nichts. */
    @Test
    void fromDailyToFourTimesAWeekTheRecordCountsWeeks() {
        Map<String, Object> config = streak("Gewohnheit A", daily());
        // Angelegt am Montag, 31.08.; Eintraege vom 02.09. bis 29.09., heute (Mi 30.09.) noch offen.
        String id = createDaysAgo(FELIX, config, 30);
        checkInDaily(id, 28);
        assertEquals(28, member(id).bestStreak);
        long announced = announcements();

        config.put("streak", map("rhythm", map("kind", "TIMES_PER_WEEK", "times", 4), "groupStreak", false));
        put("/cohabit/api/cohabits/" + id, FELIX, config).expect(200);

        // Vier volle Wochen (31.08. bis 27.09.); die laufende hat erst zwei Tage und ist offen.
        JsonNode record = record(id);
        assertEquals(4, record.path("value").asInt());
        assertEquals("4 Wo.", record.path("short").asString());
        Member m = member(id);
        assertEquals(4, m.bestStreak);
        assertEquals(List.of(List.of(4)), List.copyOf(m.milestones.values()), "4 Wochen still vermerkt");
        assertEquals(announced, announcements(), "das Umstellen meldet nichts");

        // Die laufende Woche wird voll: die Serie waechst weiter, dieselbe Serie wird nicht neu gefeiert.
        checkin(id, FELIX, map("id", uuid(100)));
        clock.set(TestBeans.START.plusDays(1).toInstant());
        checkin(id, FELIX, map("id", uuid(101)));
        assertEquals(5, record(id).path("value").asInt());
        assertEquals(5, member(id).bestStreak);
        assertEquals(announced, announcements());
    }

    /** Nur der Name aendert sich: Bestserie und Meilensteine bleiben, wie sie sind. */
    @Test
    void aNewNameKeepsTheRecord() {
        Map<String, Object> config = streak("Gewohnheit A", daily());
        String id = createDaysAgo(FELIX, config, 30);
        checkInDaily(id, 8);
        int best = member(id).bestStreak;
        Map<String, List<Integer>> milestones = new LinkedHashMap<>(member(id).milestones);
        assertEquals(8, best);

        config.put("name", "Gewohnheit B");
        put("/cohabit/api/cohabits/" + id, FELIX, config).expect(200);

        assertEquals(best, member(id).bestStreak);
        assertEquals(milestones, member(id).milestones);
        assertEquals(8, record(id).path("value").asInt());
    }

    /** Je Tag ein Eintrag fuer die letzten {@code days} Tage - die Uhr laeuft dafuer mit. */
    private void checkInDaily(String id, int days) {
        try {
            for (int i = days; i >= 1; i--) {
                clock.set(TestBeans.START.minusDays(i).toInstant());
                checkin(id, FELIX, map("id", uuid(i)));
            }
        } finally {
            clock.set(TestBeans.START.toInstant());
        }
    }

    private static String uuid(int n) {
        return String.format("00000000-0000-0000-0000-%012d", n);
    }

    private JsonNode record(String id) {
        return get("/cohabit/api/cohabits/" + id, FELIX).expect(200).json().path("streak").path("record");
    }

    private Member member(String id) {
        return store.read(data -> data.cohabit(id).orElseThrow().members.getFirst());
    }

    private long announcements() {
        return store.read(data -> data.events().events.stream()
                .filter(e -> e.kind.name().equals("MILESTONE") || e.kind.name().equals("NEW_BEST"))
                .count());
    }
}
