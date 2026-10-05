package com.fherrmann.habits.cohabit.service;

import com.fherrmann.habits.cohabit.model.ReactionKind;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Reaktionen sind Emojis - genau eines, und so vereinheitlicht, dass ❤ aus der
 * einen Tastatur und ❤️ aus der anderen dieselbe Reaktion sind.
 */
public final class Emojis {

    /** Die laengsten Familien- und Flaggenfolgen passen hinein, ganze Saetze nicht. */
    static final int MAX_LENGTH = 32;

    private static final Pattern ONE_GRAPHEME = Pattern.compile("\\X");
    private static final int VS15 = 0xFE0E;
    private static final int VS16 = 0xFE0F;
    private static final int ZWJ = 0x200D;
    private static final int KEYCAP = 0x20E3;

    private Emojis() {
    }

    /** @return das vereinheitlichte Emoji, oder {@code null}, wenn {@code raw} keins ist */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.strip();
        String legacy = legacy(s);
        if (legacy != null) {
            return legacy;
        }
        s = s.replace(String.valueOf((char) VS15), "");
        if (s.isEmpty() || s.length() > MAX_LENGTH || !ONE_GRAPHEME.matcher(s).matches()) {
            return null;
        }
        boolean pictographic = false;
        boolean keycap = false;
        int regional = 0;
        int[] codePoints = s.codePoints().toArray();
        for (int cp : codePoints) {
            if (Character.isExtendedPictographic(cp)) {
                pictographic = true;
            } else if (cp == KEYCAP) {
                keycap = true;
            } else if (cp >= 0x1F1E6 && cp <= 0x1F1FF) {
                regional++;
            } else if (!isJoiner(cp) && !isKeycapBase(cp)) {
                return null;
            }
        }
        if (keycap ? !isKeycap(codePoints) : !pictographic && regional != 2) {
            return null;
        }
        if (!keycap && isKeycapBase(codePoints[0])) {
            return null;
        }
        // Ein Zeichen, das ohne Zusatz als Text erscheint (❤, ☀), bekommt die Emoji-Darstellung.
        if (codePoints.length == 1 && !Character.isEmojiPresentation(codePoints[0])) {
            s = s + (char) VS16;
        }
        return s;
    }

    /** Die feste Auswahl bis 05.10. - aeltere Apps und der Postausgang schicken sie weiter. */
    private static String legacy(String s) {
        try {
            return ReactionKind.valueOf(s.toUpperCase(Locale.ROOT)).emoji();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Was innerhalb eines Emojis vorkommen darf, ohne selbst eines zu sein. */
    private static boolean isJoiner(int cp) {
        return cp == VS16 || cp == ZWJ
                || Character.isEmojiModifier(cp)
                || (cp >= 0xE0020 && cp <= 0xE007F); // Tag-Zeichen der Regionalflaggen (England, Schottland)
    }

    /** 1️⃣, #️⃣, *️⃣: Ziffer bzw. # oder *, optional VS16, dann die Tastenkappe. */
    private static boolean isKeycap(int[] codePoints) {
        return isKeycapBase(codePoints[0]) && codePoints[codePoints.length - 1] == KEYCAP
                && (codePoints.length == 2 || (codePoints.length == 3 && codePoints[1] == VS16));
    }

    private static boolean isKeycapBase(int cp) {
        return (cp >= '0' && cp <= '9') || cp == '#' || cp == '*';
    }
}
