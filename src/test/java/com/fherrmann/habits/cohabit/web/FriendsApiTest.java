package com.fherrmann.habits.cohabit.web;

import com.fherrmann.habits.cohabit.push.PushMessage;
import com.fherrmann.habits.cohabit.support.ApiTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FriendsApiTest extends ApiTestBase {

    @BeforeEach
    void bothExist() {
        get("/cohabit/api/me", FELIX).expect(200);
        get("/cohabit/api/me", TORBEN_APP).expect(200);
        post("/cohabit/api/devices", TORBEN_APP, map("token", "torben-phone", "platform", "android")).expect(204);
    }

    @Test
    void anfrageAnnehmenMachtBeideZuFreunden() {
        JsonNode request = post("/cohabit/api/friends/requests", FELIX, map("username", "torben")).expect(201).json();
        assertEquals("felix", request.path("from").path("id").asString());
        List<PushMessage> pushes = androidTransport.to("torben-phone");
        assertEquals(1, pushes.size());
        assertEquals("friend-request", pushes.getFirst().kind());
        assertEquals("cohabit://friends", pushes.getFirst().link());

        assertEquals(409, post("/cohabit/api/friends/requests", FELIX, map("username", "torben")).status());
        JsonNode torbens = get("/cohabit/api/friends", TORBEN_APP).expect(200).json();
        assertEquals(1, torbens.path("incoming").size());
        assertEquals(1, get("/cohabit/api/me", TORBEN_APP).json().path("incomingFriendRequests").asInt());

        JsonNode after = post("/cohabit/api/friends/requests/" + request.path("id").asString() + "/accept",
                TORBEN_APP, null).expect(200).json();
        assertEquals("felix", after.path("friends").get(0).path("id").asString());
        assertEquals(0, after.path("incoming").size());
        assertEquals(1, get("/cohabit/api/me", FELIX).json().path("counts").path("friends").asInt());
        assertEquals(409, post("/cohabit/api/friends/requests", FELIX, map("username", "torben")).status());

        delete("/cohabit/api/friends/felix", TORBEN_APP).expect(204);
        assertEquals(0, get("/cohabit/api/friends", FELIX).json().path("friends").size());
    }

    @Test
    void ablehnenUndZuruecknehmen() {
        String id = post("/cohabit/api/friends/requests", FELIX, map("username", "torben")).expect(201)
                .json().path("id").asString();
        post("/cohabit/api/friends/requests/" + id + "/decline", TORBEN_APP, null).expect(200);
        assertEquals(0, get("/cohabit/api/friends", FELIX).json().path("outgoing").size());
        String again = post("/cohabit/api/friends/requests", FELIX, map("username", "torben")).expect(201)
                .json().path("id").asString();
        assertEquals(403, post("/cohabit/api/friends/requests/" + again + "/accept", FELIX, null).status());
        post("/cohabit/api/friends/requests/" + again + "/decline", FELIX, null).expect(200);
    }

    @Test
    void sucheKenntDieBeziehung() {
        JsonNode results = get("/cohabit/api/people/search?q=tor", FELIX).expect(200).json();
        assertEquals(1, results.size());
        assertEquals("NONE", results.get(0).path("relation").asString());
        post("/cohabit/api/friends/requests", FELIX, map("username", "torben")).expect(201);
        assertEquals("REQUEST_SENT", get("/cohabit/api/people/search?q=tor", FELIX).json().get(0)
                .path("relation").asString());
        assertEquals("REQUEST_RECEIVED", get("/cohabit/api/people/search?q=fel", TORBEN_APP).json().get(0)
                .path("relation").asString());
        assertEquals("SELF", get("/cohabit/api/people/search?q=fel", FELIX).json().get(0)
                .path("relation").asString());
        assertEquals(400, get("/cohabit/api/people/search?q=f", FELIX).status());
    }

    @Test
    void blockierenTrenntUndVersteckt() {
        String id = post("/cohabit/api/friends/requests", FELIX, map("username", "torben")).expect(201)
                .json().path("id").asString();
        post("/cohabit/api/friends/requests/" + id + "/accept", TORBEN_APP, null).expect(200);
        post("/cohabit/api/blocks", TORBEN_APP, map("personId", "felix")).expect(204);
        assertEquals(0, get("/cohabit/api/friends", TORBEN_APP).json().path("friends").size());
        assertEquals(0, get("/cohabit/api/people/search?q=fel", TORBEN_APP).json().size());
        assertEquals(0, get("/cohabit/api/people/search?q=tor", FELIX).json().size(), "auch umgekehrt unsichtbar");
        assertEquals(404, post("/cohabit/api/friends/requests", FELIX, map("username", "torben")).status());
        JsonNode blocks = get("/cohabit/api/blocks", TORBEN_APP).expect(200).json();
        assertEquals("felix", blocks.get(0).path("id").asString());
        delete("/cohabit/api/blocks/felix", TORBEN_APP).expect(204);
        assertTrue(get("/cohabit/api/blocks", TORBEN_APP).json().isEmpty());
        post("/cohabit/api/friends/requests", FELIX, map("username", "torben")).expect(201);
    }
}
