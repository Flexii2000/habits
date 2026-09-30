package com.fherrmann.habits.cohabit.service;

import com.fherrmann.habits.cohabit.api.FriendRequestView;
import com.fherrmann.habits.cohabit.api.FriendsView;
import com.fherrmann.habits.cohabit.api.PersonView;
import com.fherrmann.habits.cohabit.api.SearchResult;
import com.fherrmann.habits.cohabit.model.FriendRequest;
import com.fherrmann.habits.cohabit.model.Person;
import com.fherrmann.habits.cohabit.push.Notifier;
import com.fherrmann.habits.cohabit.push.PushMessage;
import com.fherrmann.habits.cohabit.push.Setting;
import com.fherrmann.habits.cohabit.store.CohabitStore;
import com.fherrmann.habits.security.Viewer;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Freunde, Anfragen, Suche und Blocks. */
@Service
public class FriendsService {

    static final int SEARCH_LIMIT = 20;

    private final CohabitStore store;
    private final Notifier notifier;
    private final Clock clock;

    public FriendsService(CohabitStore store, Notifier notifier, Clock clock) {
        this.store = store;
        this.notifier = notifier;
        this.clock = clock;
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }

    public FriendsView friends(Viewer viewer) {
        return store.read(data -> view(data, viewer.personId()));
    }

    static FriendsView view(CohabitStore.Data data, String me) {
        List<PersonView> friends = Social.friendIds(data, me).stream()
                .map(id -> data.person(id).orElse(null))
                .filter(p -> p != null)
                .sorted(Comparator.comparing((Person p) -> p.displayName.toLowerCase(Locale.GERMANY)))
                .map(PersonView::of)
                .toList();
        List<FriendRequestView> incoming = data.people().friendRequests.stream()
                .filter(r -> r.toId.equals(me))
                .map(r -> requestView(data, r))
                .toList();
        List<FriendRequestView> outgoing = data.people().friendRequests.stream()
                .filter(r -> r.fromId.equals(me))
                .map(r -> requestView(data, r))
                .toList();
        return new FriendsView(friends, incoming, outgoing);
    }

    static FriendRequestView requestView(CohabitStore.Data data, FriendRequest r) {
        return new FriendRequestView(r.id, PersonView.of(data.person(r.fromId).orElse(null)),
                PersonView.of(data.person(r.toId).orElse(null)), r.createdAt);
    }

