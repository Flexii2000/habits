package com.fherrmann.habits.cohabit.service;

import com.fherrmann.habits.cohabit.model.Cohabit;
import com.fherrmann.habits.cohabit.model.Event;
import com.fherrmann.habits.cohabit.model.EventKind;
import com.fherrmann.habits.cohabit.model.Message;
import com.fherrmann.habits.cohabit.model.MessageKind;
import com.fherrmann.habits.cohabit.rules.CohabitEval;
import com.fherrmann.habits.cohabit.store.CohabitStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Systemmeldungen im Chat und Ereignisse der Timeline. */
@Service
public class EventService {

    /** Eine Systemmeldung ("Lena ist beigetreten") - zentriert und klein im Chat. */
    public Message system(CohabitStore.Tx tx, Cohabit c, String text, Instant now) {
        return system(tx, c, text, null, now);
    }

    /** @param actorId wer sie ausgeloest hat - fuer diese Person zaehlt sie nicht als ungelesen */
    public Message system(CohabitStore.Tx tx, Cohabit c, String text, String actorId, Instant now) {
        Message m = new Message();
        m.id = UUID.randomUUID().toString();
        m.cohabitId = c.id;
        m.kind = MessageKind.SYSTEM;
        m.createdAt = now;
        m.systemText = text;
        m.actorId = actorId;
        tx.messagesW(c.id).messages.add(m);
        return m;
    }

    public Event event(CohabitStore.Tx tx, Cohabit c, EventKind kind, String personId, Instant now) {
        Event e = new Event();
        e.id = UUID.randomUUID().toString();
        e.cohabitId = c.id;
        e.kind = kind;
        e.personId = personId;
        e.at = now;
        e.day = LocalDate.ofInstant(now, CohabitEval.zoneOf(c));
        tx.eventsW().events.add(e);
        return e;
    }

    /** Alles eines Co-Habits aus der Timeline entfernen (Loeschen). */
    public void dropCohabit(CohabitStore.Tx tx, String cohabitId) {
        tx.eventsW().events.removeIf(e -> e.cohabitId.equals(cohabitId));
        tx.eventsW().nudges.removeIf(n -> n.cohabitId.equals(cohabitId));
    }
}
