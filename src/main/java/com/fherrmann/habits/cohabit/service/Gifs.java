package com.fherrmann.habits.cohabit.service;

import com.fherrmann.habits.cohabit.model.Gif;

import java.util.regex.Pattern;

/**
 * Prueft ein GIF aus der Suche, bevor es im Chat landet. Die Adressen laden alle
 * Mitglieder - deshalb nur KLIPYs eigene Medien-Hosts, nie eine beliebige URL, die
 * verraten wuerde, wer wann den Chat oeffnet.
 */
public final class Gifs {

    public static final String KLIPY = "KLIPY";

    /** static.klipy.com, static1.klipy.com, static2.klipy.com - deren Medien-Hosts. */
    static final Pattern MEDIA_URL = Pattern.compile("^https://static[0-9]*\\.klipy\\.com/[^\\s\"<>\\\\]+$");
    static final int MAX_URL = 500;
    static final int MAX_SLUG = 200;
    static final int MAX_TITLE = 200;
    static final int MAX_EDGE = 4096;

    /** Was der Client aus dem Suchergebnis schickt ({@code file.md}). */
    public record Input(String slug, String title, Integer width, Integer height, String gifUrl, String webpUrl,
                        String mp4Url, String stillUrl) {
    }

    private Gifs() {
    }

    public static Gif validate(Input in) {
        String gifUrl = url(in.gifUrl(), true);
        int width = edge(in.width());
        int height = edge(in.height());
        String slug = in.slug() == null || in.slug().isBlank() ? null : in.slug().strip();
        if (slug != null && slug.length() > MAX_SLUG) {
            throw invalid();
        }
        Gif gif = new Gif();
        gif.provider = KLIPY;
        gif.slug = slug;
        gif.title = in.title() == null || in.title().isBlank() ? null : truncate(in.title().strip());
        gif.width = width;
        gif.height = height;
        gif.gifUrl = gifUrl;
        gif.webpUrl = url(in.webpUrl(), false);
        gif.mp4Url = url(in.mp4Url(), false);
        gif.stillUrl = url(in.stillUrl(), false);
        return gif;
    }

    /** Ob eine Adresse zu KLIPYs Medien gehoert - auch fuer die Push-Vorschau. */
    public static boolean isMediaUrl(String url) {
        return url != null && url.length() <= MAX_URL && MEDIA_URL.matcher(url).matches();
    }

    private static String url(String raw, boolean required) {
        String url = raw == null || raw.isBlank() ? null : raw.strip();
        if (url == null) {
            if (required) {
                throw invalid();
            }
            return null;
        }
        if (!isMediaUrl(url)) {
            throw invalid();
        }
        return url;
    }

    private static int edge(Integer value) {
        if (value == null || value < 1 || value > MAX_EDGE) {
            throw invalid();
        }
        return value;
    }

    private static String truncate(String title) {
        if (title.length() <= MAX_TITLE) {
            return title;
        }
        // Nicht mitten in einem Ersatzzeichenpaar abschneiden.
        int end = Character.isHighSurrogate(title.charAt(MAX_TITLE - 1)) ? MAX_TITLE - 1 : MAX_TITLE;
        return title.substring(0, end);
    }

    private static RuntimeException invalid() {
        return Errors.badRequest("Das GIF ist ungültig.");
    }
}