    public List<SearchResult> search(Viewer viewer, String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.GERMANY).replaceFirst("^@", "");
        if (q.length() < 2) {
            throw Errors.badRequest("Bitte mindestens zwei Zeichen eingeben.");
        }
        String me = viewer.personId();
        return store.read(data -> data.people().persons.stream()
                .filter(p -> p.username.startsWith(q) || p.displayName.toLowerCase(Locale.GERMANY).startsWith(q)
                        || nameWordStarts(p.displayName, q))
                .filter(p -> !Social.blockedEitherWay(data, me, p.id))
                .sorted(Comparator.comparing((Person p) -> p.username))
                .limit(SEARCH_LIMIT)
                .map(p -> new SearchResult(PersonView.of(p), relation(data, me, p.id)))
                .toList());
    }

    private static boolean nameWordStarts(String displayName, String q) {
        for (String word : displayName.toLowerCase(Locale.GERMANY).split("\\s+")) {
            if (word.startsWith(q)) {
                return true;
            }
        }
        return false;
    }

    static String relation(CohabitStore.Data data, String me, String other) {
        if (me.equals(other)) {
            return "SELF";
        }
        if (Social.areFriends(data, me, other)) {
            return "FRIEND";
        }
        for (FriendRequest r : data.people().friendRequests) {
            if (r.fromId.equals(me) && r.toId.equals(other)) {
                return "REQUEST_SENT";
            }
            if (r.fromId.equals(other) && r.toId.equals(me)) {
                return "REQUEST_RECEIVED";
            }
        }
        return "NONE";
    }

    public FriendRequestView request(Viewer viewer, String username) {
        String me = viewer.personId();
        return store.write(tx -> {
            Person target = Social.byUsername(tx, username)
                    .filter(p -> !Social.blockedEitherWay(tx, me, p.id))
                    .orElseThrow(() -> Errors.notFound("Niemand mit diesem Nutzernamen."));
            if (target.id.equals(me)) {
                throw Errors.badRequest("Mit dir selbst bist du schon befreundet.");
            }
            if (Social.areFriends(tx, me, target.id)) {
                throw Errors.conflict("Ihr seid schon befreundet.");
            }
            String relation = relation(tx, me, target.id);
            if (relation.equals("REQUEST_SENT")) {
                throw Errors.conflict("Du hast schon angefragt.");
            }
            if (relation.equals("REQUEST_RECEIVED")) {
                throw Errors.conflict("Die Person hat dich schon angefragt.");
            }
            FriendRequest r = new FriendRequest();
            r.id = UUID.randomUUID().toString();
            r.fromId = me;
            r.toId = target.id;
            r.createdAt = now();
            tx.peopleW().friendRequests.add(r);
            Person from = PeopleService.requirePerson(tx, me);
            notifier.notify(tx, List.of(target.id), new PushMessage("friend-request", "Freundschaftsanfrage",
                    from.displayName + " (@" + from.username + ") möchte mit dir befreundet sein.", null,
                    "cohabit://friends"), Setting.INVITES, null, me);
            return requestView(tx, r);
        });
    }

    public FriendsView accept(Viewer viewer, String requestId) {
        String me = viewer.personId();
        return store.write(tx -> {
            FriendRequest r = find(tx, requestId);
            if (!r.toId.equals(me)) {
                throw Errors.forbidden("Diese Anfrage kannst nur du annehmen, wenn sie an dich ging.");
            }
            tx.peopleW().friendRequests.remove(r);
            Social.befriend(tx, r.fromId, r.toId, now());
            return view(tx, me);
        });
    }

    /** Ablehnen (Empfaenger) oder zuruecknehmen (Absender). */
    public FriendsView decline(Viewer viewer, String requestId) {
        String me = viewer.personId();
        return store.write(tx -> {
            FriendRequest r = find(tx, requestId);
            if (!r.toId.equals(me) && !r.fromId.equals(me)) {
                throw Errors.notFound("Anfrage nicht gefunden.");
            }
            tx.peopleW().friendRequests.remove(r);
            return view(tx, me);
        });
    }

    private static FriendRequest find(CohabitStore.Data data, String id) {
        return data.people().friendRequests.stream().filter(r -> r.id.equals(id)).findFirst()
                .orElseThrow(() -> Errors.notFound("Anfrage nicht gefunden."));
    }

    public void unfriend(Viewer viewer, String personId) {
        String me = viewer.personId();
        store.update(tx -> {
            if (tx.people().friendships.stream().anyMatch(f -> f.involves(me) && f.involves(personId))) {
                tx.peopleW().friendships.removeIf(f -> f.involves(me) && f.involves(personId) && !me.equals(personId));
            }
        });
    }

    // MARK: - Blocks

    public List<PersonView> blocks(Viewer viewer) {
        return store.read(data -> PeopleService.requirePerson(data, viewer.personId()).blocked.stream()
                .map(id -> PersonView.of(data.person(id).orElse(null)))
                .filter(v -> v != null)
                .toList());
    }

    /**
     * Blockieren trennt: Freundschaft, Anfragen und offene Einladungen zwischen den
     * beiden verschwinden; Nachrichten und Timeline der Person sind fuer mich
     * ausgeblendet, und sie kann mich weder einladen noch anfragen.
     */
    public void block(Viewer viewer, String personId) {
        String me = viewer.personId();
        store.update(tx -> {
            if (personId == null || personId.equals(me) || tx.person(personId).isEmpty()) {
                throw Errors.notFound("Person nicht gefunden.");
            }
            Person p = PeopleService.requirePerson(tx, me);
            tx.peopleW();
            if (!p.blocked.contains(personId)) {
                p.blocked.add(personId);
            }
            tx.peopleW().friendships.removeIf(f -> f.involves(me) && f.involves(personId));
            tx.peopleW().friendRequests.removeIf(r -> Social.between(r, me, personId));
            tx.cohabitsW().invitations.removeIf(i -> (i.fromId.equals(me) && i.toId.equals(personId))
                    || (i.fromId.equals(personId) && i.toId.equals(me)));
        });
    }

    public void unblock(Viewer viewer, String personId) {
        store.update(tx -> {
            Person p = PeopleService.requirePerson(tx, viewer.personId());
            if (p.blocked.remove(personId)) {
                tx.peopleW();
            }
        });
    }
}
