package com.fherrmann.habits.cohabit.service;

import com.fherrmann.habits.cohabit.model.AppToken;
import com.fherrmann.habits.cohabit.model.Person;
import com.fherrmann.habits.cohabit.store.CohabitStore;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * App-Token: 48 Hexzeichen, gespeichert nur als SHA-256. Die Apps bekommen nie den
 * Master-Token {@code FH_PRIVATE_TOKEN}, sondern je Geraet einen eigenen, einzeln
 * widerrufbaren.
 */
@Service
public class AppTokens {

    /** Den letzten Gebrauch hoechstens stuendlich schreiben - sonst schriebe jede Anfrage people.json. */
    static final Duration TOUCH_INTERVAL = Duration.ofHours(1);

    private static final SecureRandom RANDOM = new SecureRandom();

    public record Match(String personId, String tokenId, Instant lastUsedAt) {
    }

    public record Issued(AppToken token, String secret) {
    }

    private final CohabitStore store;

    public AppTokens(CohabitStore store) {
        this.store = store;
    }

    public static String newSecret() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public static String hash(String secret) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public Optional<Match> lookup(String secret) {
        if (secret == null || secret.length() < 16 || secret.length() > 200) {
            return Optional.empty();
        }
        String hash = hash(secret.trim());
        return store.read(data -> {
            for (Person p : data.people().persons) {
                for (AppToken t : p.appTokens) {
                    if (MessageDigest.isEqual(t.hash.getBytes(StandardCharsets.UTF_8),
                            hash.getBytes(StandardCharsets.UTF_8))) {
                        return Optional.of(new Match(p.id, t.id, t.lastUsedAt));
                    }
                }
            }
            return Optional.<Match>empty();
        });
    }

    /** Stellt einen neuen Token aus; das Geheimnis gibt es nur in diesem Moment. */
    public static Issued issue(Person person, String label, Instant now) {
        String secret = newSecret();
        AppToken token = new AppToken();
        token.id = UUID.randomUUID().toString();
        token.label = label;
        token.hash = hash(secret);
        token.createdAt = now;
        person.appTokens.add(token);
        return new Issued(token, secret);
    }

    public void touch(Match match, Instant now) {
        if (match.lastUsedAt() != null && match.lastUsedAt().plus(TOUCH_INTERVAL).isAfter(now)) {
            return;
        }
        store.update(tx -> tx.person(match.personId()).ifPresent(p -> {
            for (AppToken t : p.appTokens) {
                if (t.id.equals(match.tokenId())) {
                    tx.peopleW();
                    t.lastUsedAt = now;
                }
            }
        }));
    }
}
