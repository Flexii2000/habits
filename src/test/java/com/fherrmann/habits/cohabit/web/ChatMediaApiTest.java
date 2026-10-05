package com.fherrmann.habits.cohabit.web;

import com.fherrmann.habits.cohabit.api.GifConfig;
import com.fherrmann.habits.cohabit.model.Message;
import com.fherrmann.habits.cohabit.model.Reaction;
import com.fherrmann.habits.cohabit.model.ReactionKind;
import com.fherrmann.habits.cohabit.push.ApnsClient;
import com.fherrmann.habits.cohabit.push.PushMessage;
import com.fherrmann.habits.cohabit.service.GifService;
import com.fherrmann.habits.cohabit.support.ApiTestBase;
import com.fherrmann.habits.security.AuthVia;
import com.fherrmann.habits.security.Viewer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GIFs aus der Suche und eigene GIFs im Chat, Emoji-Reaktionen, Bilder in Benachrichtigungen (05.10.). */
class ChatMediaApiTest extends ApiTestBase {

    private static final String KLIPY = "https://static.klipy.com/ii/935d7ab9d8c6202580a668421940ec81/14/af/";

    private Registered lena;
    private Registered max;
    private String chat;

    @BeforeEach
    void people() {
        lena = register("Lena", "lena", FELIX);
        max = register("Max", "maxb", FELIX);
        post("/cohabit/api/devices", FELIX, map("token", "felix-phone", "platform", "ios")).expect(204);
        post("/cohabit/api/devices", lena.who(), map("token", "lena-phone", "platform", "android")).expect(204);
        chat = withMembers(streak("Laufen", daily()), lena.who(), max.who());
    }

    private String say(Who who, String text) {
        return post("/cohabit/api/cohabits/" + chat + "/messages", who,
                map("id", UUID.randomUUID().toString(), "text", text)).expect(201).json().path("id").asString();
    }

    private Response react(Who who, String target, String reaction) {
        return post("/cohabit/api/reactions", who, map("target", target, "reaction", reaction));
    }

    /** MockMvc kodiert die Adresse selbst - das Emoji geht roh hinein. */
    private Response unreact(Who who, String target, String reaction) {
        return delete("/cohabit/api/reactions?target=" + target + (reaction == null ? "" : "&reaction=" + reaction), who);
    }

    private static Map<String, Object> klipyGif() {
        return map("slug", "hello-hi-662", "title", "Hello", "width", 498, "height", 498,
                "gifUrl", KLIPY + "8GCrVAB7.gif", "webpUrl", KLIPY + "JUYsGsrc.webp",
                "mp4Url", KLIPY + "V6da8Awi.mp4", "stillUrl", KLIPY + "UsX8Vqtm.jpg");
    }

    private Response sendGif(Who who, Map<String, Object> gif) {
        return post("/cohabit/api/cohabits/" + chat + "/messages", who,
                map("id", UUID.randomUUID().toString(), "text", null, "photoId", null, "gif", gif));
    }

    private Response upload(Who who, byte[] bytes, String type) {
        return call(MockMvcRequestBuilders.multipart("/cohabit/api/photos")
                .file(new MockMultipartFile("photo", "photo.gif", type, bytes)), who);
    }

    // MARK: - Emoji-Reaktionen

    @Test
    void einEmojiJePersonUndDasNeueErsetztDasAlte() {
        String target = "message:" + say(lena.who(), "Heute 10 km!");
        react(FELIX, target, "🔥").expect(200);
        react(max.who(), target, "🔥").expect(200);
        JsonNode r = react(FELIX, target, "💪").expect(200).json().path("reactions");
        assertEquals(2, r.size(), "Felix' 🔥 ist weg, nur noch sein 💪");
        assertEquals("🔥", r.get(0).path("reaction").asString());
        assertEquals(1, r.get(0).path("count").asInt());
        assertFalse(r.get(0).path("mine").asBoolean());
        assertEquals("💪", r.get(1).path("reaction").asString());
        assertTrue(r.get(1).path("mine").asBoolean());

        // Dasselbe noch einmal aendert nichts.
        assertEquals(r.toString(), react(FELIX, target, "💪").expect(200).json().path("reactions").toString());

        // Haeufigste zuerst, bei Gleichstand die frueher gesetzte; Personen in Reihenfolge.
        r = react(lena.who(), target, "💪").expect(200).json().path("reactions");
        assertEquals("💪", r.get(0).path("reaction").asString());
        assertEquals(2, r.get(0).path("count").asInt());
        assertEquals(List.of("felix", lena.id()), List.of(r.get(0).path("people").get(0).path("id").asString(),
                r.get(0).path("people").get(1).path("id").asString()));
        assertEquals("Lena", r.get(0).path("people").get(1).path("displayName").asString());

        JsonNode messages = get("/cohabit/api/cohabits/" + chat + "/messages", max.who()).json().path("messages");
        JsonNode reactions = messages.get(messages.size() - 1).path("reactions");
        assertEquals(r.size(), reactions.size());
        assertTrue(reactions.get(1).path("mine").asBoolean(), "Max sieht sein 🔥 als eigenes");
    }

