package com.fherrmann.habits.cohabit.api;

import com.fherrmann.habits.cohabit.model.Person;

import java.util.Locale;

public record PersonView(String id, String displayName, String username, String initials, String color,
                         String avatarPhotoId) {

    public static PersonView of(Person p) {
        if (p == null) {
            return null;
        }
        return new PersonView(p.id, p.displayName, p.username, initials(p.displayName), p.color, p.avatarPhotoId);
    }

    /** Zwei Buchstaben aus dem Anzeigenamen: zwei Woerter -> je der erste, ein Wort -> die ersten zwei. */
    public static String initials(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return "?";
        }
        String[] words = displayName.trim().split("\\s+");
        String result;
        if (words.length >= 2) {
            result = firstLetter(words[0]) + firstLetter(words[1]);
        } else {
            int[] cps = words[0].codePoints().limit(2).toArray();
            result = new String(cps, 0, cps.length);
        }
        return result.toUpperCase(Locale.GERMANY);
    }

    private static String firstLetter(String word) {
        return new String(Character.toChars(word.codePointAt(0)));
    }
}
