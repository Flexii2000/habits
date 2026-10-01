package com.fherrmann.habits.cohabit.web;

import com.fherrmann.habits.cohabit.support.ApiTestBase;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Die Rangfolge der Zugaenge - und dass Felix' Browser nie einer anderen Person gehoert. */
class AuthTest extends ApiTestBase {

    private static final Cookie FH_PRIVATE = new Cookie("fh_private", PRIVATE);
    private static final Cookie HEALTH_TORBEN = new Cookie("health_token", TORBEN);

    private String whoAmI(Who who) {
        return get("/cohabit/api/me", who).expect(200).json().path("person").path("id").asString();
    }

    @Test
    void ohneAnmeldung401AlsJson() {
        Response r = get("/cohabit/api/me", Who.nobody()).expect(401);
        assertEquals("Nicht angemeldet.", r.message());
        assertEquals(401, get("/cohabit/api/cohabits", Who.bearer("falsch-falsch-falsch")).status());
    }

    @Test
    void fhPrivateGehtVorHealthToken() {
        // Der Fall vom 2026-09-26: Felix' Browser hat zusaetzlich Torbens health_token.
        assertEquals("felix", whoAmI(Who.cookies(HEALTH_TORBEN, FH_PRIVATE)));
        assertEquals("felix", whoAmI(Who.cookies(FH_PRIVATE, HEALTH_TORBEN)));
        assertEquals("torben", whoAmI(Who.cookies(HEALTH_TORBEN)));
    }

    @Test
    void bearerGehtVorAllenCookies() {
        assertEquals("torben", whoAmI(new Who(TORBEN, FH_PRIVATE)));
        assertEquals("felix", whoAmI(new Who(PRIVATE, HEALTH_TORBEN)));
    }

    @Test
    void einUngueltigerBearerFaelltZurNaechstenStufeDurch() {
        assertEquals("torben", whoAmI(new Who("gibt-es-nicht-0000000000", HEALTH_TORBEN)));
        assertEquals("felix", whoAmI(new Who("gibt-es-nicht-0000000000", FH_PRIVATE)));
    }

    @Test
    void cohabitTokenGehtVorHealthTokenAberNachFhPrivate() {
        String lena = registerViaAppLink();
        assertEquals("torben", lena, "App-Link von Torben");
        String token = createAppLink(TORBEN_APP).path("token").asString();
        Cookie app = new Cookie("cohabit_token", token);
        assertEquals("torben", whoAmI(Who.cookies(app)));
        assertEquals("felix", whoAmI(Who.cookies(app, FH_PRIVATE)));
    }

    private String registerViaAppLink() {
        return whoAmI(Who.bearer(createAppLink(TORBEN_APP).path("token").asString()));
    }

    private tools.jackson.databind.JsonNode createAppLink(Who who) {
        return post("/cohabit/api/me/app-links", who, map("label", "iPhone")).expect(200).json();
    }

    @Test
    void healthyPersonenEntstehenBeimErstenZugriff() {
        store.read(data -> {
            assertTrue(data.person("torben").isEmpty());
            return null;
        });
        var me = get("/cohabit/api/me", TORBEN_APP).expect(200).json();
        assertEquals("Torben", me.path("person").path("displayName").asString());
        assertEquals("torben", me.path("person").path("username").asString());
        assertEquals("TO", me.path("person").path("initials").asString());
        assertFalse(me.path("isOwner").asBoolean());
        assertEquals("[\"FOOD\",\"FOOD_TARGET_WEEKLY\",\"STEPS_WEEKLY\"]", me.path("sources").toString());
        var felix = get("/cohabit/api/me", FELIX).expect(200).json();
        assertTrue(felix.path("isOwner").asBoolean());
        assertEquals(4, felix.path("sources").size(), "Track food, Kalorienziel, Schritte, Fokus");
        assertFalse(felix.path("canLogout").asBoolean(), "Healthy-/Privat-Zugang meldet sich nicht ab");
    }

    @Test
    void appTokenLiegtNurAlsHashAufDerPlatte() throws Exception {
        var created = createAppLink(FELIX);
        String token = created.path("token").asString();
        assertEquals(48, token.length());
        assertTrue(token.matches("[0-9a-f]{48}"));
        assertEquals("https://fherrmann.com/cohabit/setup?token=" + token, created.path("setupUrl").asString());
        String people = Files.readString(Path.of(store.dir().toString(), "people.json"));
        assertFalse(people.contains(token), "der Token selbst steht nirgends");
        assertTrue(people.contains(com.fherrmann.habits.cohabit.service.AppTokens.hash(token)));

        var me = get("/cohabit/api/me", Who.bearer(token)).expect(200).json();
        assertEquals("felix", me.path("person").path("id").asString());
        assertTrue(me.path("canLogout").asBoolean());
        var links = get("/cohabit/api/me/app-links", FELIX).expect(200).json();
        assertEquals(1, links.size());
        assertEquals("iPhone", links.get(0).path("label").asString());
        assertNotNull(links.get(0).path("lastUsedAt").asString(null), "Nutzung vermerkt");
        assertTrue(links.get(0).path("token").isMissingNode());
    }

    @Test
    void abmeldenWiderruftGenauDenEigenenToken() {
        String a = createAppLink(FELIX).path("token").asString();
        String b = createAppLink(FELIX).path("token").asString();
        delete("/cohabit/api/me/app-links/current", Who.bearer(a)).expect(204);
        get("/cohabit/api/me", Who.bearer(a)).expect(401);
        get("/cohabit/api/me", Who.bearer(b)).expect(200);
        // Mit dem Healthy-Zugang gibt es keinen eigenen Token zu widerrufen.
        assertEquals(400, delete("/cohabit/api/me/app-links/current", TORBEN_APP).status());
    }

    @Test
    void webAbmeldenLoeschtDasCookie() {
        String token = createAppLink(TORBEN_APP).path("token").asString();
        Response r = delete("/cohabit/api/me/app-links/current", Who.cookies(new Cookie("cohabit_token", token)))
                .expect(204);
        assertTrue(r.header("Set-Cookie").contains("cohabit_token=;"));
        assertTrue(r.header("Set-Cookie").contains("Max-Age=0"));
    }

    @Test
    void setupLinkSetztDasCookieUndLeitetWeiter() {
        String token = createAppLink(TORBEN_APP).path("token").asString();
        Response ok = get("/cohabit/setup?token=" + token, Who.nobody()).expect(302);
        assertEquals("/cohabit/", ok.header("Location"));
        String cookie = ok.header("Set-Cookie");
        assertTrue(cookie.startsWith("cohabit_token=" + token));
        assertTrue(cookie.contains("Path=/cohabit"));
        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("SameSite=Lax"));
        assertTrue(cookie.contains("Max-Age=157680000"));
        Response bad = get("/cohabit/setup?token=kaputt", Who.nobody()).expect(302);
        assertEquals("/cohabit/?setup=invalid", bad.header("Location"));
        assertNull(bad.header("Set-Cookie"));
        // Ein Healthy-Token ist kein App-Token.
        assertEquals("/cohabit/?setup=invalid", get("/cohabit/setup?token=" + TORBEN, Who.nobody()).header("Location"));
    }
}
