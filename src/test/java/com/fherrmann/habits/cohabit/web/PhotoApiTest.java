package com.fherrmann.habits.cohabit.web;

import com.fherrmann.habits.cohabit.service.PhotoService;
import com.fherrmann.habits.cohabit.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhotoApiTest extends ApiTestBase {

    @Autowired
    PhotoService photoService;

    static byte[] image(int w, int h, String format, boolean alpha) throws Exception {
        BufferedImage img = new BufferedImage(w, h, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(242, 160, 123));
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(91, 63, 217));
        g.fillOval(w / 4, h / 4, w / 2, h / 2);
        if (alpha) {
            g.setComposite(java.awt.AlphaComposite.Clear);
            g.fillRect(0, 0, 5, 5);
        }
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }

    /** Ein JPEG mit einem EXIF-Segment direkt hinter dem Anfang - wie vom Handy. */
    static byte[] withExif(byte[] jpeg) {
        byte[] payload = "Exif\0\0GPS-Koordinaten-von-Felix".getBytes(StandardCharsets.ISO_8859_1);
        int len = payload.length + 2;
        byte[] out = new byte[jpeg.length + payload.length + 4];
        out[0] = (byte) 0xFF;
        out[1] = (byte) 0xD8;
        out[2] = (byte) 0xFF;
        out[3] = (byte) 0xE1;
        out[4] = (byte) (len >> 8);
        out[5] = (byte) len;
        System.arraycopy(payload, 0, out, 6, payload.length);
        System.arraycopy(jpeg, 2, out, 6 + payload.length, jpeg.length - 2);
        return out;
    }

    private Response upload(Who who, byte[] bytes, String type, String key) {
        var builder = MockMvcRequestBuilders.multipart("/cohabit/api/photos")
                .file(new MockMultipartFile("photo", "p", type, bytes));
        if (key != null) {
            builder.header("Idempotency-Key", key);
        }
        return call(builder, who);
    }

    private static BufferedImage read(byte[] jpeg) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(jpeg));
    }

    @Test
    void grossesFotoWirdVerkleinertUndOhneMetadatenGespeichert() throws Exception {
        byte[] original = withExif(image(3000, 2000, "jpg", false));
        assertTrue(new String(original, StandardCharsets.ISO_8859_1).contains("GPS-Koordinaten"));
        JsonNode r = upload(FELIX, original, "image/jpeg", null).expect(201).json();
        assertEquals(2048, r.path("width").asInt());
        assertEquals(1365, r.path("height").asInt());
        String id = r.path("id").asString();
        byte[] full = Files.readAllBytes(store.photosDir().resolve(id + ".jpg"));
        byte[] thumb = Files.readAllBytes(store.photosDir().resolve(id + "_thumb.jpg"));
        assertEquals(2048, read(full).getWidth());
        assertEquals(512, read(thumb).getWidth());
        assertEquals(341, read(thumb).getHeight());
        assertFalse(new String(full, StandardCharsets.ISO_8859_1).contains("GPS-Koordinaten"), "neu kodiert, ohne EXIF");
        Response get = get("/cohabit/api/photos/" + id + "?size=thumb", FELIX).expect(200);
        assertEquals("image/jpeg", get.header("Content-Type"));
        assertEquals("max-age=31536000, private, immutable", get.header("Cache-Control"));
        assertEquals(thumb.length, get.raw.getContentAsByteArray().length);
        assertEquals(full.length, get("/cohabit/api/photos/" + id, FELIX).raw.getContentAsByteArray().length);
        assertEquals(400, get("/cohabit/api/photos/" + id + "?size=huge", FELIX).status());
    }

    @Test
    void pngMitTransparenzWirdJpegAufWeiss() throws Exception {
        JsonNode r = upload(FELIX, image(100, 50, "png", true), "image/png", null).expect(201).json();
        assertEquals(100, r.path("width").asInt(), "kleine Fotos werden nicht vergroessert");
        BufferedImage full = read(Files.readAllBytes(store.photosDir().resolve(r.path("id").asString() + ".jpg")));
        Color corner = new Color(full.getRGB(1, 1));
        assertTrue(corner.getRed() > 240 && corner.getGreen() > 240 && corner.getBlue() > 240, corner.toString());
    }

    @Test
    void falscheOderZuGrosseDateienWerdenAbgelehnt() throws Exception {
        assertEquals("Nur JPEG oder PNG.", upload(FELIX, "hallo".getBytes(), "image/jpeg", null).message());
        byte[] gif = image(10, 10, "gif", false);
        assertEquals(400, upload(FELIX, gif, "image/gif", null).status());
        byte[] jpeg = image(200, 200, "jpg", false);
        byte[] broken = java.util.Arrays.copyOf(jpeg, 40);
        assertEquals("Das Foto lässt sich nicht lesen.", upload(FELIX, broken, "image/jpeg", null).message());
        byte[] huge = new byte[(int) PhotoService.MAX_BYTES + 1];
        System.arraycopy(jpeg, 0, huge, 0, jpeg.length);
        Response tooLarge = upload(FELIX, huge, "image/jpeg", null);
        assertEquals(413, tooLarge.status());
        assertEquals("Das Foto ist zu groß (höchstens 10 MB).", tooLarge.message());
        assertEquals(401, upload(Who.nobody(), jpeg, "image/jpeg", null).status());
    }

    @Test
    void derIdempotencyKeyVerhindertDoppelteFotos() throws Exception {
        byte[] jpeg = image(300, 200, "jpg", false);
        String key = UUID.randomUUID().toString();
        String first = upload(FELIX, jpeg, "image/jpeg", key).expect(201).json().path("id").asString();
        Response again = upload(FELIX, jpeg, "image/jpeg", key);
        assertEquals(200, again.status());
        assertEquals(first, again.json().path("id").asString());
        String torbens = upload(TORBEN_APP, jpeg, "image/jpeg", key).expect(201).json().path("id").asString();
        assertNotEquals(first, torbens, "der Schluessel gilt je Person");
        assertEquals(400, upload(FELIX, jpeg, "image/jpeg", "kaputt!").status());
    }

    @Test
    void sichtbarNurFuerDieMitgliederDesCoHabits() throws Exception {
        Registered lena = register("Lena", "lena", FELIX);
        Registered max = register("Max", "maxb", FELIX);
        Map<String, Object> config = streak("Laufen", daily());
        config.put("photoRequired", true);
        String id = withMembers(config, lena.who());
        String photo = upload(FELIX, image(400, 300, "jpg", false), "image/jpeg", null).expect(201)
                .json().path("id").asString();
        assertEquals(404, get("/cohabit/api/photos/" + photo, lena.who()).status(), "noch nirgends verwendet");
        checkin(id, FELIX, map("id", UUID.randomUUID().toString(), "photoId", photo));
        get("/cohabit/api/photos/" + photo + "?size=thumb", lena.who()).expect(200);
        assertEquals(404, get("/cohabit/api/photos/" + photo, max.who()).status());
        int traversal = get("/cohabit/api/photos/../people", FELIX).status();
        assertTrue(traversal == 400 || traversal == 404, "abgewiesen: " + traversal);
    }

    @Test
    void avatarFuerAlleAngemeldetenSichtbar() throws Exception {
        var builder = MockMvcRequestBuilders.multipart("/cohabit/api/me/avatar")
                .file(new MockMultipartFile("photo", "a", "image/png", image(600, 600, "png", false)));
        builder.with(r -> {
            r.setMethod("PUT");
            return r;
        });
        JsonNode me = call(builder, FELIX).expect(200).json();
        String avatar = me.path("person").path("avatarPhotoId").asString();
        get("/cohabit/api/photos/" + avatar + "?size=thumb", TORBEN_APP).expect(200);
        var again = MockMvcRequestBuilders.multipart("/cohabit/api/me/avatar")
                .file(new MockMultipartFile("photo", "a", "image/png", image(64, 64, "png", false)));
        again.with(r -> {
            r.setMethod("PUT");
            return r;
        });
        String second = call(again, FELIX).expect(200).json().path("person").path("avatarPhotoId").asString();
        assertNotEquals(avatar, second);
        assertFalse(Files.exists(store.photosDir().resolve(avatar + ".jpg")), "der alte Avatar ist weg");
        JsonNode cleared = delete("/cohabit/api/me/avatar", FELIX).expect(200).json();
        assertTrue(cleared.path("person").path("avatarPhotoId").isNull());
        assertEquals(404, get("/cohabit/api/photos/" + second, TORBEN_APP).status());
    }

    @Test
    void unbenutzteFotosVerschwindenNach24Stunden() throws Exception {
        String unused = upload(FELIX, image(200, 200, "jpg", false), "image/jpeg", null).expect(201)
                .json().path("id").asString();
        Map<String, Object> config = streak("Laufen", daily());
        config.put("photoRequired", true);
        String id = id(create(FELIX, config));
        String used = upload(FELIX, image(200, 200, "jpg", false), "image/jpeg", null).expect(201)
                .json().path("id").asString();
        checkin(id, FELIX, map("id", UUID.randomUUID().toString(), "photoId", used));
        Path orphan = store.photosDir().resolve("verwaist.jpg");
        Files.write(orphan, new byte[]{1, 2, 3});
        Files.setLastModifiedTime(orphan, java.nio.file.attribute.FileTime.from(clock.instant().minus(Duration.ofDays(2))));
        assertEquals(0, photoService.cleanup(clock.instant().plus(Duration.ofHours(23))));
        assertEquals(1, photoService.cleanup(clock.instant().plus(Duration.ofHours(25))));
        assertFalse(Files.exists(store.photosDir().resolve(unused + ".jpg")));
        assertTrue(Files.exists(store.photosDir().resolve(used + ".jpg")));
        assertFalse(Files.exists(orphan));
        store.read(data -> {
            assertEquals(1, data.photos().photos.size());
            assertEquals(id, data.photos().photos.getFirst().cohabitId);
            return null;
        });
    }
}
