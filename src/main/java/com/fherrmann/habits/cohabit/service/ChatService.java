package com.fherrmann.habits.cohabit.service;

import com.fherrmann.habits.cohabit.api.CheckinView;
import com.fherrmann.habits.cohabit.api.MessagePage;
import com.fherrmann.habits.cohabit.api.MessageView;
import com.fherrmann.habits.cohabit.model.Checkin;
import com.fherrmann.habits.cohabit.model.Cohabit;
import com.fherrmann.habits.cohabit.model.Event;
import com.fherrmann.habits.cohabit.model.Message;
import com.fherrmann.habits.cohabit.model.MessageKind;
import com.fherrmann.habits.cohabit.model.MessagesFile;
import com.fherrmann.habits.cohabit.model.Report;
import com.fherrmann.habits.cohabit.push.ChatBundler;
import com.fherrmann.habits.cohabit.push.Notifier;
import com.fherrmann.habits.cohabit.push.PushMessage;
import com.fherrmann.habits.cohabit.push.Setting;
import com.fherrmann.habits.cohabit.rules.CohabitEval;
import com.fherrmann.habits.cohabit.store.CohabitStore;
import com.fherrmann.habits.security.HealthUsers;
import com.fherrmann.habits.security.Viewer;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Der Chat je Co-Habit: lesen, schreiben, loeschen, melden, Lesestand. */
@Service
public class ChatService {

    static final int MAX_TEXT = 2000;
    static final int MAX_REASON = 500;
    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;

    public record SendInput(String id, String text, String photoId) {
    }

    public record Sent(MessageView message, boolean created) {
    }

    private final CohabitStore store;
    private final ViewService views;
    private final Notifier notifier;
    private final ChatBundler bundler;
    private final PhotoFiles photos;
    private final HealthUsers users;

    public ChatService(CohabitStore store, ViewService views, Notifier notifier, ChatBundler bundler,
                       PhotoFiles photos, HealthUsers users) {
        this.store = store;
        this.views = views;
        this.notifier = notifier;
        this.bundler = bundler;
        this.photos = photos;
        this.users = users;
    }

    // MARK: - Lesen

