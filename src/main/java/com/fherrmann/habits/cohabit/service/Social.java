package com.fherrmann.habits.cohabit.service;

import com.fherrmann.habits.cohabit.model.FriendRequest;
import com.fherrmann.habits.cohabit.model.Friendship;
import com.fherrmann.habits.cohabit.model.Person;
import com.fherrmann.habits.cohabit.store.CohabitStore;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Freundschaften und Blocks - klein, aber an vielen Stellen gebraucht. */
public final class Social {

    private Social() {
    }

    public static boolean areFriends(CohabitStore.Data data, String a, String b) {
        return data.people().friendships.stream().anyMatch(f -> f.involves(a) && f.involves(b) && !a.equals(b));
    }

    public static List<String> friendIds(CohabitStore.Data data, String personId) {
        return data.people().friendships.stream()
                .filter(f -> f.involves(personId))
                .map(f -> f.other(personId))
                .toList();
    }

    /** Ob einer der beiden den anderen blockiert hat - dann gibt es zwischen ihnen nichts. */
    public static boolean blockedEitherWay(CohabitStore.Data data, String a, String b) {
        return hasBlocked(data, a, b) || hasBlocked(data, b, a);
    }

    /** Ob {@code who} die Person {@code whom} blockiert hat. */
    public static boolean hasBlocked(CohabitStore.Data data, String who, String whom) {
        return data.person(who).map(p -> p.blocked.contains(whom)).orElse(false);
    }

    public static void befriend(CohabitStore.Tx tx, String a, String b, Instant now) {
        if (a.equals(b) || areFriends(tx, a, b) || blockedEitherWay(tx, a, b)) {
            return;
        }
        Friendship f = new Friendship();
        f.a = a;
        f.b = b;
        f.since = now;
        tx.peopleW().friendships.add(f);
        // Offene Anfragen zwischen den beiden haben sich damit erledigt.
        tx.peopleW().friendRequests.removeIf(r -> between(r, a, b));
    }

    public static boolean between(FriendRequest r, String a, String b) {
        return (r.fromId.equals(a) && r.toId.equals(b)) || (r.fromId.equals(b) && r.toId.equals(a));
    }

    public static Optional<Person> byUsername(CohabitStore.Data data, String username) {
        if (username == null) {
            return Optional.empty();
        }
        String wanted = username.trim().toLowerCase(java.util.Locale.ROOT).replaceFirst("^@", "");
        return data.people().persons.stream().filter(p -> p.username.equals(wanted)).findFirst();
    }
}
