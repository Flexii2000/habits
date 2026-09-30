package com.fherrmann.habits.cohabit.push;

import com.fherrmann.habits.cohabit.model.Cohabit;
import com.fherrmann.habits.cohabit.store.CohabitStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chat-Benachrichtigungen gebuendelt: hoechstens eine je Person und Co-Habit alle
 * zwei Minuten. Was in der Zeit dazukommt, geht danach als eine Sammelnachricht
 * raus ("3 neue Nachrichten") - bei einem lebhaften Chat vibriert das Handy nicht
 * im Sekundentakt. Im Speicher: nach einem Neustart beginnt die Zaehlung neu.
 */
@Component
public class ChatBundler {

    static final Duration WINDOW = Duration.ofMinutes(2);

    private record Key(String personId, String cohabitId) {
    }

    private static final class Slot {
        Instant lastSent;
        int pending;
    }

    private final Map<Key, Slot> slots = new ConcurrentHashMap<>();
    private final CohabitStore store;
    private final Notifier notifier;
    private final Clock clock;

    public ChatBundler(CohabitStore store, Notifier notifier, Clock clock) {
        this.store = store;
        this.notifier = notifier;
        this.clock = clock;
    }

    /** Darf jetzt eine einzelne Nachricht raus? Sonst wird sie fuer die Sammelnachricht gezaehlt. */
    public synchronized boolean admit(String personId, String cohabitId, Instant now) {
        Slot slot = slots.computeIfAbsent(new Key(personId, cohabitId), k -> new Slot());
        if (slot.lastSent == null || !now.isBefore(slot.lastSent.plus(WINDOW))) {
            slot.lastSent = now;
            slot.pending = 0;
            return true;
        }
        slot.pending++;
        return false;
    }

    /** Verschickt die Sammelnachrichten, deren Fenster abgelaufen ist. */
    public void flush(Instant now) {
        List<Map.Entry<Key, Integer>> due = new ArrayList<>();
        synchronized (this) {
            for (Map.Entry<Key, Slot> e : slots.entrySet()) {
                Slot slot = e.getValue();
                if (slot.pending > 0 && !now.isBefore(slot.lastSent.plus(WINDOW))) {
                    due.add(Map.entry(e.getKey(), slot.pending));
                    slot.pending = 0;
                    slot.lastSent = now;
                }
            }
        }
        if (due.isEmpty()) {
            return;
        }
        store.update(tx -> {
            for (Map.Entry<Key, Integer> e : due) {
                Cohabit c = tx.cohabit(e.getKey().cohabitId()).orElse(null);
                if (c == null || !c.isMember(e.getKey().personId())) {
                    continue;
                }
                int n = e.getValue();
                notifier.notify(tx, List.of(e.getKey().personId()), new PushMessage("chat", c.name,
                        n == 1 ? "1 neue Nachricht" : n + " neue Nachrichten", c.id, "cohabit://cohabit/" + c.id + "/chat"),
                        Setting.CHAT, c, null);
            }
        });
    }

    @Component
    @ConditionalOnProperty(name = "cohabit.scheduler.enabled", havingValue = "true", matchIfMissing = true)
    static class Flusher {
        private final ChatBundler bundler;

        Flusher(ChatBundler bundler) {
            this.bundler = bundler;
        }

        @Scheduled(fixedDelayString = "PT15S", initialDelayString = "PT15S")
        void flush() {
            bundler.flush(Instant.now(bundler.clock));
        }
    }
}
