package com.fherrmann.habits.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Die Healthy-Personen: die Eigentuemerin ({@code HEALTH_OWNER}, Vorgabe
 * {@code felix}) und jede weitere mit eigenem Token aus {@code HEALTH_TOKENS}
 * (wortgleich wie in {@code /etc/food.env}). Dieselbe Klasse steht im
 * Kalorienzaehler und im Weight Tracker.
 *
 * <p>Hier zusaetzlich gebraucht: der Token einer Person im Klartext, um fuer sie
 * beim Kalorienzaehler und beim Weight Tracker nachzufragen (automatische
 * Co-Habits, per Bearer).
 */
@Component
public class HealthUsers {

    private static final Pattern VALID_NAME = Pattern.compile("[a-z][a-z0-9_-]{0,31}");

    /** 64 Bit sind das Mindeste; setup-health-users.sh erzeugt 192. */
    private static final int MIN_TOKEN_LENGTH = 16;

    private record Entry(String name, String token, byte[] bytes) {
    }

    private final String owner;
    private final List<Entry> entries = new ArrayList<>();

    public HealthUsers(
            @Value("${health.owner:felix}") String owner,
            @Value("${health.tokens:}") String configured) {
        this.owner = owner == null ? "" : owner.trim();
        if (!isValidName(this.owner)) {
            throw new IllegalStateException("health.owner: ungueltiger Name '" + owner + "'");
        }
        if (configured == null) {
            return;
        }
        for (String part : configured.split(",")) {
            String piece = part.trim();
            if (piece.isEmpty()) {
                continue;
            }
            int colon = piece.indexOf(':');
            if (colon <= 0 || colon == piece.length() - 1) {
                throw new IllegalStateException("health.tokens: erwartet name:token, bekommen: " + mask(piece));
            }
            String name = piece.substring(0, colon).trim();
            String token = piece.substring(colon + 1).trim();
            if (!isValidName(name)) {
                throw new IllegalStateException("health.tokens: ungueltiger Name '" + name + "'");
            }
            if (token.length() < MIN_TOKEN_LENGTH) {
                throw new IllegalStateException("health.tokens: der Token von " + name + " ist zu kurz.");
            }
            if (entries.stream().anyMatch(e -> e.name().equals(name))) {
                throw new IllegalStateException("health.tokens: " + name + " steht doppelt drin.");
            }
            if (nameFor(token).isPresent()) {
                throw new IllegalStateException("health.tokens: der Token von " + name + " ist schon vergeben.");
            }
            entries.add(new Entry(name, token, token.getBytes(StandardCharsets.UTF_8)));
        }
    }

    public String owner() {
        return owner;
    }

    public boolean isOwner(String name) {
        return owner.equals(name);
    }

    /** Ob die Person einen Healthy-Zugang hat (Eigentuemerin eingeschlossen). */
    public boolean isHealthPerson(String name) {
        return isOwner(name) || entries.stream().anyMatch(e -> e.name().equals(name));
    }

    /** Der Name zum Token - in konstanter Zeit ueber alle Eintraege, ohne fruehes Ende. */
    public Optional<String> nameFor(String supplied) {
        if (supplied == null || supplied.isEmpty()) {
            return Optional.empty();
        }
        byte[] candidate = supplied.getBytes(StandardCharsets.UTF_8);
        String found = null;
        for (Entry entry : entries) {
            if (MessageDigest.isEqual(candidate, entry.bytes()) && found == null) {
                found = entry.name();
            }
        }
        return Optional.ofNullable(found);
    }

    /** Der Healthy-Token einer weiteren Person - fuer Anfragen in ihrem Namen. */
    public Optional<String> tokenOf(String name) {
        return entries.stream().filter(e -> e.name().equals(name)).map(Entry::token).findFirst();
    }

    /** Alle bekannten Personen, die Eigentuemerin zuerst. */
    public List<String> names() {
        Set<String> names = new LinkedHashSet<>();
        names.add(owner);
        entries.forEach(e -> names.add(e.name()));
        return List.copyOf(names);
    }

    public static boolean isValidName(String name) {
        return name != null && VALID_NAME.matcher(name).matches();
    }

    /** Ein Token gehoert nicht in eine Fehlermeldung, die im Journal landet. */
    private static String mask(String piece) {
        int colon = piece.indexOf(':');
        return colon < 0 ? "(ohne Doppelpunkt)" : piece.substring(0, colon) + ":…";
    }
}