    public MessagePage page(Viewer viewer, String cohabitId, String before, String after, Integer limit) {
        String me = viewer.personId();
        int max = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(MAX_LIMIT, limit));
        Instant now = views.now();
        return store.read(data -> {
            Cohabit c = ViewService.visible(data, cohabitId, me);
            Set<String> blocked = data.person(me).map(p -> Set.copyOf(p.blocked)).orElse(Set.of());
            List<Message> all = data.messages(c.id).stream()
                    .filter(m -> m.authorId == null || !blocked.contains(m.authorId))
                    .toList();
            int from;
            int to;
            if (after != null) {
                int index = indexOf(all, after);
                if (index < 0) {
                    // Unbekannt (etwa ein inzwischen geloeschter Check-in-Post): der neueste Stand.
                    to = all.size();
                    from = Math.max(0, to - max);
                } else {
                    from = index + 1;
                    to = Math.min(all.size(), from + max);
                }
            } else if (before != null) {
                int index = indexOf(all, before);
                to = Math.max(0, index);
                from = Math.max(0, to - max);
            } else {
                to = all.size();
                from = Math.max(0, to - max);
            }
            boolean hasMore = after != null && indexOf(all, after) >= 0 ? to < all.size() : from > 0;
            List<MessageView> list = new ArrayList<>();
            Window window = Window.of(data, c, me, now);
            for (Message m : all.subList(from, to)) {
                list.add(view(data, c, m, me, window));
            }
            return new MessagePage(list, hasMore);
        });
    }

    private static int indexOf(List<Message> list, String id) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(id)) {
                return i;
            }
        }
        return -1;
    }

    /** Die Nachrichtenfrist einmal je Seite - nicht fuer jeden Check-in-Post neu. */
    record Window(LocalDate backfillFrom, LocalDate today) {

        static Window of(CohabitStore.Data data, Cohabit c, String me, Instant now) {
            CohabitEval eval = CohabitEval.evaluate(c, data.checkins(c.id), Map.of(), now);
            LocalDate from = eval.member(me) == null ? eval.today.plusDays(1) : Views.backfillFrom(eval, eval.member(me));
            return new Window(from, eval.today);
        }
    }

    public static MessageView view(CohabitStore.Data data, Cohabit c, Message m, String me, Instant now) {
        return view(data, c, m, me, Window.of(data, c, me, now));
    }

    static MessageView view(CohabitStore.Data data, Cohabit c, Message m, String me, Window window) {
        CheckinView checkin = null;
        String reactionTarget = "message:" + m.id;
        List<com.fherrmann.habits.cohabit.model.Reaction> reactions = m.reactions;
        if (m.kind == MessageKind.CHECKIN && m.eventId != null) {
            reactionTarget = "event:" + m.eventId;
            Event e = data.events().events.stream().filter(x -> x.id.equals(m.eventId)).findFirst().orElse(null);
            reactions = e == null ? List.of() : e.reactions;
            Checkin ch = data.checkins(c.id).stream().filter(x -> x.id.equals(m.checkinId)).findFirst().orElse(null);
            if (ch != null && !m.deleted) {
                checkin = Views.checkinView(data, c, ch, me, window.backfillFrom(), window.today());
            }
        }
        return new MessageView(m.id, m.cohabitId, m.kind.name(), Views.person(data, m.authorId),
                me.equals(m.authorId), m.createdAt, m.deleted ? null : m.text, m.deleted ? null : m.photoId, checkin,
                m.systemText, reactionTarget, m.deleted ? List.of() : ReactionService.views(reactions, me), m.deleted,
                m.deleted ? List.of() : Checkin.photos(m.photoId, m.photoIds));
    }

    // MARK: - Schreiben

    public Sent send(Viewer viewer, String cohabitId, SendInput in) {
        String me = viewer.personId();
        String id = in == null || in.id() == null || in.id().isBlank() ? UUID.randomUUID().toString() : in.id().trim();
        if (!CheckinService.CLIENT_ID.matcher(id).matches()) {
            throw Errors.badRequest("Die ID der Nachricht ist ungültig.");
        }
        String text = in == null || in.text() == null || in.text().isBlank() ? null : in.text().strip();
        String photoId = in == null || in.photoId() == null || in.photoId().isBlank() ? null : in.photoId().trim();
        Instant now = views.now();
        boolean[] created = {false};
        MessageView result = store.write(tx -> {
            Cohabit c = ViewService.visible(tx, cohabitId, me);
            Message existing = tx.messages(c.id).stream().filter(m -> m.id.equals(id)).findFirst().orElse(null);
            if (existing != null) {
                if (!me.equals(existing.authorId)) {
                    throw Errors.conflict("Diese ID ist schon vergeben.");
                }
                return view(tx, c, existing, me, now);
            }
            if (c.archived) {
                throw Errors.conflict("Das Co-Habit ist archiviert.");
            }
            if (text == null && photoId == null) {
                throw Errors.badRequest("Die Nachricht ist leer.");
            }
            if (text != null && text.length() > MAX_TEXT) {
                throw Errors.badRequest("Eine Nachricht hat höchstens 2000 Zeichen.");
            }
            if (photoId != null) {
                CheckinService.attachPhoto(tx, c, me, photoId);
            }
            Message m = new Message();
            m.id = id;
            m.cohabitId = c.id;
            m.kind = photoId == null ? MessageKind.TEXT : MessageKind.PHOTO;
            m.authorId = me;
            m.createdAt = now;
            m.text = text;
            m.photoId = photoId;
            MessagesFile chat = tx.messagesW(c.id);
            chat.messages.add(m);
            chat.readState.put(me, m.id);
            created[0] = true;
            String name = Views.name(tx, me);
            String body = text != null ? name + ": " + text : name + " hat ein Foto geschickt";
            List<String> now1 = new ArrayList<>();
            for (var member : c.members) {
                if (!member.personId.equals(me) && bundler.admit(member.personId, c.id, now)) {
                    now1.add(member.personId);
                }
            }
            notifier.notify(tx, now1, new PushMessage("chat", c.name, body, c.id, "cohabit://cohabit/" + c.id + "/chat"),
                    Setting.CHAT, c, me);
            return view(tx, c, m, me, now);
        });
        return new Sent(result, created[0]);
    }

    /** Eigene Nachricht loeschen: bleibt als "Nachricht geloescht" stehen, das Foto verschwindet. */
    public MessageView delete(Viewer viewer, String cohabitId, String messageId) {
        String me = viewer.personId();
        Instant now = views.now();
        return store.write(tx -> {
            Cohabit c = ViewService.visible(tx, cohabitId, me);
            Message m = tx.messages(c.id).stream().filter(x -> x.id.equals(messageId)).findFirst()
                    .orElseThrow(() -> Errors.notFound("Nachricht nicht gefunden."));
            if (!me.equals(m.authorId) || m.kind == MessageKind.SYSTEM) {
                throw Errors.forbidden("Nur eigene Nachrichten lassen sich löschen.");
            }
            if (m.kind == MessageKind.CHECKIN) {
                throw Errors.badRequest("Ein Beweisfoto verschwindet mit seinem Eintrag.");
            }
            if (!m.deleted) {
                tx.messagesW(c.id);
                markDeleted(tx, m);
            }
            return view(tx, c, m, me, now);
        });
    }

    void markDeleted(CohabitStore.Tx tx, Message m) {
        if (m.photoId != null) {
            photos.delete(tx, m.photoId);
        }
        m.deleted = true;
        m.text = null;
        m.photoId = null;
        m.reactions.clear();
    }

    /** Meldet eine Nachricht - mit Kopie, falls sie spaeter geloescht wird; Felix bekommt einen Push. */
    public void report(Viewer viewer, String cohabitId, String messageId, String reason) {
        String me = viewer.personId();
        String why = reason == null || reason.isBlank() ? null : reason.strip();
        if (why != null && why.length() > MAX_REASON) {
            throw Errors.badRequest("Die Begründung hat höchstens 500 Zeichen.");
        }
        Instant now = views.now();
        store.update(tx -> {
            Cohabit c = ViewService.visible(tx, cohabitId, me);
            Message m = tx.messages(c.id).stream().filter(x -> x.id.equals(messageId)).findFirst()
                    .orElseThrow(() -> Errors.notFound("Nachricht nicht gefunden."));
            if (me.equals(m.authorId)) {
                throw Errors.badRequest("Eigene Nachrichten meldest du nicht.");
            }
            Report r = new Report();
            r.id = UUID.randomUUID().toString();
            r.reporterId = me;
            r.cohabitId = c.id;
            r.messageId = m.id;
            r.authorId = m.authorId;
            r.text = m.text != null ? m.text : m.systemText;
            r.photoId = m.photoId;
            r.reason = why;
            r.createdAt = now;
            tx.reportsW().reports.add(r);
            String body = Views.name(tx, me) + " meldet eine Nachricht"
                    + (m.authorId == null ? "" : " von " + Views.name(tx, m.authorId)) + " in „" + c.name + "“"
                    + (why == null ? "" : ": " + why);
            notifier.notify(tx, List.of(users.owner()), new PushMessage("report", "Neue Meldung", body, c.id,
                    "cohabit://cohabit/" + c.id + "/chat"), Setting.ALWAYS, null, null);
        });
    }

    /** Lesestand setzen - nur vorwaerts. */
    public int read(Viewer viewer, String cohabitId, String lastMessageId) {
        String me = viewer.personId();
        return store.write(tx -> {
            Cohabit c = ViewService.visible(tx, cohabitId, me);
            List<Message> list = tx.messages(c.id);
            int index = indexOf(list, lastMessageId);
            if (index < 0) {
                throw Errors.badRequest("Unbekannte Nachricht.");
            }
            MessagesFile file = tx.messagesOf(c.id);
            String current = file.readState.get(me);
            if (current == null || indexOf(list, current) < index) {
                tx.messagesW(c.id).readState.put(me, lastMessageId);
            }
            return Views.unread(tx, c, me);
        });
    }
}