    @Test
    void zuruecknehmenNurDasEigeneUndNurWennEsNochDasIst() {
        String target = "message:" + say(lena.who(), "Bin gelaufen");
        react(FELIX, target, "😂").expect(200);
        react(FELIX, target, "👏").expect(200);
        // Ein spaeter Eintrag aus dem Postausgang will 😂 loeschen - inzwischen gilt 👏.
        JsonNode r = unreact(FELIX, target, "😂").expect(200).json().path("reactions");
        assertEquals("👏", r.get(0).path("reaction").asString());
        r = unreact(FELIX, target, "👏").expect(200).json().path("reactions");
        assertEquals(0, r.size());
        react(FELIX, target, "🙌").expect(200);
        assertEquals(0, unreact(FELIX, target, null).expect(200).json().path("reactions").size(), "ohne reaction: die eigene");
        assertEquals("Das ist kein Emoji.", unreact(FELIX, target, "nein").message());
    }

    @Test
    void emojisWerdenVereinheitlichtUndGeprueft() {
        String target = "message:" + say(lena.who(), "Gut gemacht");
        react(FELIX, target, "❤").expect(200);
        JsonNode r = react(max.who(), target, "❤️").expect(200).json().path("reactions");
        assertEquals(1, r.size(), "❤ und ❤️ sind dieselbe Reaktion");
        assertEquals("❤️", r.get(0).path("reaction").asString());
        assertEquals(2, r.get(0).path("count").asInt());
        react(lena.who(), target, "🇩🇪").expect(200);
        react(lena.who(), target, "👨‍👩‍👧‍👦").expect(200);

        assertEquals("Das ist kein Emoji.", react(FELIX, target, "LIKE").message());
        assertEquals(400, react(FELIX, target, "👍👍").status());
        assertEquals(400, react(FELIX, target, "x").status());
        assertEquals(400, react(FELIX, target, null).status());
    }

    @Test
    void alteReaktionenWerdenAlsEmojisGelesenUndBeimSchreibenUmgestellt() {
        String id = say(lena.who(), "Vorher");
        store.update(tx -> {
            Message m = tx.messagesW(chat).messages.stream().filter(x -> x.id.equals(id)).findFirst().orElseThrow();
            // Vor dem 05.10. durfte eine Person mehrere Arten setzen.
            m.reactions.add(legacy("felix", ReactionKind.STARK));
            m.reactions.add(legacy(max.id(), ReactionKind.RESPEKT));
            m.reactions.add(legacy("felix", ReactionKind.HAHA));
        });
        JsonNode messages = get("/cohabit/api/cohabits/" + chat + "/messages", FELIX).json().path("messages");
        JsonNode r = messages.get(messages.size() - 1).path("reactions");
        assertEquals(2, r.size(), "von Felix bleibt nur die zuletzt gesetzte");
        assertEquals("🙌", r.get(0).path("reaction").asString());
        assertEquals("😂", r.get(1).path("reaction").asString());
        assertTrue(r.get(1).path("mine").asBoolean());

        react(lena.who(), "message:" + id, "👏").expect(200);
        store.read(data -> {
            List<Reaction> stored = data.messages(chat).stream().filter(x -> x.id.equals(id)).findFirst()
                    .orElseThrow().reactions;
            assertEquals(3, stored.size());
            assertTrue(stored.stream().allMatch(x -> x.reaction == null && x.emoji != null), "nur noch Emojis");
            return null;
        });
        String file = readQuietly(store.dir().resolve("messages").resolve(chat + ".json"));
        assertFalse(file.contains("\"reaction\""), "die alte Art wird nicht mehr geschrieben");
    }

