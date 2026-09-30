package com.fherrmann.habits.cohabit.service;

import com.fherrmann.habits.cohabit.model.Palette;
import com.fherrmann.habits.cohabit.model.Person;
import com.fherrmann.habits.cohabit.store.CohabitStore;

import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

/** Personen anlegen und Nutzernamen pruefen. */
public final class Persons {

    public static final Pattern USERNAME = Pattern.compile("[a-z][a-z0-9._]{2,19}");
    public static final int MAX_DISPLAY_NAME = 30;

    private Persons() {
    }

    public static boolean isUsernameTaken(CohabitStore.Data data, String username, String exceptPersonId) {
        return data.people().persons.stream()
                .anyMatch(p -> p.username.equals(username) && !p.id.equals(exceptPersonId));
    }

    /**
     * Ein freier Nutzername, so nah wie moeglich am Wunsch: unerlaubte Zeichen
     * werden zu Unterstrichen, zu kurze bekommen Ziffern, vergebene eine Zahl dahinter.
     */
    public static String freeUsername(CohabitStore.Data data, String wish) {
        String base = wish == null ? "" : wish.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._]", "_");
        if (base.isEmpty() || !Character.isLetter(base.charAt(0)) || base.charAt(0) > 'z') {
            base = "p" + base;
        }
        if (base.length() > 20) {
            base = base.substring(0, 20);
        }
        while (base.length() < 3) {
            base = base + "0";
        }
        String candidate = base;
        for (int i = 2; isUsernameTaken(data, candidate, null) || !USERNAME.matcher(candidate).matches(); i++) {
            String suffix = String.valueOf(i);
            candidate = base.substring(0, Math.min(base.length(), 20 - suffix.length())) + suffix;
        }
        return candidate;
    }

    public static Person create(CohabitStore.Tx tx, String id, String displayName, String username, Instant now) {
        Person person = new Person();
        person.id = id;
        person.displayName = displayName;
        person.username = username;
        person.color = Palette.forId(id);
        person.createdAt = now;
        tx.peopleW().persons.add(person);
        return person;
    }

    /** "torben" -> "Torben": der Anzeigename einer Healthy-Person beim ersten Zugriff. */
    public static String displayNameFromId(String id) {
        if (id == null || id.isEmpty()) {
            return "?";
        }
        String name = id.replace('_', ' ').replace('-', ' ');
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
