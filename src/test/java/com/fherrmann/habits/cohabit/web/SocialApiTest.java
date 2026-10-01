package com.fherrmann.habits.cohabit.web;

import com.fherrmann.habits.cohabit.push.ChatBundler;
import com.fherrmann.habits.cohabit.push.PushMessage;
import com.fherrmann.habits.cohabit.support.ApiTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SocialApiTest extends ApiTestBase {

    @Autowired
    ChatBundler bundler;

    private Registered lena;
    private Registered max;

    @BeforeEach
    void people() {
        lena = register("Lena", "lena", FELIX);
        max = register("Max", "maxb", FELIX);
        post("/cohabit/api/devices", FELIX, map("token", "felix-phone", "platform", "ios")).expect(204);
        post("/cohabit/api/devices", lena.who(), map("token", "lena-phone", "platform", "android")).expect(204);
    }

    private JsonNode say(String cohabitId, Who who, String text) {
        return post("/cohabit/api/cohabits/" + cohabitId + "/messages", who,
                map("id", UUID.randomUUID().toString(), "text", text, "photoId", null)).expect(201).json();
    }

    @Test
    void chatSchreibenLesenUndIdempotent() {
        String id = withMembers(streak("Laufen", daily()), lena.who());
        String mid = "aaaaaaaa-0000-0000-0000-000000000001";
        JsonNode m = post("/cohabit/api/cohabits/" + id + "/messages", lena.who(),
                map("id", mid, "text", "Morgen 7 Uhr?")).expect(201).json();
        assertEquals("TEXT", m.path("kind").asString());
        assertTrue(m.path("mine").asBoolean());
        assertEquals("message:" + mid, m.path("reactionTarget").asString());
        assertEquals(200, post("/cohabit/api/cohabits/" + id + "/messages", lena.who(),
                map("id", mid, "text", "Morgen 7 Uhr?")).status());
        assertEquals(409, post("/cohabit/api/cohabits/" + id + "/messages", FELIX, map("id", mid, "text", "x")).status());
        assertEquals("Die Nachricht ist leer.", post("/cohabit/api/cohabits/" + id + "/messages", FELIX,
                map("id", UUID.randomUUID().toString(), "text", "  ")).message());
        assertEquals(400, post("/cohabit/api/cohabits/" + id + "/messages", FELIX,
                map("id", UUID.randomUUID().toString(), "text", "x".repeat(2001))).status());

        JsonNode page = get("/cohabit/api/cohabits/" + id + "/messages", FELIX).expect(200).json();
        JsonNode msgs = page.path("messages");
        assertEquals("SYSTEM", msgs.get(0).path("kind").asString());
        assertEquals("Lena ist beigetreten", msgs.get(0).path("systemText").asString());
        assertEquals("Morgen 7 Uhr?", msgs.get(1).path("text").asString());
        assertFalse(msgs.get(1).path("mine").asBoolean());
        assertEquals("lena", msgs.get(1).path("author").path("username").asString());
        assertFalse(page.path("hasMore").asBoolean());
        assertEquals(2, get("/cohabit/api/cohabits/" + id, FELIX).json().path("unreadMessages").asInt());
        JsonNode read = post("/cohabit/api/cohabits/" + id + "/read", FELIX, map("lastMessageId", mid)).expect(200).json();
        assertEquals(0, read.path("unread").asInt());
        assertEquals(0, get("/cohabit/api/cohabits/" + id, FELIX).json().path("unreadMessages").asInt());
    }

    @Test
    void chatBlaetternVorUndZurueck() {
        String id = withMembers(streak("Laufen", daily()), lena.who());
        for (int i = 0; i < 7; i++) {
            say(id, lena.who(), "Nachricht " + i);
        }
        JsonNode last3 = get("/cohabit/api/cohabits/" + id + "/messages?limit=3", FELIX).json();
        assertTrue(last3.path("hasMore").asBoolean());
        assertEquals("Nachricht 4", last3.path("messages").get(0).path("text").asString());
        assertEquals("Nachricht 6", last3.path("messages").get(2).path("text").asString(), "aufsteigend");
        String oldest = last3.path("messages").get(0).path("id").asString();
        JsonNode before = get("/cohabit/api/cohabits/" + id + "/messages?limit=3&before=" + oldest, FELIX).json();
        assertEquals("Nachricht 1", before.path("messages").get(0).path("text").asString());
        assertTrue(before.path("hasMore").asBoolean(), "die Systemmeldung und Nachricht 0 sind noch davor");
        String newest = last3.path("messages").get(2).path("id").asString();
        say(id, lena.who(), "Nachricht 7");
        JsonNode after = get("/cohabit/api/cohabits/" + id + "/messages?after=" + newest, FELIX).json();
        assertEquals(1, after.path("messages").size());
        assertEquals("Nachricht 7", after.path("messages").get(0).path("text").asString());
        assertFalse(after.path("hasMore").asBoolean());
    }

    @Test
    void chatPushWirdGebuendelt() {
        String id = withMembers(streak("Laufen", daily()), lena.who());
        say(id, FELIX, "Eins");
        say(id, FELIX, "Zwei");
        say(id, FELIX, "Drei");
        List<PushMessage> pushes = androidTransport.to("lena-phone").stream().filter(p -> p.kind().equals("chat")).toList();
        assertEquals(1, pushes.size(), "hoechstens eine je Co-Habit alle zwei Minuten");
        assertEquals("Laufen", pushes.getFirst().title());
        assertEquals("Felix: Eins", pushes.getFirst().body());
        assertEquals("cohabit://cohabit/" + id + "/chat", pushes.getFirst().link());
        assertTrue(iosTransport.to("felix-phone").stream().noneMatch(p -> p.kind().equals("chat")), "nicht an mich selbst");
        bundler.flush(clock.instant().plus(Duration.ofMinutes(2)));
        pushes = androidTransport.to("lena-phone").stream().filter(p -> p.kind().equals("chat")).toList();
        assertEquals(2, pushes.size());
        assertEquals("2 neue Nachrichten", pushes.get(1).body());
    }

    @Test
    void loeschenUndMelden() {
        String id = withMembers(streak("Laufen", daily()), lena.who());
        String mine = say(id, lena.who(), "Ups").path("id").asString();
        assertEquals(403, delete("/cohabit/api/cohabits/" + id + "/messages/" + mine, FELIX).status());
        JsonNode deleted = delete("/cohabit/api/cohabits/" + id + "/messages/" + mine, lena.who()).expect(200).json();
        assertTrue(deleted.path("deleted").asBoolean());
        assertTrue(deleted.path("text").isNull());
        JsonNode rude = say(id, lena.who(), "Blödsinn");
        post("/cohabit/api/cohabits/" + id + "/messages/" + rude.path("id").asString() + "/report", FELIX,
                map("reason", "unfreundlich")).expect(204);
        assertEquals(400, post("/cohabit/api/cohabits/" + id + "/messages/" + rude.path("id").asString() + "/report",
                lena.who(), map("reason", "x")).status(), "eigene Nachricht");
        store.read(data -> {
            assertEquals(1, data.reports().reports.size());
            assertEquals("Blödsinn", data.reports().reports.getFirst().text);
            assertEquals("unfreundlich", data.reports().reports.getFirst().reason);
            return null;
        });
        PushMessage report = iosTransport.to("felix-phone").stream().filter(p -> p.kind().equals("report"))
                .findFirst().orElseThrow();
        assertEquals("Neue Meldung", report.title());
        assertEquals("Felix meldet eine Nachricht von Lena in „Laufen“: unfreundlich", report.body());
    }

    @Test
    void reaktionenAufNachrichtenUndBeweisfotosTeilenSich() {
        Map<String, Object> config = streak("Laufen", daily());
        config.put("photoRequired", true);
        String id = withMembers(config, lena.who());
        String msg = say(id, lena.who(), "Stark!").path("id").asString();
        JsonNode r = post("/cohabit/api/reactions", FELIX, map("target", "message:" + msg, "reaction", "STARK"))
                .expect(200).json();
        post("/cohabit/api/reactions", FELIX, map("target", "message:" + msg, "reaction", "STARK")).expect(200);
        r = post("/cohabit/api/reactions", lena.who(), map("target", "message:" + msg, "reaction", "stark")).expect(200).json();
        assertEquals("[{\"reaction\":\"STARK\",\"label\":\"Stark\",\"count\":2,\"mine\":true}]",
                r.path("reactions").toString());
        assertEquals(400, post("/cohabit/api/reactions", FELIX, map("target", "message:" + msg, "reaction", "LIKE")).status());
        assertEquals(404, post("/cohabit/api/reactions", max.who(), map("target", "message:" + msg, "reaction", "HAHA")).status());

        String photo = fakePhoto(lena.id());
        checkin(id, lena.who(), map("id", UUID.randomUUID().toString(), "photoId", photo, "caption", "Regenlauf"));
        JsonNode item = get("/cohabit/api/timeline", FELIX).json().path("items").get(0);
        assertEquals("PHOTO_CHECKIN", item.path("kind").asString());
        String target = item.path("reactionTarget").asString();
        post("/cohabit/api/reactions", FELIX, map("target", target, "reaction", "RESPEKT")).expect(200);
        JsonNode post = get("/cohabit/api/cohabits/" + id + "/messages", FELIX).json().path("messages");
        JsonNode checkinPost = post.get(post.size() - 1);
        assertEquals("CHECKIN", checkinPost.path("kind").asString());
        assertEquals(target, checkinPost.path("reactionTarget").asString());
        assertEquals("Regenlauf", checkinPost.path("checkin").path("caption").asString());
        assertEquals(1, checkinPost.path("reactions").get(0).path("count").asInt(), "dieselben Reaktionen wie in der Timeline");
        // Auch ueber die Nachricht selbst landet die Reaktion am Ereignis.
        post("/cohabit/api/reactions", lena.who(), map("target", "message:" + checkinPost.path("id").asString(),
                "reaction", "RESPEKT")).expect(200);
        assertEquals(2, get("/cohabit/api/timeline", FELIX).json().path("items").get(0).path("reactions").get(0)
                .path("count").asInt());
        JsonNode removed = delete("/cohabit/api/reactions?target=" + target + "&reaction=RESPEKT", FELIX).expect(200).json();
        assertEquals(1, removed.path("reactions").get(0).path("count").asInt());
        assertFalse(removed.path("reactions").get(0).path("mine").asBoolean());
    }

    @Test
    void timelineMitTextenFilterUndBlaettern() {
        String laufen = createDaysAgo(FELIX, streak("Laufen", daily()), 3, lena.who());
        String zucker = createDaysAgo(FELIX, map("type", "ABSTINENCE", "name", "Ohne Zucker", "color", "mint"), 3,
                lena.who());
        put("/cohabit/api/cohabits/" + zucker + "/settings/me", lena.who(), map("shareBreaks", true)).expect(200);
        checkin(laufen, FELIX, map("id", UUID.randomUUID().toString(), "date", "2026-09-29"));
        checkin(laufen, lena.who(), map("id", UUID.randomUUID().toString()));
        checkin(zucker, lena.who(), map("id", UUID.randomUUID().toString(), "kind", "BREAK"));
        JsonNode items = get("/cohabit/api/timeline", FELIX).expect(200).json().path("items");
        assertEquals(3, items.size());
        assertEquals("BREAK", items.get(0).path("kind").asString());
        assertEquals("Lena hat „Ohne Zucker“ unterbrochen", items.get(0).path("title").asString());
        assertEquals("CHECKIN", items.get(1).path("kind").asString());
        assertEquals("Lena hat Laufen abgehakt", items.get(1).path("title").asString());
        assertEquals("10:00 · Serie 1 Tag", items.get(1).path("subtitle").asString());
        assertEquals("10:00 · Serie 1 Tag · für 29.09.", items.get(2).path("subtitle").asString());
        assertEquals("2026-09-30", items.get(2).path("day").asString());
        assertTrue(items.get(2).path("canReply").asBoolean());
        assertEquals("Laufen", items.get(1).path("cohabit").path("name").asString());

        JsonNode filtered = get("/cohabit/api/timeline?cohabitId=" + laufen, FELIX).json().path("items");
        assertEquals(2, filtered.size());
        // Der Filter der Clients blendet aus statt auszuwaehlen.
        assertEquals(2, get("/cohabit/api/timeline?exclude=" + zucker, FELIX).json().path("items").size());
        assertEquals(0, get("/cohabit/api/timeline?exclude=" + laufen + "," + zucker, FELIX).json()
                .path("items").size());
        assertEquals(0, get("/cohabit/api/timeline?exclude=" + laufen + "&exclude=" + zucker, FELIX).json()
                .path("items").size(), "auch mehrfach");
        assertEquals(3, get("/cohabit/api/timeline?exclude=c-gibt-es-nicht", FELIX).json().path("items").size(),
                "eine geloeschte ID im gespeicherten Filter stoert nicht");
        JsonNode page1 = get("/cohabit/api/timeline?limit=1&exclude=" + zucker, FELIX).json();
        assertTrue(page1.path("hasMore").asBoolean());
        JsonNode page2 = get("/cohabit/api/timeline?limit=5&exclude=" + zucker + "&before="
                + page1.path("items").get(0).path("id").asString(), FELIX).json();
        assertEquals(1, page2.path("items").size());
        assertFalse(page2.path("hasMore").asBoolean());
        JsonNode first = get("/cohabit/api/timeline?limit=1", FELIX).json();
        assertTrue(first.path("hasMore").asBoolean());
        JsonNode next = get("/cohabit/api/timeline?limit=5&before=" + first.path("items").get(0).path("id").asString(),
                FELIX).json();
        assertEquals(2, next.path("items").size());
        assertFalse(next.path("hasMore").asBoolean());

        post("/cohabit/api/blocks", FELIX, map("personId", lena.id())).expect(204);
        assertEquals(1, get("/cohabit/api/timeline", FELIX).json().path("items").size(), "Blockierte sind ausgeblendet");
        say(laufen, lena.who(), "hallo?");
        JsonNode chat = get("/cohabit/api/cohabits/" + laufen + "/messages", FELIX).json().path("messages");
        for (JsonNode m : chat) {
            assertFalse("hallo?".equals(m.path("text").asString(null)));
        }
    }

    @Test
    void neueBeweisfotosBisZumBlickInDieTimeline() {
        Map<String, Object> config = streak("Laufen", daily());
        config.put("photoRequired", true);
        String id = withMembers(config, lena.who());
        checkin(id, lena.who(), map("id", UUID.randomUUID().toString(), "photoId", fakePhoto(lena.id())));
        assertEquals(1, get("/cohabit/api/today", FELIX).json().path("newPhotos").path("count").asInt());
        String newest = get("/cohabit/api/timeline", FELIX).json().path("items").get(0).path("id").asString();
        post("/cohabit/api/timeline/seen", FELIX, map("lastEventId", newest)).expect(204);
        assertEquals(0, get("/cohabit/api/today", FELIX).json().path("newPhotos").path("count").asInt());
        assertEquals(400, post("/cohabit/api/timeline/seen", FELIX, map("lastEventId", "nope")).status());
    }

    @Test
    void stupsenEinmalAmTagUndZurueck() {
        String id = withMembers(streak("Laufen", daily()), lena.who(), max.who());
        JsonNode nudge = post("/cohabit/api/cohabits/" + id + "/nudges", FELIX, map("to", lena.id(), "text", null))
                .expect(201).json();
        assertEquals("Heute noch „Laufen“?", nudge.path("text").asString());
        assertEquals("felix", nudge.path("from").path("id").asString());
        PushMessage push = androidTransport.to("lena-phone").stream().filter(p -> p.kind().equals("nudge"))
                .findFirst().orElseThrow();
        assertEquals("Felix hat dich angestupst", push.title());
        assertEquals("cohabit://today", push.link());
        Response again = post("/cohabit/api/cohabits/" + id + "/nudges", FELIX, map("to", lena.id()));
        assertEquals(429, again.status());
        assertEquals("Heute schon angestupst.", again.message());

        JsonNode banner = get("/cohabit/api/today", lena.who()).json().path("nudges");
        assertEquals(1, banner.size());
        assertEquals("Laufen", banner.get(0).path("cohabit").path("name").asString());
        // Felix hat heute schon erledigt - Zurueckstupsen geht trotzdem.
        checkin(id, FELIX, map("id", UUID.randomUUID().toString()));
        assertEquals(400, post("/cohabit/api/cohabits/" + id + "/nudges", max.who(), map("to", "felix")).status(),
                "ohne Stupser vorher nur bei Offenen");
        post("/cohabit/api/cohabits/" + id + "/nudges", lena.who(), map("to", "felix", "text", "Du auch!")).expect(201);
        post("/cohabit/api/nudges/" + banner.get(0).path("id").asString() + "/seen", lena.who(), null).expect(204);
        assertEquals(0, get("/cohabit/api/today", lena.who()).json().path("nudges").size());
        assertEquals(400, post("/cohabit/api/cohabits/" + id + "/nudges", FELIX,
                map("to", lena.id(), "text", "x".repeat(61))).status());
        assertEquals(400, post("/cohabit/api/cohabits/" + id + "/nudges", FELIX, map("to", "felix")).status());
    }
}
