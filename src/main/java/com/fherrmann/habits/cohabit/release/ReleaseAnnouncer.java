package com.fherrmann.habits.cohabit.release;

import com.fherrmann.habits.cohabit.push.Notifier;
import com.fherrmann.habits.cohabit.push.PushMessage;
import com.fherrmann.habits.cohabit.store.CohabitStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Sagt allen Android-Geraeten Bescheid, sobald eine neue Version veroeffentlicht ist.
 * Schaut alle paar Minuten selbst nach - das Veroeffentlichen auf dem Mac muss nichts
 * ueber den Dienst wissen. Fehlt der Merker, wird die vorhandene Version nur vermerkt:
 * die hat, wer die App gerade von Hand installiert hat.
 */
@Component
public class ReleaseAnnouncer {

    private static final Logger log = LoggerFactory.getLogger(ReleaseAnnouncer.class);

    private final AndroidRelease release;
    private final Notifier notifier;
    private final CohabitStore store;
    private final Path announcedFile;
    private final boolean enabled;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    public ReleaseAnnouncer(AndroidRelease release, Notifier notifier, CohabitStore store,
                            @Value("${cohabit.android.announced-file:data/cohabit/android-release.json}") String file,
                            @Value("${cohabit.scheduler.enabled:true}") boolean enabled) {
        this.release = release;
        this.notifier = notifier;
        this.store = store;
        this.announcedFile = Path.of(file);
        this.enabled = enabled;
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT5M")
    public void scheduled() {
        if (enabled) {
            check();
        }
    }

    /** @return ob angekuendigt wurde */
    public boolean check() {
        Optional<AndroidRelease.Info> latest = release.latest();
        if (latest.isEmpty()) {
            return false;
        }
        AndroidRelease.Info info = latest.get();
        Integer announced = readAnnounced();
        if (announced != null && info.versionCode() <= announced) {
            return false;
        }
        boolean announce = announced != null;
        if (announce) {
            log.info("Kuendige Android-Version {} ({}) an", info.versionName(), info.versionCode());
            store.update(tx -> notifier.broadcast(tx, "android", new PushMessage("app-update",
                    "Neue Version " + info.versionName(), "Antippen zum Installieren.", null, null,
                    Map.of("versionCode", String.valueOf(info.versionCode()), "versionName", info.versionName()))));
        }
        writeAnnounced(info.versionCode());
        return announce;
    }

    private Integer readAnnounced() {
        if (!Files.exists(announcedFile)) {
            return null;
        }
        try {
            return objectMapper.readTree(Files.readString(announcedFile)).path("versionCode").asInt(0);
        } catch (IOException | RuntimeException e) {
            log.warn("{} nicht lesbar - behandle es, als fehlte es", announcedFile, e);
            return null;
        }
    }

    private void writeAnnounced(int versionCode) {
        try {
            if (announcedFile.getParent() != null) {
                Files.createDirectories(announcedFile.getParent());
            }
            Files.writeString(announcedFile, objectMapper.writeValueAsString(Map.of("versionCode", versionCode)));
        } catch (IOException e) {
            log.warn("{} nicht schreibbar", announcedFile, e);
        }
    }
}
