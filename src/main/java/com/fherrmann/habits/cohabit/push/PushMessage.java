package com.fherrmann.habits.cohabit.push;

import com.fherrmann.habits.cohabit.model.Gif;

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

    /**
     * Die Felder fuer das Bild in der Benachrichtigung: ein Foto des Dienstes laden die
     * Apps mit ihrem Token, ein GIF aus der Suche direkt von KLIPY.
     */
    public static Map<String, String> picture(String photoId, Gif gif) {
        Map<String, String> extra = new LinkedHashMap<>();
        if (photoId != null) {
            extra.put("photoId", photoId);
        } else if (gif != null) {
            extra.put("imageUrl", gif.gifUrl);
            if (gif.stillUrl != null) {
                extra.put("imageStillUrl", gif.stillUrl);
            }
        }
        return extra;
    }

    /** Ob die Benachrichtigung ein Bild bekommt - iOS braucht dann {@code mutable-content}. */
    public boolean hasPicture() {
        return extra.containsKey("photoId") || extra.containsKey("imageUrl");
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
