package com.fherrmann.habits.cohabit.service;

import com.fherrmann.habits.cohabit.api.AppLinkCreated;
import com.fherrmann.habits.cohabit.api.AppLinkView;
import com.fherrmann.habits.cohabit.api.MeView;
import com.fherrmann.habits.cohabit.api.NotificationSettings;
import com.fherrmann.habits.cohabit.api.PersonView;
import com.fherrmann.habits.cohabit.model.AppToken;
import com.fherrmann.habits.cohabit.model.AutoSource;
import com.fherrmann.habits.cohabit.model.ChallengeRound;
import com.fherrmann.habits.cohabit.model.Cohabit;
import com.fherrmann.habits.cohabit.model.Device;
import com.fherrmann.habits.cohabit.model.Palette;
import com.fherrmann.habits.cohabit.model.Person;
import com.fherrmann.habits.cohabit.model.TypeColors;
import com.fherrmann.habits.cohabit.store.CohabitStore;
import com.fherrmann.habits.security.HealthUsers;
import com.fherrmann.habits.security.Viewer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Die eigene Person: Profil, Einstellungen, App-Links, Geraete. */
@Service
public class PeopleService {

    static final int MAX_LABEL = 40;
    static final int MAX_DEVICES = 10;

    private final CohabitStore store;
    private final HealthUsers users;
    private final AutoSources sources;
    private final Clock clock;
    private final String publicUrl;

    public PeopleService(CohabitStore store, HealthUsers users, AutoSources sources, Clock clock,
                         @Value("${cohabit.public-url:https://fherrmann.com/cohabit}") String publicUrl) {
        this.store = store;
        this.users = users;
        this.sources = sources;
        this.clock = clock;
        this.publicUrl = publicUrl.replaceAll("/+$", "");
    }

    public String publicUrl() {
        return publicUrl;
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }

    /** Eine Healthy-Person, die coHabit noch nicht kennt, entsteht beim ersten Zugriff. */
    public void ensureHealthPerson(String name) {
        if (store.read(data -> data.person(name).isPresent())) {
            return;
        }
        store.update(tx -> {
            if (tx.person(name).isEmpty()) {
                Persons.create(tx, name, Persons.displayNameFromId(name), Persons.freeUsername(tx, name), now());
            }
        });
    }

    public static Person requirePerson(CohabitStore.Data data, String personId) {
        return data.person(personId).orElseThrow(Errors::unauthorized);
    }

    // MARK: - Ich

    public MeView me(Viewer viewer) {
        return store.read(data -> meView(data, viewer));
    }

    public MeView meView(CohabitStore.Data data, Viewer viewer) {
        Person p = requirePerson(data, viewer.personId());
        int cohabits = (int) data.cohabits().cohabits.stream()
                .filter(c -> !c.archived && c.isMember(p.id)).count();
        int friends = Social.friendIds(data, p.id).size();
        int pending = (int) data.cohabits().invitations.stream()
                .filter(i -> i.toId.equals(p.id))
                .filter(i -> data.cohabit(i.cohabitId).map(c -> !c.archived).orElse(false))
                .count();
        int incoming = (int) data.people().friendRequests.stream().filter(r -> r.toId.equals(p.id)).count();
        List<String> sourceNames = sources.sourcesOf(p.id).stream().map(AutoSource::name).toList();
        return new MeView(PersonView.of(p), users.isOwner(p.id), sourceNames,
                new MeView.Counts(cohabits, friends, wins(data, p.id)), pending, incoming,
                viewer.via().isAppToken(), p.createdAt, TypeColors.effective(p.typeColors));
    }

    /** Gewonnene Challenge-Runden - ein geteilter erster Platz zaehlt. */
    public static int wins(CohabitStore.Data data, String personId) {
        int wins = 0;
        for (Cohabit c : data.cohabits().cohabits) {
            if (c.challengeState == null) {
                continue;
            }
            for (ChallengeRound round : c.challengeState.rounds) {
                if (round.winners.contains(personId)) {
                    wins++;
                }
            }
        }
        return wins;
    }

    public MeView updateMe(Viewer viewer, String displayName, String username) {
        String name = displayName == null ? "" : displayName.trim().replaceAll("\\s+", " ");
        if (name.isEmpty() || name.length() > Persons.MAX_DISPLAY_NAME) {
            throw Errors.badRequest("Der Anzeigename braucht 1 bis 30 Zeichen.");
        }
        String user = username == null ? "" : username.trim().toLowerCase(Locale.ROOT).replaceFirst("^@", "");
        if (!Persons.USERNAME.matcher(user).matches()) {
            throw Errors.badRequest("Der Nutzername braucht 3 bis 20 Zeichen (a–z, 0–9, Punkt, Unterstrich) "
                    + "und beginnt mit einem Buchstaben.");
        }
        return store.write(tx -> {
            Person p = requirePerson(tx, viewer.personId());
            if (Persons.isUsernameTaken(tx, user, p.id)) {
                throw Errors.conflict("Der Nutzername ist schon vergeben.");
            }
            tx.peopleW();
            p.displayName = name;
            p.username = user;
            return meView(tx, viewer);
        });
    }

