package com.fherrmann.habits.cohabit.service;

import com.fherrmann.habits.cohabit.api.PersonView;
import com.fherrmann.habits.cohabit.api.ReactionView;
import com.fherrmann.habits.cohabit.api.ReactionsView;
import com.fherrmann.habits.cohabit.model.Cohabit;
import com.fherrmann.habits.cohabit.model.Event;
import com.fherrmann.habits.cohabit.model.Message;
import com.fherrmann.habits.cohabit.model.Reaction;
import com.fherrmann.habits.cohabit.store.CohabitStore;
import com.fherrmann.habits.security.Viewer;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reaktionen auf Nachrichten ({@code message:<id>}) und Ereignisse ({@code event:<id>}):
 * ein Emoji je Person. Ein Beweisfoto ist ein Ereignis: Chat-Post und Timeline zeigen
 * dieselben Reaktionen.
 */
@Service
public class ReactionService {

    private final CohabitStore store;

    public ReactionService(CohabitStore store) {
        this.store = store;
    }

    /**
     * Je Emoji eine Zeile: die haeufigsten zuerst, bei Gleichstand das zuerst gesetzte.
     * {@code people} in der Reihenfolge, in der sie reagiert haben.
     */
    public static List<ReactionView> views(CohabitStore.Data data, List<Reaction> reactions, String viewerId) {
        Map<String, List<Reaction>> byEmoji = new LinkedHashMap<>();
        for (Reaction r : effective(reactions)) {
            byEmoji.computeIfAbsent(r.effectiveEmoji(), e -> new ArrayList<>()).add(r);
        }
        List<ReactionView> list = new ArrayList<>();
        for (Map.Entry<String, List<Reaction>> e : byEmoji.entrySet()) {
            List<PersonView> people = e.getValue().stream().map(r -> Views.person(data, r.personId))
                    .filter(Objects::nonNull).toList();
            boolean mine = e.getValue().stream().anyMatch(r -> r.personId.equals(viewerId));
            list.add(new ReactionView(e.getKey(), e.getKey(), e.getValue().size(), mine, people));
        }
        // Stabil sortiert: bei gleicher Zahl bleibt die Reihenfolge der ersten Reaktion.
        list.sort(Comparator.comparingInt(ReactionView::count).reversed());
        return list;
    }

    /**
     * Eine Reaktion je Person, alte Arten als Emoji gelesen. Hatte jemand frueher mehrere
     * auf demselben Ziel, gilt die zuletzt gesetzte - wie heute, wo eine neue die alte ersetzt.
     */
    static List<Reaction> effective(List<Reaction> reactions) {
        Map<String, Reaction> byPerson = new LinkedHashMap<>();
        for (Reaction r : reactions) {
            if (r.personId == null || r.effectiveEmoji() == null) {
                continue;
            }
            byPerson.remove(r.personId);
            byPerson.put(r.personId, r);
        }
        return new ArrayList<>(byPerson.values());
    }

    /** Setzt die eigene Reaktion; eine andere eigene weicht ihr. */
    public ReactionsView add(Viewer viewer, String target, String reaction) {
        String me = viewer.personId();
        String emoji = emoji(reaction);
        return store.write(tx -> {
            List<Reaction> reactions = resolve(tx, target, me, true);
            migrate(reactions);
            Reaction mine = reactions.stream().filter(r -> r.personId.equals(me)).findFirst().orElse(null);
            if (mine == null || !emoji.equals(mine.emoji)) {
                reactions.remove(mine);
                reactions.add(new Reaction(me, emoji));
            }
            return new ReactionsView(views(tx, reactions, me));
        });
    }

    /**
     * Nimmt die eigene Reaktion zurueck. Mit {@code reaction} nur, wenn es noch diese ist -
     * ein spaeter Eintrag aus dem Postausgang loescht keine neuere.
     */
    public ReactionsView remove(Viewer viewer, String target, String reaction) {
        String me = viewer.personId();
        String emoji = reaction == null || reaction.isBlank() ? null : emoji(reaction);
        return store.write(tx -> {
            List<Reaction> reactions = resolve(tx, target, me, true);
            migrate(reactions);
            reactions.removeIf(r -> r.personId.equals(me) && (emoji == null || emoji.equals(r.emoji)));
            return new ReactionsView(views(tx, reactions, me));
        });
    }

    /** Schreibt die Liste in der heutigen Form: ein Emoji je Person, keine alten Arten mehr. */
    private static void migrate(List<Reaction> reactions) {
        List<Reaction> current = effective(reactions);
        for (Reaction r : current) {
            r.emoji = r.effectiveEmoji();
            r.reaction = null;
        }
        reactions.clear();
        reactions.addAll(current);
    }

    static String emoji(String reaction) {
        String emoji = Emojis.normalize(reaction);
        if (emoji == null) {
            throw Errors.badRequest("Das ist kein Emoji.");
        }
        return emoji;
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
