package com.fherrmann.habits.cohabit.release;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Die veroeffentlichte Android-App: {@code cohabit.apk} und {@code latest.json}
 * ({@code {"versionCode": 3, "versionName": "1.2"}}) in {@code /opt/cohabit-android},
 * gefuellt von {@code tools/publish.sh} im Repo cohabit-android. Kein Play Store - die
 * App ist fuer eine Handvoll Freunde. Der Dienst liest das Verzeichnis nur.
 */
@Component
public class AndroidRelease {

    private static final Logger log = LoggerFactory.getLogger(AndroidRelease.class);

    static final String APK = "cohabit.apk";
    static final String META = "latest.json";

    /** Die neueste Version, wie die App sie vergleicht; sha256 aus genau der ausgelieferten Datei. */
    public record Info(int versionCode, String versionName, long sizeBytes, String sha256) {
    }

    private record Digest(long modified, long size, String sha256) {
    }

    private final Path dir;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private volatile Digest digest;

    public AndroidRelease(@Value("${cohabit.android.dir:}") String dir) {
        this.dir = dir == null || dir.isBlank() ? null : Path.of(dir.trim());
    }

    public Optional<Info> latest() {
        if (dir == null) {
            return Optional.empty();
        }
        Path apk = dir.resolve(APK);
        Path meta = dir.resolve(META);
        if (!Files.isRegularFile(apk) || !Files.isRegularFile(meta)) {
            return Optional.empty();
        }
        try {
            JsonNode json = objectMapper.readTree(Files.readString(meta));
            int code = json.path("versionCode").asInt(0);
            String name = json.path("versionName").asString("");
            if (code <= 0 || name.isBlank()) {
                log.warn("{} ohne gueltige versionCode/versionName", meta);
                return Optional.empty();
            }
            long size = Files.size(apk);
            return Optional.of(new Info(code, name, size, sha256(apk, size)));
        } catch (IOException | RuntimeException e) {
            log.warn("Android-Version nicht lesbar in {}", dir, e);
            return Optional.empty();
        }
    }

    public Path apk() {
        return dir == null ? null : dir.resolve(APK);
    }

    /** Die Pruefsumme nur neu rechnen, wenn sich die Datei geaendert hat. */
    private String sha256(Path apk, long size) throws IOException {
        long modified = Files.getLastModifiedTime(apk).toMillis();
        Digest known = digest;
        if (known != null && known.modified() == modified && known.size() == size) {
            return known.sha256();
        }
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (InputStream in = Files.newInputStream(apk)) {
            byte[] buffer = new byte[64 * 1024];
            for (int read; (read = in.read(buffer)) > 0; ) {
                md.update(buffer, 0, read);
            }
        }
        String hex = HexFormat.of().formatHex(md.digest());
        digest = new Digest(modified, size, hex);
        return hex;
    }
}
