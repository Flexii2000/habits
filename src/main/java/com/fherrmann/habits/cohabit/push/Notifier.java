package com.fherrmann.habits.cohabit.push;

import com.fherrmann.habits.cohabit.model.Cohabit;
import com.fherrmann.habits.cohabit.model.Device;
import com.fherrmann.habits.cohabit.model.MemberSettings;
import com.fherrmann.habits.cohabit.model.NotificationPrefs;
import com.fherrmann.habits.cohabit.model.Person;
import com.fherrmann.habits.cohabit.store.CohabitStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Entscheidet, wer eine Benachrichtigung bekommt, und stellt sie nach dem
 * Speichern zu.
 *
 * <p>Innerhalb der Transaktion wird nur ausgewaehlt (Schalter, Stummschaltung,
 * Blocks, nie an die ausloesende Person); zugestellt wird danach, ausserhalb des
 * Locks - ein haengender Push-Dienst haelt so niemanden auf.
 */
@Component
public class Notifier {

    private record Delivery(String personId, String token, String platform, PushMessage message) {
    }

    private final Map<String, PushTransport> transports;
    private final CohabitStore store;

    public Notifier(List<PushTransport> transports, CohabitStore store) {
        this.transports = transports.stream()
                .collect(Collectors.toMap(PushTransport::platform, Function.identity(),
                        (a, b) -> a.isConfigured() ? a : b));
        this.store = store;
    }

    /**
     * @param cohabit  das Co-Habit, um dessen Ereignis es geht (fuer Stummschaltung und
     *                 Einstellungen je Co-Habit), sonst {@code null}
     * @param actorId  wer das Ereignis ausgeloest hat - bekommt nichts, und wer ihn
     *                 blockiert hat, auch nicht
     */
    public void notify(CohabitStore.Tx tx, Collection<String> recipients, PushMessage message, Setting setting,
                       Cohabit cohabit, String actorId) {
        List<Delivery> deliveries = new ArrayList<>();
        for (String personId : new LinkedHashSet<>(recipients)) {
            if (personId == null || personId.equals(actorId)) {
                continue;
            }
            Optional<Person> person = tx.person(personId);
            if (person.isEmpty() || !wants(person.get(), setting, cohabit, actorId)) {
                continue;
            }
            for (Device device : person.get().devices) {
                deliveries.add(new Delivery(personId, device.token, device.platform, message));
            }
        }
        if (!deliveries.isEmpty()) {
            tx.afterCommit(() -> deliver(deliveries));
        }
    }

    /** An jedes Geraet einer Plattform, egal wessen - fuer das App-Update. */
    public void broadcast(CohabitStore.Tx tx, String platform, PushMessage message) {
        List<Delivery> deliveries = new ArrayList<>();
        for (Person person : tx.people().persons) {
            for (Device device : person.devices) {
                if (platform.equals(device.platform)) {
                    deliveries.add(new Delivery(person.id, device.token, device.platform, message));
                }
            }
        }
        if (!deliveries.isEmpty()) {
            tx.afterCommit(() -> deliver(deliveries));
        }
    }

    static boolean wants(Person person, Setting setting, Cohabit cohabit, String actorId) {
        if (actorId != null && person.blocked.contains(actorId)) {
            return false;
        }
        NotificationPrefs prefs = person.notifications;
        MemberSettings ms = cohabit == null ? null
                : cohabit.member(person.id).map(m -> m.settings).orElse(null);
        boolean muted = ms != null && ms.muted;
        return switch (setting) {
            case ALWAYS -> true;
            case INVITES -> prefs.invites;
            case CHECKINS, MILESTONES -> !muted && override(ms == null ? null : ms.checkins, prefs.checkins);
            case PHOTOS -> !muted && override(ms == null ? null : ms.checkins, prefs.photos);
            case CHAT -> !muted && override(ms == null ? null : ms.chat, prefs.chat);
            case NUDGES -> !muted && prefs.nudges;
            case REMINDERS -> !muted && prefs.reminders;
            case STREAK_AT_RISK -> !muted && prefs.streakAtRisk;
            case CHALLENGE_END -> !muted && prefs.challengeEnd;
        };
    }

    private static boolean override(Boolean perCohabit, boolean global) {
        return perCohabit != null ? perCohabit : global;
    }

    private void deliver(List<Delivery> deliveries) {
        List<Delivery> dead = new ArrayList<>();
        for (Delivery d : deliveries) {
            PushTransport transport = transports.get(d.platform());
            if (transport == null || !transport.isConfigured()) {
                continue;
            }
            if (!transport.send(d.token(), d.message())) {
                dead.add(d);
            }
        }
        if (!dead.isEmpty()) {
            // Eine abgelehnte Kennung ist tot - aufheben hiesse, es bei jeder Nachricht neu zu versuchen.
            store.update(tx -> {
                for (Delivery d : dead) {
                    tx.person(d.personId()).ifPresent(p -> {
                        if (p.devices.removeIf(dev -> dev.token.equals(d.token()))) {
                            tx.peopleW();
                        }
                    });
                }
            });
        }
    }

    /** Fuer Tests und Diagnose: ob ueberhaupt ein Weg eingerichtet ist. */
    public boolean anyConfigured() {
        return transports.values().stream().anyMatch(PushTransport::isConfigured);
    }
}