    private static Reaction legacy(String personId, ReactionKind kind) {
        Reaction r = new Reaction();
        r.personId = personId;
        r.reaction = kind;
        return r;
    }

    private static String readQuietly(java.nio.file.Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    // MARK: - GIFs aus der Suche

    @Test
    void gifAusDerSucheLandetImChatMitBildInDerBenachrichtigung() {
        Map<String, Object> gif = klipyGif();
        gif.put("title", "x".repeat(250));
        JsonNode m = sendGif(FELIX, gif).expect(201).json();
        assertEquals("GIF", m.path("kind").asString());
        assertEquals("KLIPY", m.path("gif").path("provider").asString());
        assertEquals("hello-hi-662", m.path("gif").path("slug").asString());
        assertEquals(200, m.path("gif").path("title").asString().length(), "Titel gekuerzt");
        assertEquals(498, m.path("gif").path("width").asInt());
        assertEquals(KLIPY + "JUYsGsrc.webp", m.path("gif").path("webpUrl").asString());
        assertEquals(KLIPY + "V6da8Awi.mp4", m.path("gif").path("mp4Url").asString());
        assertTrue(m.path("photoId").isNull());
        assertFalse(m.path("photoAnimated").asBoolean());

        PushMessage push = androidTransport.to("lena-phone").getLast();
        assertEquals("chat", push.kind());
        assertEquals("Felix hat ein GIF geschickt", push.body());
        assertEquals(KLIPY + "8GCrVAB7.gif", push.data().get("imageUrl"));
        assertEquals(KLIPY + "UsX8Vqtm.jpg", push.data().get("imageStillUrl"));
        assertTrue(ApnsClient.payload(push).contains("\"mutable-content\":1"));

        // Nach dem Loeschen ist auch das GIF weg.
        String id = m.path("id").asString();
        JsonNode deleted = delete("/cohabit/api/cohabits/" + chat + "/messages/" + id, FELIX).expect(200).json();
        assertTrue(deleted.path("gif").isNull());
    }

    @Test
    void nurKlipysMedienHostsUndVollstaendigeAngaben() {
        Map<String, Object> foreign = klipyGif();
        foreign.put("gifUrl", "https://tracker.example.com/x.gif");
        assertEquals("Das GIF ist ungültig.", sendGif(FELIX, foreign).message());
        Map<String, Object> plain = klipyGif();
        plain.put("stillUrl", "http://static.klipy.com/a.jpg");
        assertEquals(400, sendGif(FELIX, plain).status());
        Map<String, Object> lookalike = klipyGif();
        lookalike.put("webpUrl", "https://static.klipy.com.evil.net/a.webp");
        assertEquals(400, sendGif(FELIX, lookalike).status());
        Map<String, Object> noSize = klipyGif();
        noSize.remove("width");
        assertEquals(400, sendGif(FELIX, noSize).status());
        Map<String, Object> noGif = klipyGif();
        noGif.remove("gifUrl");
        assertEquals(400, sendGif(FELIX, noGif).status());
        Map<String, Object> minimal = map("width", 220, "height", 120, "gifUrl", "https://static2.klipy.com/a/b.gif");
        assertEquals("GIF", sendGif(FELIX, minimal).expect(201).json().path("kind").asString());

        String photo = fakePhoto("felix");
        assertEquals("Ein GIF kommt ohne Foto.", post("/cohabit/api/cohabits/" + chat + "/messages", FELIX,
                map("id", UUID.randomUUID().toString(), "photoId", photo, "gif", klipyGif())).message());
    }

    @Test
    void gifKonfiguration() {
        assertEquals("{\"enabled\":false}", get("/cohabit/api/gifs/config", FELIX).expect(200).body());
        assertEquals(401, get("/cohabit/api/gifs/config", Who.nobody()).status());

        GifService service = new GifService(store, "test-key", "de", "medium");
        GifConfig felix = service.config(new Viewer("felix", AuthVia.BEARER_PRIVATE, null, true));
        assertTrue(felix.enabled());
        assertEquals("test-key", felix.apiKey());
        assertEquals("de", felix.locale());
        assertEquals("medium", felix.contentFilter());
        assertNotNull(felix.customerId());
        assertFalse(felix.customerId().contains("felix"), "KLIPY erfaehrt nicht, wer es ist");
        assertEquals(felix.customerId(), service.config(new Viewer("felix", AuthVia.BEARER_PRIVATE, null, true))
                .customerId(), "stabil");
        GifConfig other = service.config(new Viewer(lena.id(), AuthVia.BEARER_APP, null, false));
        assertFalse(felix.customerId().equals(other.customerId()));
    }

    // MARK: - Eigene GIFs

    @Test
    void eigenesGifBleibtAnimiertOhneMetadaten() throws Exception {
        byte[] original = gif(64, 48, 3, LOOP, comment("Kamera: Testgeraet"), xmp("GPS 52.52"));
        JsonNode up = upload(lena.who(), original, "image/gif").expect(201).json();
        assertTrue(up.path("animated").asBoolean());
        assertEquals(64, up.path("width").asInt());
        assertEquals(48, up.path("height").asInt());
        String id = up.path("id").asString();
        assertTrue(Files.exists(store.photosDir().resolve(id + ".gif")));
        assertFalse(Files.exists(store.photosDir().resolve(id + ".jpg")));

        Response full = get("/cohabit/api/photos/" + id + "?size=full", lena.who()).expect(200);
        assertEquals("image/gif", full.header("Content-Type"));
        byte[] stored = full.raw.getContentAsByteArray();
        String text = new String(stored, StandardCharsets.ISO_8859_1);
        assertTrue(text.contains("NETSCAPE2.0"), "die Schleife bleibt");
        assertFalse(text.contains("Testgeraet"), "Kommentar weg");
        assertFalse(text.contains("XMP DataXMP") || text.contains("GPS"), "XMP weg");
        assertEquals(3, frames(stored));
        Response thumb = get("/cohabit/api/photos/" + id + "?size=thumb", lena.who()).expect(200);
        assertEquals("image/jpeg", thumb.header("Content-Type"));
        assertEquals(64, ImageIO.read(new ByteArrayInputStream(thumb.raw.getContentAsByteArray())).getWidth());

        // Im Chat: ein Foto, das animiert ist; die Benachrichtigung bringt das Foto mit.
        JsonNode m = post("/cohabit/api/cohabits/" + chat + "/messages", lena.who(),
                map("id", UUID.randomUUID().toString(), "photoId", id)).expect(201).json();
        assertEquals("PHOTO", m.path("kind").asString());
        assertTrue(m.path("photoAnimated").asBoolean());
        PushMessage push = iosTransport.to("felix-phone").getLast();
        assertEquals("Lena hat ein GIF geschickt", push.body());
        assertEquals(id, push.data().get("photoId"));
        assertTrue(ApnsClient.payload(push).contains("\"mutable-content\":1"));

        // Im Export mit der richtigen Endung.
        byte[] zip = get("/cohabit/api/me/export", lena.who()).expect(200).raw.getContentAsByteArray();
        assertTrue(zipEntries(zip).contains("photos/" + id + ".gif"));
    }

    @Test
    void einGifIstKeinBeweisfotoUndAlsAvatarEinStandbild() throws Exception {
        String id = upload(FELIX, gif(32, 32, 2, LOOP), "image/gif").expect(201).json().path("id").asString();
        assertEquals("Ein GIF ist kein Beweisfoto.", post("/cohabit/api/cohabits/" + chat + "/checkins", FELIX,
                map("id", UUID.randomUUID().toString(), "photoIds", List.of(id))).message());

        JsonNode me = call(MockMvcRequestBuilders.multipart("/cohabit/api/me/avatar")
                .file(new MockMultipartFile("photo", "a.gif", "image/gif", gif(40, 40, 2, LOOP)))
                .with(r -> {
                    r.setMethod("PUT");
                    return r;
                }), FELIX).expect(200).json();
        String avatar = me.path("person").path("avatarPhotoId").asString();
        assertTrue(Files.exists(store.photosDir().resolve(avatar + ".jpg")));
        assertFalse(Files.exists(store.photosDir().resolve(avatar + ".gif")));
    }

    @Test
    void kaputteUndZuGrosseGifsWerdenAbgelehnt() throws Exception {
        byte[] wide = gif(2100, 8, 2);
        assertEquals("Das GIF ist zu groß (höchstens 2048 px).", upload(FELIX, wide, "image/gif").message());

        // Klein auf der Leitung, riesig beim Abspielen: 31 Bilder auf einer Flaeche von 2000 x 2000.
        byte[] many = gif(4, 4, 31);
        many[6] = (byte) (2000 & 0xFF);
        many[7] = (byte) (2000 >> 8);
        many[8] = (byte) (2000 & 0xFF);
        many[9] = (byte) (2000 >> 8);
        assertEquals("Das GIF hat zu viele Bilder.", upload(FELIX, many, "image/gif").message());

        byte[] broken = java.util.Arrays.copyOf(gif(16, 16, 2), 40);
        assertEquals("Das GIF lässt sich nicht lesen.", upload(FELIX, broken, "image/gif").message());
    }

    // MARK: - Bild in der Benachrichtigung

    @Test
    void beweisfotoBenachrichtigungBringtDasErsteFotoMit() {
        String first = fakePhoto(lena.id());
        String second = fakePhoto(lena.id());
        checkin(chat, lena.who(), map("id", UUID.randomUUID().toString(), "photoIds", List.of(first, second)));
        PushMessage push = iosTransport.ofKind("photo").getLast();
        assertEquals(first, push.data().get("photoId"));
        assertTrue(ApnsClient.payload(push).contains("\"mutable-content\":1"));

        // Ohne Bild bleibt alles wie bisher.
        say(lena.who(), "Nur Text");
        PushMessage text = iosTransport.to("felix-phone").getLast();
        assertFalse(text.data().containsKey("photoId"));
        assertFalse(ApnsClient.payload(text).contains("mutable-content"));
    }

    @Test
    void chatFotoBringtSeinBildMit() {
        String photo = fakePhoto(lena.id());
        post("/cohabit/api/cohabits/" + chat + "/messages", lena.who(),
                map("id", UUID.randomUUID().toString(), "photoId", photo)).expect(201);
        PushMessage push = iosTransport.to("felix-phone").getLast();
        assertEquals("Lena hat ein Foto geschickt", push.body());
        assertEquals(photo, push.data().get("photoId"));
    }

    // MARK: - GIF-Dateien bauen

    private static final byte[] LOOP = block(new byte[]{0x21, (byte) 0xFF, 0x0B},
            "NETSCAPE2.0".getBytes(StandardCharsets.US_ASCII), new byte[]{0x03, 0x01, 0x00, 0x00, 0x00});

    private static byte[] comment(String text) {
        byte[] data = text.getBytes(StandardCharsets.US_ASCII);
        return block(new byte[]{0x21, (byte) 0xFE, (byte) data.length}, data, new byte[]{0x00});
    }

    private static byte[] xmp(String text) {
        byte[] data = ("<x:xmpmeta>" + text + "</x:xmpmeta>").getBytes(StandardCharsets.US_ASCII);
        return block(new byte[]{0x21, (byte) 0xFF, 0x0B}, "XMP DataXMP".getBytes(StandardCharsets.US_ASCII),
                new byte[]{(byte) data.length}, data, new byte[]{0x00});
    }

    private static byte[] block(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    /** Ein GIF mit {@code frames} Bildern; {@code extensions} landen vor dem ersten Bild. */
    static byte[] gif(int width, int height, int frames, byte[]... extensions) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            writer.prepareWriteSequence(null);
            for (int i = 0; i < frames; i++) {
                BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_INDEXED);
                Graphics2D g = image.createGraphics();
                g.setColor(i % 2 == 0 ? Color.RED : Color.BLUE);
                g.fillRect(0, 0, width, height);
                g.dispose();
                writer.writeToSequence(new IIOImage(image, null, null), null);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        byte[] bytes = out.toByteArray();
        int packed = bytes[10] & 0xFF;
        int at = 13 + ((packed & 0x80) != 0 ? 3 * (1 << ((packed & 0x07) + 1)) : 0);
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        result.write(bytes, 0, at);
        for (byte[] extension : extensions) {
            result.writeBytes(extension);
        }
        result.write(bytes, at, bytes.length - at);
        return result.toByteArray();
    }

    private static int frames(byte[] gif) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(gif))) {
            ImageReader reader = ImageIO.getImageReaders(in).next();
            try {
                reader.setInput(in);
                return reader.getNumImages(true);
            } finally {
                reader.dispose();
            }
        }
    }

    private static List<String> zipEntries(byte[] zip) throws IOException {
        List<String> names = new java.util.ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                names.add(e.getName());
            }
        }
        return names;
    }
}
