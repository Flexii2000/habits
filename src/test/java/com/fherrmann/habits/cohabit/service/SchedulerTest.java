package com.fherrmann.habits.cohabit.service;

import com.fherrmann.habits.client.FoodClient;
import com.fherrmann.habits.cohabit.push.PushMessage;
import com.fherrmann.habits.cohabit.support.ApiTestBase;
import com.fherrmann.habits.cohabit.support.TestBeans;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Der Scheduler mit fester Uhr - jeder Lauf von Hand. */
class SchedulerTest extends ApiTestBase {

    @Autowired
    SchedulerService scheduler;

    private Registered lena;

    @BeforeEach
    void people() {
        lena = register("Lena", "lena", FELIX);
        post("/cohabit/api/devices", FELIX, map("token", "felix-phone", "platform", "ios")).expect(204);
        post("/cohabit/api/devices", lena.who(), map("token", "lena-phone", "platform", "android")).expect(204);
    }

    private void at(int day, int hour, int minute) {
        clock.set(ZonedDateTime.of(2026, 9, day, hour, minute, 0, 0, TestBeans.BERLIN));
    }

    private void atOct(int day, int hour, int minute) {
        clock.set(ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, TestBeans.BERLIN));
    }

    private void tick() {
        scheduler.tick(clock.instant());
    }

    private List<PushMessage> felix(String kind) {
        return iosTransport.to("felix-phone").stream().filter(p -> p.kind().equals(kind)).toList();
    }

    private List<PushMessage> lena(String kind) {
        return androidTransport.to("lena-phone").stream().filter(p -> p.kind().equals(kind)).toList();
    }

    @Test
    void erinnerungEinmalUndNurAnOffene() {
        Map<String, Object> config = streak("Laufen", map("kind", "TIMES_PER_WEEK", "times", 3));
        config.put("reminderTime", "07:30");
        at(30, 6, 0);
        String id = withMembers(config, lena.who());
        checkin(id, lena.who(), map("id", UUID.randomUUID().toString()));
        at(30, 7, 29);
        tick();
        assertTrue(felix("reminder").isEmpty());
        at(30, 7, 31);
        tick();
        List<PushMessage> reminders = felix("reminder");
        assertEquals(1, reminders.size());
        assertEquals("Laufen", reminders.getFirst().title());
        assertEquals("Noch 3 Einträge diese Woche", reminders.getFirst().body());
        assertEquals("cohabit://cohabit/" + id + "/checkin", reminders.getFirst().link());
        assertTrue(lena("reminder").isEmpty(), "Lena hat heute schon");
        at(30, 7, 45);
        tick();
        assertEquals(1, felix("reminder").size(), "nichts doppelt");
        store.read(data -> {
            assertTrue(data.scheduler().sent.containsKey("reminder:" + id + ":felix:2026-09-30"));
            return null;
        });
    }

    @Test
    void stummgeschaltetUndErinnerungenAusBleibenStill() {
        Map<String, Object> config = streak("Lesen", daily());
        config.put("reminderTime", "21:00");
        at(30, 6, 0);
        String id = withMembers(config, lena.who());
        put("/cohabit/api/cohabits/" + id + "/settings/me", FELIX, map("muted", true)).expect(200);
        put("/cohabit/api/me/notifications", lena.who(), map("reminders", false)).expect(200);
        at(30, 21, 5);
        tick();
        assertTrue(felix("reminder").isEmpty());
        assertTrue(lena("reminder").isEmpty());
    }

    @Test
    void umZwanzigUhrGefaehrdeteSerien() {
        at(27, 9, 0);
        String id = withMembers(streak("Lesen", daily()), lena.who());
        checkin(id, FELIX, map("id", UUID.randomUUID().toString()));
        checkin(id, lena.who(), map("id", UUID.randomUUID().toString()));
        at(28, 9, 0);
        checkin(id, FELIX, map("id", UUID.randomUUID().toString()));
        at(29, 9, 0);
        checkin(id, FELIX, map("id", UUID.randomUUID().toString()));
        at(30, 19, 59);
        tick();
        assertTrue(felix("streak-at-risk").isEmpty());
        at(30, 20, 1);
        tick();
        List<PushMessage> risk = felix("streak-at-risk");
        assertEquals(1, risk.size());
        assertEquals("Deine Serie ist gefährdet", risk.getFirst().title());
        assertEquals("Lesen: 3 Tage – heute noch abhaken", risk.getFirst().body());
        assertTrue(lena("streak-at-risk").isEmpty(), "Lenas Serie ist schon gerissen");
        at(30, 20, 10);
        tick();
        assertEquals(1, felix("streak-at-risk").size());
    }

    @Test
    void challengeEineStundeVorherUndDasEndeMitNeuerRunde() {
        at(27, 9, 0);
        String id = withMembers(map("type", "CHALLENGE", "name", "Wer kocht öfter?", "color", "butter",
                "challenge", map("start", "2026-09-01", "end", "2026-09-30", "scoring", "MOST_ENTRIES",
                        "stake", "Verlierer kocht für alle", "recurrence", "MONTHLY")), lena.who());
        checkin(id, lena.who(), map("id", UUID.randomUUID().toString()));
        at(28, 9, 0);
        checkin(id, lena.who(), map("id", UUID.randomUUID().toString()));
        checkin(id, FELIX, map("id", UUID.randomUUID().toString()));
        at(30, 22, 30);
        tick();
        assertTrue(felix("challenge-ending").isEmpty());
        at(30, 23, 1);
        tick();
        PushMessage ending = felix("challenge-ending").getFirst();
        assertEquals("„Wer kocht öfter?“ endet in einer Stunde", ending.title());
        assertEquals("Du bist auf Platz 2.", ending.body());
        at(30, 23, 30);
        tick();
        assertEquals(1, felix("challenge-ending").size());

        atOct(1, 0, 1);
        tick();
        JsonNode d = get("/cohabit/api/cohabits/" + id, FELIX).json();
        JsonNode ch = d.path("challenge");
        assertEquals(2, ch.path("round").asInt());
        assertEquals("2026-10-01", ch.path("start").asString());
        assertEquals("2026-10-31", ch.path("end").asString(), "ein ganzer Monat bleibt ein ganzer Monat");
        assertEquals("Oktober", ch.path("periodLabel").asString());
        assertEquals("September", ch.path("pastRounds").get(0).path("label").asString());
        assertEquals("Lena gewinnt „Wer kocht öfter?“", d.path("dialog").path("title").asString());
        assertEquals("Verlierer kocht für alle: Felix ist dran.", d.path("dialog").path("stakeText").asString());
        assertEquals("Die nächste Runde startet am 1. Oktober automatisch.", d.path("dialog").path("nextText").asString());
        PushMessage ended = felix("challenge-ended").getFirst();
        assertEquals("Lena gewinnt „Wer kocht öfter?“", ended.title());
        assertEquals("cohabit://cohabit/" + id, ended.link());
        List<String> texts = store.read(data -> data.messages(id).stream().map(m -> m.systemText).toList());
        assertTrue(texts.contains("Lena gewinnt „Wer kocht öfter?“"));
        assertTrue(texts.contains("Runde 2 hat begonnen (Oktober)"));
        store.read(data -> {
            assertEquals(1, data.events().events.stream().filter(e -> e.kind.name().equals("CHALLENGE_ENDED")).count());
            return null;
        });
        JsonNode item = get("/cohabit/api/timeline", lena.who()).json().path("items").get(0);
        assertEquals("CHALLENGE_ENDED", item.path("kind").asString());
        assertEquals("Lena gewinnt „Wer kocht öfter?“", item.path("title").asString());
        assertEquals(1, get("/cohabit/api/me", lena.who()).json().path("counts").path("wins").asInt());
        // "Gratulieren": STARK auf die Systemmeldung.
        String target = d.path("dialog").path("reactionTarget").asString();
        post("/cohabit/api/reactions", FELIX, map("target", target, "reaction", "STARK")).expect(200);
    }

    @Test
    void zielEndetNachDerDeadline() {
        at(20, 9, 0);
        String id = withMembers(map("type", "GOAL", "name", "10 Läufe", "color", "periwinkle",
                "goal", map("target", 3, "deadline", "2026-09-29", "counting", "ENTRIES", "mode", "TEAM")), lena.who());
        for (int i = 0; i < 3; i++) {
            checkin(id, i == 0 ? FELIX : lena.who(), map("id", UUID.randomUUID().toString()));
        }
        at(29, 23, 0);
        tick();
        assertTrue(felix("goal-finished").isEmpty());
        at(30, 0, 5);
        tick();
        PushMessage done = felix("goal-finished").getFirst();
        assertEquals("„10 Läufe“: Ziel erreicht", done.title());
        assertEquals("3 von 3", done.body());
        JsonNode d = get("/cohabit/api/cohabits/" + id, FELIX).json();
        assertTrue(d.path("goal").path("finished").path("reached").asBoolean());
        assertEquals("goal", d.path("dialog").path("id").asString());
        assertEquals("GOAL", d.path("dialog").path("kind").asString());
        assertEquals("Ziel erreicht: „10 Läufe“", d.path("dialog").path("title").asString());
        assertEquals("Lena", d.path("dialog").path("podium").get(0).path("person").path("displayName").asString());
        assertFalse(d.path("summary").path("canCheckIn").asBoolean());
        List<String> texts = store.read(data -> data.messages(id).stream().map(m -> m.systemText).toList());
        assertTrue(texts.contains("Ziel erreicht: 3 von 3"));
        tick();
        assertEquals(1, felix("goal-finished").size(), "einmal");
    }

    @Test
    void abstinenzMeilensteinOhneEintrag() {
        at(23, 9, 0);
        String id = withMembers(map("type", "ABSTINENCE", "name", "Ohne Zucker", "color", "mint"), lena.who());
        at(28, 12, 0);
        tick();
        store.read(data -> {
            assertEquals(0, data.events().events.size(), "sechs Tage - noch nicht");
            return null;
        });
        at(29, 0, 1);
        tick();
        List<String> texts = store.read(data -> data.messages(id).stream().map(m -> m.systemText).toList());
        assertTrue(texts.contains("Felix hat 7 Tage geschafft"));
        assertTrue(texts.contains("Lena hat 7 Tage geschafft"));
        assertEquals("Lena hat 7 Tage geschafft", felix("milestone").getFirst().title());
        tick();
        assertEquals(1, felix("milestone").size());
    }

    @Test
    void abgelaufeneEinladungslinksVerschwinden() {
        at(1, 9, 0);
        String id = id(create(FELIX, streak("Laufen", daily())));
        String code = post("/cohabit/api/cohabits/" + id + "/invite-link", FELIX, null).expect(200).json()
                .path("code").asString();
        at(15, 10, 0);
        assertEquals(404, get("/cohabit/api/invite-links/" + code, Who.nobody()).status(), "nach 14 Tagen abgelaufen");
        at(16, 10, 0);
        tick();
        store.read(data -> {
            assertTrue(data.cohabits().inviteLinks.stream().noneMatch(l -> l.code.equals(code)));
            return null;
        });
    }

    @Test
    void automatischeQuellenWerdenStillErfasst() {
        food.days = (person, day) -> new FoodClient.Day(2000, 2300, Set.of());
        Map<String, Object> config = streak("Track food", daily());
        config.put("auto", map("source", "FOOD"));
        at(20, 9, 0);
        String id = id(create(FELIX, config));
        store.update(tx -> tx.cohabitsW().cohabits.stream().filter(c -> c.id.equals(id)).findFirst().orElseThrow()
                .members.getFirst().baselinePending = true);
        at(30, 9, 15);
        tick();
        store.read(data -> {
            var m = data.cohabit(id).orElseThrow().members.getFirst();
            assertFalse(m.baselinePending);
            assertEquals(730, m.bestStreak, "die Quelle schaut wie bisher bis zu 730 Tage zurueck");
            assertTrue(data.events().events.isEmpty(), "alte Erfolge werden nicht gefeiert");
            return null;
        });
        // Danach werden neue gefeiert: ein neuer Tag, ein neuer Meilenstein gibt es erst bei ... nichts - also still.
        at(30, 9, 30);
        tick();
        store.read(data -> {
            assertTrue(data.events().events.isEmpty());
            return null;
        });
        assertNotNull(store.read(data -> data.cohabit(id).orElseThrow().members.getFirst().milestones));
    }

    @Test
    void erinnerungKurzVorMitternacht() {
        assertTrue(SchedulerService.inWindow(ZonedDateTime.of(2026, 9, 30, 23, 50, 0, 0, TestBeans.BERLIN),
                java.time.LocalTime.of(23, 45)));
        assertFalse(SchedulerService.inWindow(ZonedDateTime.of(2026, 9, 30, 0, 5, 0, 0, TestBeans.BERLIN),
                java.time.LocalTime.of(23, 45)));
        assertTrue(SchedulerService.inWindow(ZonedDateTime.of(2026, 9, 30, 7, 59, 0, 0, TestBeans.BERLIN),
                java.time.LocalTime.of(7, 30)));
        assertFalse(SchedulerService.inWindow(ZonedDateTime.of(2026, 9, 30, 8, 0, 0, 0, TestBeans.BERLIN),
                java.time.LocalTime.of(7, 30)));
        assertEquals(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 9, 30));
        assertEquals(Duration.ofMinutes(30), SchedulerService.WINDOW);
    }
}
