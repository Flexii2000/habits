package com.fherrmann.habits.cohabit.service;

import com.fherrmann.habits.cohabit.api.ReactionView;
import com.fherrmann.habits.cohabit.api.ReactionsView;
import com.fherrmann.habits.cohabit.model.Cohabit;
import com.fherrmann.habits.cohabit.model.Event;
import com.fherrmann.habits.cohabit.model.Message;
import com.fherrmann.habits.cohabit.model.Reaction;
import com.fherrmann.habits.cohabit.model.ReactionKind;
import com.fherrmann.habits.cohabit.store.CohabitStore;
import com.fherrmann.habits.security.Viewer;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reaktionen auf Nachrichten ({@code message:<id>}) und Ereignisse ({@code event:<id>}).
 * Ein Beweisfoto ist ein Ereignis: Chat-Post und Timeline zeigen dieselben Reaktionen.
 */
@Service
public class ReactionService {

    private final CohabitStore store;

    public ReactionService(CohabitStore store) {
        this.store = store;
    }

    public static List<ReactionView> views(List<Reaction> reactions, String viewerId) {
        List<ReactionView> list = new ArrayList<>();
        for (ReactionKind kind : ReactionKind.values()) {
            int count = 0;
            boolean mine = false;
            for (Reaction r : reactions) {
                if (r.reaction == kind) {
                    count++;
                    mine |= r.personId.equals(viewerId);
                }
            }
            if (count > 0) {
                list.add(new ReactionView(kind.name(), kind.label(), count, mine));
            }
        }
        return list;
    }

    public ReactionsView add(Viewer viewer, String target, String reaction) {
        return change(viewer, target, reaction, true);
    }

    public ReactionsView remove(Viewer viewer, String target, String reaction) {
        return change(viewer, target, reaction, false);
    }

    private ReactionsView change(Viewer viewer, String target, String reaction, boolean add) {
        String me = viewer.personId();
        ReactionKind kind = kind(reaction);
        return store.write(tx -> {
            List<Reaction> reactions = resolve(tx, target, me, true);
            boolean present = reactions.stream().anyMatch(r -> r.personId.equals(me) && r.reaction == kind);
            if (add && !present) {
                reactions.add(new Reaction(me, kind));
            } else if (!add && present) {
                reactions.removeIf(r -> r.personId.equals(me) && r.reaction == kind);
            }
            return new ReactionsView(views(reactions, me));
        });
    }

    static ReactionKind kind(String reaction) {
        try {
            return ReactionKind.valueOf(reaction == null ? "" : reaction.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw Errors.badRequest("Unbekannte Reaktion.");
        }
    }

    /** Die Reaktionsliste des Ziels - nur, wenn die Person Mitglied des Co-Habits ist. */
    private static List<Reaction> resolve(CohabitStore.Tx tx, String target, String me, boolean write) {
        if (target == null) {
            throw Errors.badRequest("Das Ziel der Reaktion fehlt.");
        }
        if (target.startsWith("event:")) {
            String id = target.substring("event:".length());
            Event e = tx.events().events.stream().filter(x -> x.id.equals(id)).findFirst()
                    .orElseThrow(() -> Errors.notFound("Nicht gefunden."));
            ViewService.visible(tx, e.cohabitId, me);
            if (write) {
                tx.eventsW();
            }
            return e.reactions;
        }
        if (target.startsWith("message:")) {
            String id = target.substring("message:".length());
            for (Cohabit c : tx.cohabits().cohabits) {
                if (!c.isMember(me)) {
                    continue;
                }
                for (Message m : tx.messages(c.id)) {
                    if (m.id.equals(id)) {
                        if (m.deleted) {
                            throw Errors.badRequest("Die Nachricht wurde gelöscht.");
                        }
                        if (m.eventId != null) {
                            // Check-in-Post: die Reaktionen haengen am Ereignis.
                            return resolve(tx, "event:" + m.eventId, me, write);
                        }
                        if (write) {
                            tx.messagesW(c.id);
                        }
                        return m.reactions;
                    }
                }
            }
            throw Errors.notFound("Nicht gefunden.");
        }
        throw Errors.badRequest("Das Ziel muss event:… oder message:… sein.");
    }
}
