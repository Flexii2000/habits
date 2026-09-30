package com.fherrmann.habits.cohabit.web;

import com.fherrmann.habits.cohabit.push.PushMessage;
import com.fherrmann.habits.cohabit.release.ReleaseAnnouncer;
import com.fherrmann.habits.cohabit.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountApiTest extends ApiTestBase {

    @Autowired
    ReleaseAnnouncer announcer;

    private static Map<String, byte[]> unzip(byte[] zip) throws Exception {
        Map<String, byte[]> files = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                files.put(e.getName(), in.readAllBytes());
            }
        }
        return files;
    }

    @Test
    void exportEnthaeltAllesEigene() throws Exception {
        Registered lena = register("Lena", "lena", FELIX);
        Map<String, Object> config = streak("Laufen", daily());
        config.put("photoRequired", true);
        String id = withMembers(config, lena.who());
        byte[] jpeg = PhotoApiTest.image(300, 200, "jpg", false);
        var upload = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/cohabit/api/photos")
                .file(new org.springframework.mock.web.MockMultipartFile("photo", "p", "image/jpeg", jpeg));
        String photo = call(upload, lena.who()).expect(201).json().path("id").asString();
        checkin(id, lena.who(), map("id", UUID.randomUUID().toString(), "photoId", photo, "caption", "Mein Lauf"));
        post("/cohabit/api/cohabits/" + id + "/messages", lena.who(), map("id", UUID.randomUUID().toString(),
                "text", "Hallo")).expect(201);
        post("/cohabit/api/cohabits/" + id + "/messages", FELIX, map("id", UUID.randomUUID().toString(),
                "text", "Nicht von Lena")).expect(201);
        Response r = get("/cohabit/api/me/export", lena.who()).expect(200);
        assertEquals("application/zip", r.header("Content-Type"));
        assertTrue(r.header("Content-Disposition").contains("cohabit-export-2026-09-30.zip"));
        Map<String, byte[]> files = unzip(r.raw.getContentAsByteArray());
        assertTrue(files.containsKey("profile.json"));
        assertTrue(files.containsKey("photos/" + photo + ".jpg"));
        JsonNode profile = JSON.readTree(files.get("profile.json"));
        assertEquals("lena", profile.path("username").asString());
        assertEquals("felix", profile.path("friends").get(0).path("id").asString());
        JsonNode checkins = JSON.readTree(files.get("checkins.json"));
        assertEquals(1, checkins.size());
        assertEquals("Mein Lauf", checkins.get(0).path("caption").asString());
        assertEquals("photos/" + photo + ".jpg", checkins.get(0).path("photo").asString());
        String messages = new String(files.get("messages.json"), StandardCharsets.UTF_8);
        assertTrue(messages.contains("Hallo"));
        assertTrue(messages.contains("\"CHECKIN\""), "der eigene Beweisfoto-Post");
        assertFalse(messages.contains("Nicht von Lena"), "nur eigene");
        JsonNode cohabits = JSON.readTree(files.get("cohabits.json"));
        assertEquals("Laufen", cohabits.get(0).path("config").path("name").asString());
        assertEquals("MEMBER", cohabits.get(0).path("role").asString());
        assertFalse(new String(files.get("profile.json"), StandardCharsets.UTF_8).contains("\"hash\""), "keine Token-Hashes");
    }

    @Test
    void accountLoeschenRaeumtAufUndLaesstNachrichtenStehen() {
        Registered lena = register("Lena", "lena", FELIX);
        post("/cohabit/api/devices", lena.who(), map("token", "lena-phone", "platform", "android")).expect(204);
        String shared = withMembers(streak("Laufen", daily()), lena.who());
        put("/cohabit/api/cohabits/" + shared + "/admin", FELIX, map("personId", lena.id())).expect(200);
        String alone = id(create(lena.who(), streak("Nur Lena", daily())));
        checkin(shared, lena.who(), map("id", UUID.randomUUID().toString()));
        String msg = post("/cohabit/api/cohabits/" + shared + "/messages", lena.who(),
                map("id", UUID.randomUUID().toString(), "text", "Tschüss")).expect(201).json().path("id").asString();
        post("/cohabit/api/reactions", lena.who(), map("target", "message:" + msg, "reaction", "HAHA")).expect(200);

        assertEquals(400, delete("/cohabit/api/me", lena.who(), map("confirm", "ja")).status());
        delete("/cohabit/api/me", lena.who(), map("confirm", "LÖSCHEN")).expect(204);
        assertEquals(401, get("/cohabit/api/me", lena.who()).status(), "App-Token ist weg");
        store.read(data -> {
            assertTrue(data.person(lena.id()).isEmpty());
            assertTrue(data.cohabit(alone).isEmpty(), "allein - geloescht");
            var c = data.cohabit(shared).orElseThrow();
            assertEquals(1, c.members.size());
            assertEquals("ADMIN", c.members.getFirst().role.name(), "Felix uebernimmt");
            assertTrue(data.checkins(shared).isEmpty());
            var m = data.messages(shared).stream().filter(x -> x.id.equals(msg)).findFirst().orElseThrow();
            assertTrue(m.deleted);
            assertNull(m.authorId);
            assertNull(m.text);
            assertTrue(data.people().friendships.isEmpty());
            assertTrue(data.events().events.stream().noneMatch(e -> lena.id().equals(e.personId)));
            return null;
        });
        JsonNode chat = get("/cohabit/api/cohabits/" + shared + "/messages", FELIX).json().path("messages");
        boolean placeholder = false;
        for (JsonNode m : chat) {
            if (m.path("id").asString().equals(msg)) {
                placeholder = m.path("deleted").asBoolean() && m.path("author").isNull();
            }
        }
        assertTrue(placeholder, "bleibt als Nachricht geloescht");
    }

    @Test
    void healthyPersonenBehaltenIhrenZugang() {
        get("/cohabit/api/me", TORBEN_APP).expect(200);
        put("/cohabit/api/me", TORBEN_APP, map("displayName", "Torben T.", "username", "torben_t")).expect(200);
        delete("/cohabit/api/me", TORBEN_APP, map("confirm", "löschen")).expect(204);
        JsonNode fresh = get("/cohabit/api/me", TORBEN_APP).expect(200).json();
        assertEquals("Torben", fresh.path("person").path("displayName").asString(), "frisch angelegt");
        assertEquals("torben", fresh.path("person").path("username").asString());
    }

    @Test
    void androidAppVerteilenUndAnkuendigen() throws Exception {
        assertEquals(404, get("/cohabit/api/app/android", TORBEN_APP).status());
        Path dir = dataDir().resolve("android");
        Files.createDirectories(dir);
        byte[] apk = "PK-fake-apk".getBytes(StandardCharsets.UTF_8);
        Files.write(dir.resolve("cohabit.apk"), apk);
        Files.writeString(dir.resolve("latest.json"), "{\"versionCode\":3,\"versionName\":\"1.0\"}");
        JsonNode info = get("/cohabit/api/app/android", TORBEN_APP).expect(200).json();
        assertEquals(3, info.path("versionCode").asInt());
        assertEquals("1.0", info.path("versionName").asString());
        assertEquals(apk.length, info.path("sizeBytes").asInt());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(apk)), info.path("sha256").asString());
        Response download = get("/cohabit/api/app/android/apk", TORBEN_APP).expect(200);
        assertEquals("application/vnd.android.package-archive", download.header("Content-Type"));
        assertTrue(download.header("Content-Disposition").contains("cohabit-1.0.apk"));
        assertEquals(401, get("/cohabit/api/app/android/apk", Who.nobody()).status());

        post("/cohabit/api/devices", TORBEN_APP, map("token", "torben-phone", "platform", "android")).expect(204);
        post("/cohabit/api/devices", FELIX, map("token", "felix-phone", "platform", "ios")).expect(204);
        assertFalse(announcer.check(), "die vorhandene Version wird nur vermerkt");
        assertFalse(announcer.check());
        Files.writeString(dir.resolve("latest.json"), "{\"versionCode\":4,\"versionName\":\"1.1\"}");
        assertTrue(announcer.check());
        List<PushMessage> pushes = androidTransport.to("torben-phone");
        assertEquals(1, pushes.size());
        assertEquals("app-update", pushes.getFirst().kind());
        assertEquals("Neue Version 1.1", pushes.getFirst().title());
        assertEquals("4", pushes.getFirst().extra().get("versionCode"));
        assertTrue(iosTransport.to("felix-phone").isEmpty(), "nur Android");
        assertFalse(announcer.check());
    }
}
