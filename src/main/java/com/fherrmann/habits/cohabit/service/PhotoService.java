package com.fherrmann.habits.cohabit.service;

import com.fherrmann.habits.cohabit.api.MeView;
import com.fherrmann.habits.cohabit.model.Checkin;
import com.fherrmann.habits.cohabit.model.Cohabit;
import com.fherrmann.habits.cohabit.model.Message;
import com.fherrmann.habits.cohabit.model.Person;
import com.fherrmann.habits.cohabit.model.PhotoMeta;
import com.fherrmann.habits.cohabit.store.CohabitStore;
import com.fherrmann.habits.security.Viewer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.stream.Stream;

/**
 * Fotos: annehmen, verkleinern, ausliefern, aufraeumen.
 *
 * <p>Nur ImageIO, keine Bibliothek. Jedes Foto wird neu kodiert - damit sind alle
 * Metadaten (Ort, Geraet) weg, ohne sie einzeln suchen zu muessen. Gedreht wird
 * nicht: die Clients laden aufrecht hoch und verlassen sich nicht auf EXIF.
 */
@Service
public class PhotoService {

    private static final Logger log = LoggerFactory.getLogger(PhotoService.class);

    public static final long MAX_BYTES = 10L * 1024 * 1024;
    static final int FULL_EDGE = 2048;
    static final int THUMB_EDGE = 512;
    static final float FULL_QUALITY = 0.85f;
    static final float THUMB_QUALITY = 0.8f;
    /** Mehr Pixel dekodiert niemand - Schutz vor Bildern, die klein aussehen und riesig entpacken. */
    static final long MAX_PIXELS = 120_000_000L;
    /** Beim Dekodieren hoechstens doppelt so gross wie das Ergebnis - der Rest wird schon beim Lesen uebersprungen. */
    static final int DECODE_EDGE = 2 * FULL_EDGE;
    static final Duration UNUSED_GRACE = Duration.ofHours(24);

    public record Uploaded(String id, int width, int height) {
    }

    public record Result(Uploaded photo, boolean created) {
    }

    public record Image(byte[] bytes) {
    }

    static {
        // Kein Plattencache fuer ImageIO: der Dienst laeuft mit ProtectSystem=strict, und im
        // Speicher ist es bei 10 MB ohnehin schneller.
        ImageIO.setUseCache(false);
    }

    /** Zwei Fotos gleichzeitig - jedes braucht beim Verkleinern ein paar Dutzend MB. */
    private final Semaphore decoding = new Semaphore(2);
    private final CohabitStore store;
    private final PhotoFiles files;
    private final ViewService views;
    private final PeopleService people;

    public PhotoService(CohabitStore store, PhotoFiles files, ViewService views, PeopleService people) {
        this.store = store;
        this.files = files;
        this.views = views;
        this.people = people;
    }

    // MARK: - Hochladen

    public Result upload(Viewer viewer, byte[] bytes, String contentType, String idempotencyKey) {
        String me = viewer.personId();
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.trim();
        if (key != null && !CheckinService.CLIENT_ID.matcher(key).matches()) {
            throw Errors.badRequest("Der Idempotency-Key ist ungültig.");
        }
        if (key != null) {
            PhotoMeta known = store.read(data -> data.photos().photos.stream()
                    .filter(p -> me.equals(p.ownerId) && key.equals(p.idempotencyKey)).findFirst().orElse(null));
            if (known != null) {
                return new Result(new Uploaded(known.id, known.width, known.height), false);
            }
        }
        Encoded encoded = encode(bytes, contentType);
        String id = UUID.randomUUID().toString();
        Instant now = views.now();
        writeFile(files.full(id), encoded.full());
        writeFile(files.thumb(id), encoded.thumb());
        try {
            return store.write(tx -> {
                PhotoMeta known = key == null ? null : tx.photos().photos.stream()
                        .filter(p -> me.equals(p.ownerId) && key.equals(p.idempotencyKey)).findFirst().orElse(null);
                if (known != null) {
                    // Zwei gleichzeitige Versuche mit demselben Schluessel: der erste gewinnt.
                    tx.afterCommit(() -> files.deleteFiles(id));
                    return new Result(new Uploaded(known.id, known.width, known.height), false);
                }
                PeopleService.requirePerson(tx, me);
                PhotoMeta meta = new PhotoMeta();
                meta.id = id;
                meta.ownerId = me;
                meta.idempotencyKey = key;
                meta.createdAt = now;
                meta.width = encoded.width();
                meta.height = encoded.height();
                tx.photosW().photos.add(meta);
                return new Result(new Uploaded(id, meta.width, meta.height), true);
            });
        } catch (RuntimeException e) {
            files.deleteFiles(id);
            throw e;
        }
    }