    public Map<String, String> typeColors(Viewer viewer) {
        return store.read(data -> TypeColors.effective(requirePerson(data, viewer.personId()).typeColors));
    }

    /**
     * Setzt einzelne Typfarben; ein Platz mit {@code null} faellt auf die Vorgabe zurueck.
     * Erst alles pruefen, dann schreiben - eine unbekannte Farbe aendert nichts.
     */
    public Map<String, String> updateTypeColors(Viewer viewer, Map<String, String> changes) {
        if (changes != null) {
            for (Map.Entry<String, String> e : changes.entrySet()) {
                if (!TypeColors.SLOTS.contains(e.getKey())) {
                    throw Errors.badRequest("Unbekannter Typ.");
                }
                if (e.getValue() != null && !Palette.isValid(e.getValue())) {
                    throw Errors.badRequest("Unbekannte Farbe.");
                }
            }
        }
        return store.write(tx -> {
            Person p = requirePerson(tx, viewer.personId());
            if (changes != null && !changes.isEmpty()) {
                tx.peopleW();
                changes.forEach((slot, color) -> {
                    if (color == null || color.equals(TypeColors.DEFAULTS.get(slot))) {
                        p.typeColors.remove(slot);
                    } else {
                        p.typeColors.put(slot, color);
                    }
                });
            }
            return TypeColors.effective(p.typeColors);
        });
    }

    public NotificationSettings notifications(Viewer viewer) {
        return store.read(data -> NotificationSettings.of(requirePerson(data, viewer.personId()).notifications));
    }

    public NotificationSettings updateNotifications(Viewer viewer, NotificationSettings settings) {
        return store.write(tx -> {
            Person p = requirePerson(tx, viewer.personId());
            tx.peopleW();
            if (settings != null) {
                settings.applyTo(p.notifications);
            }
            return NotificationSettings.of(p.notifications);
        });
    }

    // MARK: - App-Links

    public List<AppLinkView> appLinks(Viewer viewer) {
        return store.read(data -> requirePerson(data, viewer.personId()).appTokens.stream()
                .sorted(Comparator.comparing((AppToken t) -> t.createdAt).reversed())
                .map(t -> new AppLinkView(t.id, t.label, t.createdAt, t.lastUsedAt))
                .toList());
    }

    public AppLinkCreated createAppLink(Viewer viewer, String label) {
        String name = label == null || label.isBlank() ? "App" : label.trim();
        if (name.length() > MAX_LABEL) {
            name = name.substring(0, MAX_LABEL);
        }
        String finalName = name;
        return store.write(tx -> {
            Person p = requirePerson(tx, viewer.personId());
            tx.peopleW();
            AppTokens.Issued issued = AppTokens.issue(p, finalName, now());
            return new AppLinkCreated(issued.token().id, finalName, setupUrl(issued.secret()), issued.secret());
        });
    }

    public String setupUrl(String secret) {
        return publicUrl + "/setup?token=" + secret;
    }

    public void deleteAppLink(Viewer viewer, String id) {
        store.update(tx -> {
            Person p = requirePerson(tx, viewer.personId());
            if (!p.appTokens.removeIf(t -> t.id.equals(id))) {
                throw Errors.notFound("App-Link nicht gefunden.");
            }
            tx.peopleW();
        });
    }

    /** Den Token dieser Anfrage widerrufen - so melden sich Apps (und das Web mit cohabit_token) ab. */
    public void revokeCurrent(Viewer viewer) {
        if (!viewer.via().isAppToken() || viewer.tokenId() == null) {
            throw Errors.badRequest("Diese Anmeldung gehört nicht zu coHabit und lässt sich hier nicht abmelden.");
        }
        deleteAppLink(viewer, viewer.tokenId());
    }

    // MARK: - Geraete

    public void registerDevice(Viewer viewer, String token, String platform) {
        String t = token == null ? "" : token.trim();
        if (t.isEmpty() || t.length() > 4096) {
            throw Errors.badRequest("Die Gerätekennung fehlt.");
        }
        String plat = platform == null ? "" : platform.trim().toLowerCase(Locale.ROOT);
        if (!plat.equals("ios") && !plat.equals("android")) {
            throw Errors.badRequest("Plattform muss ios oder android sein.");
        }
        store.update(tx -> {
            Person me = requirePerson(tx, viewer.personId());
            // Ein Handy, das erst mit einem Token und dann mit einem anderen eingerichtet
            // wurde, bekaeme sonst weiter die Benachrichtigungen der ersten Person.
            for (Person other : tx.people().persons) {
                if (!other.id.equals(me.id) && other.devices.removeIf(d -> d.token.equals(t))) {
                    tx.peopleW();
                }
            }
            tx.peopleW();
            me.devices.removeIf(d -> d.token.equals(t));
            Device device = new Device();
            device.token = t;
            device.platform = plat;
            device.registeredAt = now();
            me.devices.add(device);
            while (me.devices.size() > MAX_DEVICES) {
                me.devices.removeFirst();
            }
        });
    }

    public void unregisterDevice(Viewer viewer, String token) {
        store.update(tx -> {
            Person me = requirePerson(tx, viewer.personId());
            if (me.devices.removeIf(d -> d.token.equals(token))) {
                tx.peopleW();
            }
        });
    }
}
