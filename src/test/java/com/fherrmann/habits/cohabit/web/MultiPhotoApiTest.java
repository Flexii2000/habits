package com.fherrmann.habits.cohabit.web;

import com.fherrmann.habits.cohabit.push.PushMessage;
import com.fherrmann.habits.cohabit.service.PhotoService;
import com.fherrmann.habits.cohabit.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Bis zu vier Beweisfotos je Eintrag - das erste bleibt photoId fuer aeltere Clients. */
class MultiPhotoApiTest extends ApiTestBase {

    private static final String UUID1 = "11111111-1111-1111-1111-111111111111";
    private static final String UUID2 = "22222222-2222-2222-2222-222222222222";

    @Autowired
    PhotoService photoService;

    private static Map<String, Object> withPhoto(boolean required) {
        Map<String, Object> config = streak("Laufen", daily());
        config.put("photoRequired", required);
        return config;
    }

    private static List<String> ids(JsonNode array) {
        return array.valueStream().map(JsonNode::asString).toList();
    }

    private List<String> storedPhotos() {
        return store.read(data -> data.photos().photos.stream().map(p -> p.id).sorted().toList());
    }

    @Test
    void mehrereFotosInEintragChatUndTimeline() {
        post("/cohabit/api/devices", TORBEN_APP, map("token", "torben-phone", "platform", "android")).expect(204);
        String id = withMembers(withPhoto(true), TORBEN_APP);
        String p1 = fakePhoto("felix");
        String p2 = fakePhoto("felix");
        String p3 = fakePhoto("felix");
        JsonNode r = checkin(id, FELIX, map("id", UUID1, "photoIds", List.of(p1, p2, p3)));
        JsonNode ch = r.path("checkin");
        assertEquals(p1, ch.path("photoId").asString(), "das erste fuer aeltere Clients");
        assertEquals(List.of(p1, p2, p3), ids(ch.path("photoIds")));

        JsonNode post = get("/cohabit/api/cohabits/" + id + "/messages", TORBEN_APP).json().path("messages")
                .valueStream().filter(m -> m.path("kind").asString().equals("CHECKIN")).findFirst().orElseThrow();
        assertEquals(p1, post.path("photoId").asString());
        assertEquals(List.of(p1, p2, p3), ids(post.path("photoIds")));
        JsonNode item = get("/cohabit/api/timeline", TORBEN_APP).json().path("items").get(0);
        assertEquals("PHOTO_CHECKIN", item.path("kind").asString());
        assertEquals(List.of(p1, p2, p3), ids(item.path("photoIds")));
        PushMessage push = androidTransport.to("torben-phone").getFirst();
        assertEquals("3 neue Beweisfotos", push.body());
        store.read(data -> {
            data.photos().photos.forEach(m -> assertEquals(id, m.cohabitId, "alle drei haengen am Co-Habit - so sehen die Mitglieder sie"));
            return null;
        });
        assertEquals(0, photoService.cleanup(clock.instant().plus(Duration.ofDays(2))), "keins ist verwaist");
    }

    @Test
    void hoechstensVierUndKeinsDoppelt() {
        String id = id(create(FELIX, withPhoto(true)));
        List<String> five = List.of(fakePhoto("felix"), fakePhoto("felix"), fakePhoto("felix"), fakePhoto("felix"),
                fakePhoto("felix"));
        Response tooMany = post("/cohabit/api/cohabits/" + id + "/checkins", FELIX, map("id", UUID1, "photoIds", five));
        assertEquals(400, tooMany.status());
        assertEquals("Höchstens 4 Fotos.", tooMany.message());
        Response twice = post("/cohabit/api/cohabits/" + id + "/checkins", FELIX,
                map("id", UUID1, "photoIds", List.of(five.get(0), five.get(0))));
        assertEquals("Ein Foto ist doppelt.", twice.message());
        JsonNode old = checkin(id, FELIX, map("id", UUID1, "photoId", five.get(0))).path("checkin");
        assertEquals(List.of(five.get(0)), ids(old.path("photoIds")), "aeltere Clients schicken nur photoId");
    }

    @Test
    void fotosNachtraeglichErgaenzenUndEntfernen() {
        String id = id(create(FELIX, withPhoto(true)));
        String p1 = fakePhoto("felix");
        String p2 = fakePhoto("felix");
        String p3 = fakePhoto("felix");
        checkin(id, FELIX, map("id", UUID1, "photoIds", List.of(p1, p2), "caption", "Regen"));

        String p4 = fakePhoto("felix");
        JsonNode edited = put("/cohabit/api/cohabits/" + id + "/checkins/" + UUID1, FELIX,
                map("photoIds", List.of(p2, p4), "caption", "Regen")).expect(200).json().path("checkin");
        assertEquals(List.of(p2, p4), ids(edited.path("photoIds")));
        assertEquals(p2, edited.path("photoId").asString());
        assertEquals(List.of(p2, p3, p4).stream().sorted().toList(), storedPhotos(), "p1 ist weg, p3 noch unbenutzt");
        JsonNode post = get("/cohabit/api/cohabits/" + id + "/messages", FELIX).json().path("messages").get(0);
        assertEquals(List.of(p2, p4), ids(post.path("photoIds")));

        JsonNode kept = put("/cohabit/api/cohabits/" + id + "/checkins/" + UUID1, FELIX, map("caption", "Sonne"))
                .expect(200).json().path("checkin");
        assertEquals(List.of(p2, p4), ids(kept.path("photoIds")), "ohne photoIds bleiben sie");

        Response none = put("/cohabit/api/cohabits/" + id + "/checkins/" + UUID1, FELIX, map("photoIds", List.of()));
        assertEquals(400, none.status());
        assertEquals("Ein Beweisfoto ist Pflicht.", none.message());

        delete("/cohabit/api/cohabits/" + id + "/checkins/" + UUID1, FELIX).expect(200);
        assertEquals(List.of(p3), storedPhotos(), "mit dem Eintrag gehen alle seine Fotos");
    }

    @Test
    void ersteFotosMachenAusDemEintragEinenPost() {
        String id = id(create(FELIX, withPhoto(false)));
        checkin(id, FELIX, map("id", UUID2));
        String p1 = fakePhoto("felix");
        put("/cohabit/api/cohabits/" + id + "/checkins/" + UUID2, FELIX, map("photoIds", List.of(p1))).expect(200);
        JsonNode item = get("/cohabit/api/timeline", FELIX).json().path("items").get(0);
        assertEquals("PHOTO_CHECKIN", item.path("kind").asString());
        assertEquals(p1, item.path("photoId").asString());
        List<String> kinds = get("/cohabit/api/cohabits/" + id + "/messages", FELIX).json().path("messages")
                .valueStream().map(m -> m.path("kind").asString()).toList();
        assertTrue(kinds.contains("CHECKIN"), kinds.toString());

        put("/cohabit/api/cohabits/" + id + "/checkins/" + UUID2, FELIX, map("photoIds", List.of())).expect(200);
        assertEquals("CHECKIN", get("/cohabit/api/timeline", FELIX).json().path("items").get(0).path("kind").asString());
        assertTrue(get("/cohabit/api/cohabits/" + id + "/messages", FELIX).json().path("messages").valueStream()
                .noneMatch(m -> m.path("kind").asString().equals("CHECKIN")), "der Post geht mit dem letzten Foto");
        assertEquals(List.of(), storedPhotos());
    }
}