    record Encoded(byte[] full, byte[] thumb, int width, int height) {
    }

    Encoded encode(byte[] bytes, String contentType) {
        if (bytes == null || bytes.length == 0) {
            throw Errors.badRequest("Das Foto fehlt.");
        }
        if (bytes.length > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "Das Foto ist zu groß (höchstens 10 MB).");
        }
        if (!isJpeg(bytes) && !isPng(bytes)) {
            throw Errors.badRequest("Nur JPEG oder PNG.");
        }
        try {
            decoding.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw Errors.badRequest("Abgebrochen.");
        }
        try {
            BufferedImage image = decode(bytes);
            BufferedImage full = scale(image, FULL_EDGE);
            BufferedImage thumb = scale(full, THUMB_EDGE);
            return new Encoded(jpeg(full, FULL_QUALITY), jpeg(thumb, THUMB_QUALITY), full.getWidth(), full.getHeight());
        } finally {
            decoding.release();
        }
    }

    static boolean isJpeg(byte[] b) {
        return b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF;
    }

    static boolean isPng(byte[] b) {
        return b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
    }

    /** Dekodiert mit Unterabtastung, damit ein 50-Megapixel-Foto nicht 200 MB Speicher frisst. */
    static BufferedImage decode(byte[] bytes) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw Errors.badRequest("Das Foto lässt sich nicht lesen.");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                if (w <= 0 || h <= 0 || (long) w * h > MAX_PIXELS) {
                    throw Errors.badRequest("Das Foto ist zu groß.");
                }
                ImageReadParam param = reader.getDefaultReadParam();
                int sub = (int) Math.ceil(Math.max(w, h) / (double) DECODE_EDGE);
                if (sub > 1) {
                    param.setSourceSubsampling(sub, sub, 0, 0);
                }
                BufferedImage image = reader.read(0, param);
                if (image == null) {
                    throw Errors.badRequest("Das Foto lässt sich nicht lesen.");
                }
                return image;
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof ResponseStatusException rse) {
                throw rse;
            }
            throw Errors.badRequest("Das Foto lässt sich nicht lesen.");
        }
    }

    /**
     * Auf hoechstens {@code edge} Pixel lange Kante, in Halbierungsschritten: ein
     * einziger grosser Sprung liesse feine Linien flimmern. Transparenz wird zu Weiss -
     * JPEG kennt keine.
     */
    static BufferedImage scale(BufferedImage src, int edge) {
        int w = src.getWidth();
        int h = src.getHeight();
        double factor = Math.min(1.0, edge / (double) Math.max(w, h));
        int targetW = Math.max(1, (int) Math.round(w * factor));
        int targetH = Math.max(1, (int) Math.round(h * factor));
        BufferedImage current = toRgb(src);
        while (current.getWidth() / 2 >= targetW && current.getHeight() / 2 >= targetH) {
            current = draw(current, current.getWidth() / 2, current.getHeight() / 2);
        }
        if (current.getWidth() != targetW || current.getHeight() != targetH) {
            current = draw(current, targetW, targetH);
        }
        return current;
    }

    private static BufferedImage toRgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_RGB) {
            return src;
        }
        BufferedImage rgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, src.getWidth(), src.getHeight());
            g.drawImage(src, 0, 0, null);
        } finally {
            g.dispose();
        }
        return rgb;
    }

    private static BufferedImage draw(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(src, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    static byte[] jpeg(BufferedImage image, float quality) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(out)) {
            writer.setOutput(stream);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.write(null, new IIOImage(image, null, null), param);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private static void writeFile(Path target, byte[] bytes) {
        try {
            Files.createDirectories(target.getParent());
            Path temp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.write(temp, bytes);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + target, e);
        }
    }

    // MARK: - Ausliefern

    /**
     * Sichtbar fuer die hochladende Person, fuer Mitglieder des Co-Habits, in dem es
     * verwendet wird, und als Avatar fuer alle Angemeldeten. Sonst 404 - auch wenn es
     * das Foto gibt.
     */
    public Image read(Viewer viewer, String id, String size) {
        String me = viewer.personId();
        boolean thumb = "thumb".equalsIgnoreCase(size);
        if (size != null && !thumb && !"full".equalsIgnoreCase(size)) {
            throw Errors.badRequest("size ist thumb oder full.");
        }
        if (!CohabitStore.isSafeId(id)) {
            throw Errors.notFound("Foto nicht gefunden.");
        }
        boolean visible = store.read(data -> {
            PhotoMeta meta = PhotoFiles.meta(data, id);
            if (meta == null) {
                return false;
            }
            if (me.equals(meta.ownerId) || meta.avatarOf != null) {
                return true;
            }
            return meta.cohabitId != null && data.cohabit(meta.cohabitId).map(c -> c.isMember(me)).orElse(false);
        });
        if (!visible) {
            throw Errors.notFound("Foto nicht gefunden.");
        }
        try {
            return new Image(Files.readAllBytes(thumb ? files.thumb(id) : files.full(id)));
        } catch (IOException e) {
            throw Errors.notFound("Foto nicht gefunden.");
        }
    }

    // MARK: - Avatar

    public MeView setAvatar(Viewer viewer, byte[] bytes, String contentType) {
        Result result = upload(viewer, bytes, contentType, null);
        String id = result.photo().id();
        store.update(tx -> {
            Person p = PeopleService.requirePerson(tx, viewer.personId());
            PhotoMeta meta = PhotoFiles.meta(tx, id);
            tx.photosW();
            meta.avatarOf = p.id;
            String old = p.avatarPhotoId;
            tx.peopleW();
            p.avatarPhotoId = id;
            if (old != null && !old.equals(id)) {
                files.delete(tx, old);
            }
        });
        return people.me(viewer);
    }

    public MeView removeAvatar(Viewer viewer) {
        store.update(tx -> {
            Person p = PeopleService.requirePerson(tx, viewer.personId());
            if (p.avatarPhotoId != null) {
                files.delete(tx, p.avatarPhotoId);
                tx.peopleW();
                p.avatarPhotoId = null;
            }
        });
        return people.me(viewer);
    }

    // MARK: - Aufraeumen

    /**
     * Gleicht ab, wo jedes Foto verwendet wird, und loescht, was seit 24 Stunden
     * nirgends haengt - dazu Dateien ohne Eintrag (etwa nach einem Absturz beim Hochladen).
     *
     * @return wie viele Fotos weg sind
     */
    public int cleanup(Instant now) {
        Instant cutoff = now.minus(UNUSED_GRACE);
        int removed = store.write(tx -> {
            Map<String, String> usedIn = new HashMap<>();
            Map<String, String> avatars = new HashMap<>();
            for (Cohabit c : tx.cohabits().cohabits) {
                for (Checkin ch : tx.checkins(c.id)) {
                    if (ch.photoId != null) {
                        usedIn.put(ch.photoId, c.id);
                    }
                }
                for (Message m : tx.messages(c.id)) {
                    if (m.photoId != null && !m.deleted) {
                        usedIn.put(m.photoId, c.id);
                    }
                }
            }
            for (Person p : tx.people().persons) {
                if (p.avatarPhotoId != null) {
                    avatars.put(p.avatarPhotoId, p.id);
                }
            }
            List<String> gone = new java.util.ArrayList<>();
            for (PhotoMeta meta : tx.photosW().photos) {
                String cohabit = usedIn.get(meta.id);
                String avatar = avatars.get(meta.id);
                meta.cohabitId = cohabit;
                meta.avatarOf = avatar;
                if (!meta.isUsed() && meta.createdAt.isBefore(cutoff)) {
                    gone.add(meta.id);
                }
            }
            files.delete(tx, gone);
            return gone.size();
        });
        removeOrphanFiles(cutoff);
        return removed;
    }

    private void removeOrphanFiles(Instant cutoff) {
        Path dir = store.photosDir();
        if (!Files.isDirectory(dir)) {
            return;
        }
        Set<String> known = store.read(data -> {
            Set<String> ids = new HashSet<>();
            data.photos().photos.forEach(p -> ids.add(p.id));
            return ids;
        });
        try (Stream<Path> list = Files.list(dir)) {
            for (Path file : list.toList()) {
                String name = file.getFileName().toString();
                String id = name.replaceFirst("(_thumb)?\\.jpg(\\.tmp)?$", "");
                if (known.contains(id)) {
                    continue;
                }
                if (Files.getLastModifiedTime(file).toInstant().isBefore(cutoff)) {
                    Files.deleteIfExists(file);
                }
            }
        } catch (IOException e) {
            log.warn("Aufraeumen der Fotos fehlgeschlagen", e);
        }
    }
}
