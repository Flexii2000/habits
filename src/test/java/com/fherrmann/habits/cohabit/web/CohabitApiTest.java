package com.fherrmann.habits.cohabit.web;

import com.fherrmann.habits.cohabit.push.PushMessage;
import com.fherrmann.habits.cohabit.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CohabitApiTest extends ApiTestBase {

    @Test
    void streakAnlegenLiefertDasDetail() {
        JsonNode d = create(FELIX, streak("Laufen", map("kind", "TIMES_PER_WEEK", "times", 3)));
        String id = d.path("summary").path("ref").path("id").asString();
        assertTrue(id.startsWith("c-"));
        assertEquals("Laufen", d.path("summary").path("ref").path("name").asString());
        assertEquals("STREAK", d.path("summary").path("ref").path("type").asString());
        assertEquals("Streak · 3× pro Woche", d.path("summary").path("typeLine").asString());
        assertEquals("0", d.path("summary").path("headline").path("value").asString());
        assertEquals("Wochen", d.path("summary").path("headline").path("unit").asString());
        assertEquals("0 Wo.", d.path("summary").path("headline").path("short").asString());
        assertEquals("OPEN", d.path("summary").path("status").asString());
        assertEquals("OPEN_TODAY", d.path("summary").path("section").asString());
        assertEquals("Abhaken", d.path("summary").path("checkInLabel").asString());
        assertEquals("0 von 3", d.path("summary").path("listLine").asString());
        assertEquals("ADMIN", d.path("myRole").asString());
        assertTrue(d.path("canInvite").asBoolean());
        assertEquals(1, d.path("seats").path("used").asInt());
        assertEquals(8, d.path("seats").path("max").asInt());
        assertEquals("[\"Nachtragen bis 48 h\",\"Europe/Berlin\"]", d.path("rules").toString());
        assertEquals("{\"kind\":\"TIMES_PER_WEEK\",\"times\":3}", d.path("config").path("streak").path("rhythm").toString());
        assertEquals("{\"mode\":\"CHECK\"}", d.path("config").path("tracking").toString());
        assertEquals("noch 3 Einträge diese Woche", d.path("streak").path("remainingText").asString());
        assertEquals(7, d.path("streak").path("week").path("days").size());
        assertEquals(2, d.path("streak").path("week").path("todayIndex").asInt(), "Mittwoch");
        assertEquals("[\"BEFORE_JOIN\",\"BEFORE_JOIN\",\"OPEN\",\"FUTURE\",\"FUTURE\",\"FUTURE\",\"FUTURE\"]",
                d.path("streak").path("week").path("rows").get(0).path("cells").toString());
        assertTrue(d.path("abstinence").isNull());
        assertEquals("2026-09-30", d.path("backfillFrom").asString());
        assertEquals(1, get("/cohabit/api/cohabits", FELIX).expect(200).json().size());
    }

    @Test
    void jederTypBekommtSeineZeileUndSeinenKnopf() {
        JsonNode a = create(FELIX, map("type", "ABSTINENCE", "name", "Ohne Zucker", "color", "mint",
                "abstinence", map("groupMode", false)));
        assertEquals("Abstinenz", a.path("summary").path("typeLine").asString());
        assertEquals("1", a.path("summary").path("headline").path("value").asString(), "heute zaehlt mit");
        assertEquals("Tag", a.path("summary").path("headline").path("unit").asString());
        assertEquals("Unterbrechung eintragen", a.path("summary").path("checkInLabel").asString());
        assertEquals("RUNNING", a.path("summary").path("section").asString());

        JsonNode g = create(FELIX, map("type", "GOAL", "name", "100k Schritte", "color", "periwinkle",
                "tracking", map("mode", "VALUE", "unit", "STEPS"),
                "goal", map("target", 100000, "deadline", "2026-10-31", "counting", "AMOUNT", "mode", "TEAM")));
        assertEquals("Teamziel · bis 31.10.", g.path("summary").path("typeLine").asString());
        assertEquals("0%", g.path("summary").path("headline").path("value").asString());
        assertEquals("Schritte manuell eintragen", g.path("summary").path("checkInLabel").asString());
        assertEquals("2026-09-30", g.path("config").path("goal").path("start").asString());
        assertEquals(100000, g.path("config").path("goal").path("target").asInt());
        assertEquals("100000", g.path("config").path("goal").path("target").toString(), "ganze Zahl ohne .0");
        assertEquals("noch 31 Tage", g.path("goal").path("remainingText").asString());

        JsonNode ch = create(FELIX, map("type", "CHALLENGE", "name", "Wer kocht öfter?", "color", "butter",
                "challenge", map("start", "2026-09-01", "end", "2026-09-30", "scoring", "MOST_ENTRIES",
                        "stake", "Verlierer kocht für alle", "recurrence", "MONTHLY")));
        assertEquals("Challenge · September", ch.path("summary").path("typeLine").asString());
        assertEquals("#1", ch.path("summary").path("headline").path("value").asString());
        assertEquals("dein Platz", ch.path("summary").path("headline").path("unit").asString());
        assertEquals("+1 Wer kocht öfter? eintragen", ch.path("summary").path("checkInLabel").asString());
        assertEquals("Meiste Einträge", ch.path("challenge").path("scoringText").asString());
        assertEquals("startet jeden Monat neu", ch.path("challenge").path("recurrenceText").asString());
        assertEquals("2026-09-30T21:59:59Z", ch.path("challenge").path("endsAt").asString());
        assertEquals("endet in 13 Std. 59 Min.", ch.path("challenge").path("endsInText").asString());
        assertEquals("vorn · endet heute", ch.path("summary").path("subline").asString());
    }

    @Test
    void eingabenWerdenGeprueft() {
        assertEquals("Welcher Typ soll es sein?", post("/cohabit/api/cohabits", FELIX, map("name", "x")).message());
        assertEquals("Der Name braucht 1 bis 40 Zeichen.",
                post("/cohabit/api/cohabits", FELIX, streak("", daily())).message());
        Map<String, Object> bad = streak("x", daily());
        bad.put("color", "pink");
        assertEquals(400, post("/cohabit/api/cohabits", FELIX, bad).status());
        Map<String, Object> week8 = streak("x", map("kind", "TIMES_PER_WEEK", "times", 8));
        assertEquals(400, post("/cohabit/api/cohabits", FELIX, week8).status());
        Map<String, Object> backfill = streak("x", daily());
        backfill.put("backfillHours", 12);
        assertEquals(400, post("/cohabit/api/cohabits", FELIX, backfill).status());
        assertEquals(400, post("/cohabit/api/cohabits", FELIX, map("type", "CHALLENGE", "name", "c",
                "challenge", map("start", "2026-09-01", "end", "2026-09-10", "scoring", "MOST_ENTRIES"))).status(),
                "Ende in der Vergangenheit");
        assertEquals(400, post("/cohabit/api/cohabits", FELIX, map("type", "CHALLENGE", "name", "c",
                "challenge", map("start", "2026-10-01", "end", "2026-10-10", "scoring", "FIRST_TO_TARGET"))).status(),
                "Zielwert fehlt");
        assertEquals(400, post("/cohabit/api/cohabits", FELIX, map("type", "GOAL", "name", "g",
                "goal", map("target", 0, "deadline", "2026-10-31"))).status());
    }

    @Test
    void automatischNurMitQuelle() {
        Map<String, Object> focus = streak("Fokus", daily());
        focus.put("auto", map("source", "FOCUS", "focusMinutesGoal", 120));
        assertEquals(403, post("/cohabit/api/cohabits", TORBEN_APP, focus).status());
        JsonNode d = create(FELIX, focus);
        assertEquals("Streak · 120 Min. Fokus täglich", d.path("summary").path("typeLine").asString());
        assertFalse(d.path("summary").path("canCheckIn").asBoolean());
        assertTrue(d.path("summary").path("checkInLabel").isNull());
        Map<String, Object> food = streak("Track food", daily());
        food.put("auto", map("source", "FOOD"));
        create(TORBEN_APP, food);
    }

    @Test
    void derTypLaesstSichNichtAendernUndNurDerAdminBearbeitet() {
        String id = create(FELIX, streak("Laufen", daily())).path("summary").path("ref").path("id").asString();
        Map<String, Object> changed = streak("Laufen!", map("kind", "WEEKDAYS", "weekdays", List.of(5, 1, 3)));
        JsonNode d = put("/cohabit/api/cohabits/" + id, FELIX, changed).expect(200).json();
        assertEquals("Streak · Mo, Mi, Fr", d.path("summary").path("typeLine").asString());
        Map<String, Object> typeChange = streak("Laufen", daily());
        typeChange.put("type", "GOAL");
        Response r = put("/cohabit/api/cohabits/" + id, FELIX, typeChange);
        assertEquals(400, r.status());
        assertEquals("Der Typ lässt sich nicht ändern.", r.message());
        assertEquals(404, put("/cohabit/api/cohabits/" + id, TORBEN_APP, changed).status(), "nicht sichtbar");
        JsonNode chat = get("/cohabit/api/cohabits/" + id, FELIX).json();
        assertEquals(0, chat.path("unreadMessages").asInt(), "die eigene Systemmeldung ist nicht ungelesen");
    }

    @Test
    void einladenNurFreundeUndPlaetzeZaehlenOffeneEinladungenMit() {
        String id = create(FELIX, streak("Laufen", daily())).path("summary").path("ref").path("id").asString();
        get("/cohabit/api/me", TORBEN_APP).expect(200);
        assertEquals(400, post("/cohabit/api/cohabits/" + id + "/invitations", FELIX,
                map("personIds", List.of("torben"))).status(), "noch nicht befreundet");
        List<Registered> friends = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            friends.add(register("Freund " + i, "freund" + i, FELIX));
        }
        post("/cohabit/api/devices", friends.get(0).who(), map("token", "f0-phone", "platform", "ios")).expect(204);
        JsonNode after = post("/cohabit/api/cohabits/" + id + "/invitations", FELIX,
                map("personIds", friends.stream().map(Registered::id).toList())).expect(200).json();
        assertEquals(8, after.path("seats").path("used").asInt());
        assertEquals("INVITED", after.path("people").get(0).path("status").asString());
        PushMessage invite = iosTransport.to("f0-phone").getFirst();
        assertEquals("invite", invite.kind());
        assertEquals("Einladung zu „Laufen“", invite.title());
        assertTrue(invite.link().startsWith("cohabit://invitation/"));

        Registered eighth = register("Acht", "acht", FELIX);
        Response full = post("/cohabit/api/cohabits/" + id + "/invitations", FELIX,
                map("personIds", List.of(eighth.id())));
        assertEquals(409, full.status());
        assertEquals("Alle Plätze sind belegt.", full.message());
        String code = post("/cohabit/api/cohabits/" + id + "/invite-link", FELIX, null).expect(200)
                .json().path("code").asString();
        assertTrue(get("/cohabit/api/invite-links/" + code, Who.nobody()).expect(200).json().path("full").asBoolean());
        assertEquals(409, post("/cohabit/api/invite-links/" + code + "/accept", eighth.who(), null).status());

        // Wer eine Einladung hat, kommt trotzdem ueber den Link rein - sie haelt ja seinen Platz.
        post("/cohabit/api/invite-links/" + code + "/accept", friends.get(1).who(), null).expect(200);
        JsonNode invitations = get("/cohabit/api/me/invitations", friends.get(0).who()).expect(200).json();
        assertEquals(1, invitations.size());
        assertEquals("Streak · täglich", invitations.get(0).path("cohabit").path("typeLine").asString());
        String invitationId = invitations.get(0).path("id").asString();
        JsonNode joined = post("/cohabit/api/invitations/" + invitationId + "/accept", friends.get(0).who(), null)
                .expect(200).json();
        assertEquals("MEMBER", joined.path("myRole").asString());
        String decline = get("/cohabit/api/me/invitations", friends.get(2).who()).json().get(0).path("id").asString();
        post("/cohabit/api/invitations/" + decline + "/decline", friends.get(2).who(), null).expect(204);
        assertEquals(7, get("/cohabit/api/cohabits/" + id, FELIX).json().path("seats").path("used").asInt());
    }

    @Test
    void einladungslinkRegistriertUndBefreundet() {
        JsonNode d = create(FELIX, streak("Laufen", map("kind", "TIMES_PER_WEEK", "times", 3)));
        String id = d.path("summary").path("ref").path("id").asString();
        JsonNode link = post("/cohabit/api/cohabits/" + id + "/invite-link", FELIX, null).expect(200).json();
        String code = link.path("code").asString();
        assertEquals(22, code.length());
        assertEquals("https://fherrmann.com/cohabit/join/" + code, link.path("url").asString());
        assertEquals("2026-10-14T08:00:00Z", link.path("expiresAt").asString());

        JsonNode preview = get("/cohabit/api/invite-links/" + code, Who.nobody()).expect(200).json();
        assertEquals("COHABIT", preview.path("kind").asString());
        assertEquals("felix", preview.path("from").path("id").asString());
        assertEquals("Streak · 3× pro Woche", preview.path("cohabit").path("typeLine").asString());
        assertEquals(1, preview.path("cohabit").path("members").size());
        assertFalse(preview.path("full").asBoolean());
        assertEquals(404, get("/cohabit/api/invite-links/AAAAAAAAAAAAAAAAAAAAAA", Who.nobody()).status());

        assertEquals(400, post("/cohabit/api/invite-links/" + code + "/accept", Who.nobody(),
                map("displayName", "Lena", "username", "lena", "acceptTerms", false)).status());
        assertEquals(409, post("/cohabit/api/invite-links/" + code + "/accept", Who.nobody(),
                map("displayName", "Lena", "username", "felix", "acceptTerms", true)).status());
        Response accepted = post("/cohabit/api/invite-links/" + code + "/accept", Who.nobody(),
                map("displayName", "Lena Kraus", "username", "lena.k", "acceptTerms", true)).expect(200);
        JsonNode r = accepted.json();
        String token = r.path("token").asString();
        assertEquals(48, token.length());
        assertEquals("https://fherrmann.com/cohabit/setup?token=" + token, r.path("setupUrl").asString());
        assertEquals(id, r.path("cohabitId").asString());
        assertTrue(r.path("me").path("person").path("id").asString().matches("u-[0-9a-f]{12}"));
        assertEquals("LK", r.path("me").path("person").path("initials").asString());
        assertTrue(r.path("me").path("canLogout").asBoolean());
        assertTrue(accepted.header("Set-Cookie").startsWith("cohabit_token=" + token));

        Who lena = Who.bearer(token);
        JsonNode lenaDetail = get("/cohabit/api/cohabits/" + id, lena).expect(200).json();
        assertEquals("MEMBER", lenaDetail.path("myRole").asString());
        assertFalse(lenaDetail.path("canInvite").asBoolean());
        assertEquals(1, get("/cohabit/api/friends", lena).json().path("friends").size());
        JsonNode messages = store.read(data -> JSON.valueToTree(data.messages(id).stream().map(m -> m.systemText).toList()));
        assertEquals("[\"Lena Kraus ist beigetreten\"]", messages.toString());
        assertEquals(1, get("/cohabit/api/cohabits/" + id, FELIX).json().path("unreadMessages").asInt());

        // Angemeldet noch einmal: nichts doppelt, kein neuer Token.
        JsonNode again = post("/cohabit/api/invite-links/" + code + "/accept", lena, null).expect(200).json();
        assertTrue(again.path("token").isNull());
        assertEquals(2, get("/cohabit/api/cohabits/" + id, FELIX).json().path("members").size());
    }

    @Test
    void freundesLinkBefreundet() {
        Registered sara = register("Sara", "sara_a", FELIX);
        JsonNode preview = get("/cohabit/api/invite-links/" + post("/cohabit/api/me/friend-link", FELIX, null)
                .json().path("code").asString(), Who.nobody()).expect(200).json();
        assertEquals("FRIEND", preview.path("kind").asString());
        assertTrue(preview.path("cohabit").isNull());
        assertEquals("sara_a", get("/cohabit/api/friends", FELIX).json().path("friends").get(0).path("username").asString());
        assertEquals(1, get("/cohabit/api/friends", sara.who()).json().path("friends").size());
    }

    @Test
    void mitgliederEntfernenVerlassenUndAdminWechsel() {
        String id = create(FELIX, streak("Laufen", daily())).path("summary").path("ref").path("id").asString();
        Registered lena = register("Lena", "lena", FELIX);
        Registered max = register("Max", "maxb", FELIX);
        post("/cohabit/api/cohabits/" + id + "/invitations", FELIX, map("personIds", List.of(lena.id(), max.id())))
                .expect(200);
        for (Registered r : List.of(lena, max)) {
            String inv = get("/cohabit/api/me/invitations", r.who()).json().get(0).path("id").asString();
            post("/cohabit/api/invitations/" + inv + "/accept", r.who(), null).expect(200);
        }
        assertEquals(403, delete("/cohabit/api/cohabits/" + id + "/members/" + max.id(), lena.who()).status());
        delete("/cohabit/api/cohabits/" + id + "/members/" + max.id(), FELIX).expect(204);
        assertEquals(404, get("/cohabit/api/cohabits/" + id, max.who()).status());

        JsonNode transferred = put("/cohabit/api/cohabits/" + id + "/admin", FELIX, map("personId", lena.id()))
                .expect(200).json();
        assertEquals("MEMBER", transferred.path("myRole").asString());
        delete("/cohabit/api/cohabits/" + id + "/members/me", lena.who()).expect(204);
        assertEquals("ADMIN", get("/cohabit/api/cohabits/" + id, FELIX).json().path("myRole").asString(),
                "der Admin ging - das am laengsten beteiligte Mitglied uebernimmt");
        List<String> texts = store.read(data -> data.messages(id).stream().map(m -> m.systemText).toList());
        assertTrue(texts.contains("Max wurde entfernt"));
        assertTrue(texts.contains("Lena ist jetzt Admin"));
        assertTrue(texts.contains("Lena hat das Co-Habit verlassen"));
        assertTrue(texts.contains("Felix ist jetzt Admin"));

        delete("/cohabit/api/cohabits/" + id + "/members/me", FELIX).expect(204);
        store.read(data -> {
            assertTrue(data.cohabit(id).isEmpty(), "allein verlassen = geloescht");
            return null;
        });
    }

    @Test
    void eigeneEinstellungenUndPausen() {
        String id = create(FELIX, streak("Laufen", daily())).path("summary").path("ref").path("id").asString();
        JsonNode s = put("/cohabit/api/cohabits/" + id + "/settings/me", FELIX, map("muted", true, "chat", false))
                .expect(200).json().path("mySettings");
        assertTrue(s.path("muted").asBoolean());
        assertFalse(s.path("chat").asBoolean());
        assertTrue(s.path("checkins").isNull(), "nicht geschickt = wie global");
        s = put("/cohabit/api/cohabits/" + id + "/settings/me", FELIX, "{\"chat\":null}").expect(200).json()
                .path("mySettings");
        assertTrue(s.path("chat").isNull());
        assertTrue(s.path("muted").asBoolean());

        JsonNode d = post("/cohabit/api/cohabits/" + id + "/pauses", FELIX, map("from", "2026-10-01", "to", "2026-10-07"))
                .expect(200).json();
        assertEquals(1, d.path("myPauses").size());
        String pauseId = d.path("myPauses").get(0).path("id").asString();
        assertEquals(400, post("/cohabit/api/cohabits/" + id + "/pauses", FELIX,
                map("from", "2026-10-07", "to", "2026-10-01")).status());
        assertEquals(400, post("/cohabit/api/cohabits/" + id + "/pauses", FELIX,
                map("from", "2026-09-01", "to", "2026-09-02")).status(), "vor der Nachtragsfrist");
        delete("/cohabit/api/cohabits/" + id + "/pauses/" + pauseId, FELIX).expect(200);
        String abst = create(FELIX, map("type", "ABSTINENCE", "name", "Ohne", "color", "mint"))
                .path("summary").path("ref").path("id").asString();
        assertEquals(400, post("/cohabit/api/cohabits/" + abst + "/pauses", FELIX,
                map("from", "2026-10-01", "to", "2026-10-02")).status());
    }

    @Test
    void archivierenWiederherstellenUndLoeschen() {
        String id = create(FELIX, streak("Laufen", daily())).path("summary").path("ref").path("id").asString();
        JsonNode archived = post("/cohabit/api/cohabits/" + id + "/archive", FELIX, null).expect(200).json();
        assertTrue(archived.path("summary").path("archived").asBoolean());
        assertFalse(archived.path("summary").path("canCheckIn").asBoolean());
        assertEquals(0, get("/cohabit/api/cohabits", FELIX).json().size());
        assertEquals(1, get("/cohabit/api/me/archived", FELIX).json().size());
        assertEquals(0, get("/cohabit/api/me", FELIX).json().path("counts").path("cohabits").asInt());
        post("/cohabit/api/cohabits/" + id + "/unarchive", FELIX, null).expect(200);
        assertEquals(1, get("/cohabit/api/cohabits", FELIX).json().size());

        assertEquals(400, delete("/cohabit/api/cohabits/" + id, FELIX, map("confirm", false)).status());
        delete("/cohabit/api/cohabits/" + id, FELIX, map("confirm", true)).expect(204);
        assertEquals(404, get("/cohabit/api/cohabits/" + id, FELIX).status());
        assertNull(store.read(data -> data.cohabit(id).orElse(null)));
    }
}
