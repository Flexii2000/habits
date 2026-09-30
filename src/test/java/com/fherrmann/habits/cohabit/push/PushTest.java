package com.fherrmann.habits.cohabit.push;

import com.fherrmann.habits.cohabit.model.Cohabit;
import com.fherrmann.habits.cohabit.model.Member;
import com.fherrmann.habits.cohabit.model.Person;
import com.fherrmann.habits.cohabit.model.Role;
import com.fherrmann.habits.cohabit.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PushTest extends ApiTestBase {

    private static final PushMessage CHECKIN = new PushMessage("checkin", "Lena hat Laufen abgehakt",
            "Regenlauf zählt doppelt.", "c-123", "cohabit://cohabit/c-123");

    @Test
    void apnsBekommtAlertUndDaten() {
        JsonNode p = JsonMapper.builder().build().readTree(ApnsClient.payload(CHECKIN));
        assertEquals("Lena hat Laufen abgehakt", p.path("aps").path("alert").path("title").asString());
        assertEquals("Regenlauf zählt doppelt.", p.path("aps").path("alert").path("body").asString());
        assertEquals("default", p.path("aps").path("sound").asString());
        assertEquals("c-123", p.path("aps").path("thread-id").asString());
        assertEquals("checkin", p.path("kind").asString());
        assertEquals("cohabit://cohabit/c-123", p.path("link").asString());
        assertEquals("c-123", p.path("cohabitId").asString());
        assertEquals("Lena hat Laufen abgehakt", p.path("title").asString());
    }

    @Test
    void androidBekommtNurDaten() {
        Map<String, Object> body = FcmClient.body("tok", new PushMessage("app-update", "Neue Version 1.2",
                "Antippen zum Installieren.", null, null, Map.of("versionCode", "12", "versionName", "1.2")));
        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) body.get("message");
        assertEquals("tok", message.get("token"));
        assertFalse(message.containsKey("notification"), "reine Datennachricht");
        @SuppressWarnings("unchecked")
        Map<String, String> data = (Map<String, String>) message.get("data");
        assertEquals("app-update", data.get("kind"));
        assertEquals("12", data.get("versionCode"));
        assertFalse(data.containsKey("cohabitId"));
        assertEquals(Map.of("priority", "high", "ttl", "86400s"), message.get("android"));
        assertTrue(FcmClient.isGone(404, "{\"error\":{\"status\":\"NOT_FOUND\",\"details\":[{\"errorCode\":\"UNREGISTERED\"}]}}"));
        assertFalse(FcmClient.isGone(404, "project not found"));
    }

    @Test
    void schalterStummUndBlocksEntscheiden() {
        Person p = new Person();
        p.id = "lena";
        Cohabit c = new Cohabit();
        Member m = new Member();
        m.personId = "lena";
        m.role = Role.MEMBER;
        c.members.add(m);
        assertTrue(Notifier.wants(p, Setting.CHECKINS, c, "felix"));
        m.settings.checkins = false;
        assertFalse(Notifier.wants(p, Setting.CHECKINS, c, "felix"), "je Co-Habit aus");
        assertFalse(Notifier.wants(p, Setting.PHOTOS, c, "felix"), "gilt fuer Beweisfotos mit");
        m.settings.checkins = null;
        p.notifications.photos = false;
        assertTrue(Notifier.wants(p, Setting.CHECKINS, c, "felix"));
        assertFalse(Notifier.wants(p, Setting.PHOTOS, c, "felix"), "global aus");
        m.settings.chat = true;
        p.notifications.chat = false;
        assertTrue(Notifier.wants(p, Setting.CHAT, c, "felix"), "je Co-Habit an schlaegt global aus");
        m.settings.muted = true;
        assertFalse(Notifier.wants(p, Setting.CHAT, c, "felix"));
        assertFalse(Notifier.wants(p, Setting.NUDGES, c, "felix"));
        assertTrue(Notifier.wants(p, Setting.INVITES, c, "felix"), "Einladungen trotz Stummschaltung");
        assertTrue(Notifier.wants(p, Setting.ALWAYS, c, null));
        p.blocked.add("max");
        assertFalse(Notifier.wants(p, Setting.INVITES, null, "max"), "nichts von Blockierten");
    }

    @Test
    void abgelehnteKennungenFliegenRaus() {
        Registered lena = register("Lena", "lena", FELIX);
        post("/cohabit/api/devices", lena.who(), map("token", "tot", "platform", "android")).expect(204);
        post("/cohabit/api/devices", lena.who(), map("token", "lebendig", "platform", "android")).expect(204);
        androidTransport.dead.add("tot");
        String id = withMembers(streak("Laufen", daily()), lena.who());
        checkin(id, FELIX, map("id", java.util.UUID.randomUUID().toString()));
        assertEquals(1, androidTransport.to("lebendig").size());
        store.read(data -> {
            List<String> tokens = data.person(lena.id()).orElseThrow().devices.stream().map(d -> d.token).toList();
            assertEquals(List.of("lebendig"), tokens);
            return null;
        });
    }
}
