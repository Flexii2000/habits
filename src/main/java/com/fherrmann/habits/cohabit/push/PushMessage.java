package com.fherrmann.habits.cohabit.push;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Eine Benachrichtigung, wie beide Plattformen sie bekommen: iOS als Alert plus
 * Daten, Android als reine Datennachricht (die App baut die Benachrichtigung).
 *
 * @param extra weitere Datenfelder, etwa {@code versionCode} beim App-Update
 */
public record PushMessage(String kind, String title, String body, String cohabitId, String link,
                          Map<String, String> extra) {

    public PushMessage(String kind, String title, String body, String cohabitId, String link) {
        this(kind, title, body, cohabitId, link, Map.of());
    }

    /** Die Datenfelder - bei FCM ausschliesslich Strings, ohne leere. */
    public Map<String, String> data() {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("kind", kind);
        data.put("title", title == null ? "" : title);
        data.put("body", body == null ? "" : body);
        if (cohabitId != null) {
            data.put("cohabitId", cohabitId);
        }
        if (link != null) {
            data.put("link", link);
        }
        data.putAll(extra);
        return data;
    }
}
