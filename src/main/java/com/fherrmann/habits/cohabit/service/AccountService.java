package com.fherrmann.habits.cohabit.service;

import com.fherrmann.habits.cohabit.api.CohabitConfigView;
import com.fherrmann.habits.cohabit.model.Checkin;
import com.fherrmann.habits.cohabit.model.Cohabit;
import com.fherrmann.habits.cohabit.model.Event;
import com.fherrmann.habits.cohabit.model.EventKind;
import com.fherrmann.habits.cohabit.model.Member;
import com.fherrmann.habits.cohabit.model.Message;
import com.fherrmann.habits.cohabit.model.MessagesFile;
import com.fherrmann.habits.cohabit.model.Person;
import com.fherrmann.habits.cohabit.model.PhotoMeta;
import com.fherrmann.habits.cohabit.store.CohabitStore;
import com.fherrmann.habits.cohabit.store.Json;
import com.fherrmann.habits.security.Viewer;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Das eigene Konto: alles exportieren, alles loeschen. */
@Service
public class AccountService {

    private final CohabitStore store;
    private final CohabitService cohabits;
    private final ChatService chat;
    private final PhotoFiles photos;
    private final ViewService views;
    private final ObjectMapper json = Json.fileMapper();

    public AccountService(CohabitStore store, CohabitService cohabits, ChatService chat, PhotoFiles photos,
                          ViewService views) {
        this.store = store;
        this.cohabits = cohabits;
        this.chat = chat;
        this.photos = photos;
        this.views = views;
    }

    // MARK: - Export

    /** profile.json, cohabits.json, checkins.json, messages.json und photos/ - alles, was mir gehoert. */
    public byte[] export(Viewer viewer) {
        String me = viewer.personId();
        Map<String, Object> files = new LinkedHashMap<>();
        List<String> photoIds = new ArrayList<>();
        store.read(data -> {
            Person p = PeopleService.requirePerson(data, me);
            Map<String, Object> profile = new LinkedHashMap<>();
            profile.put("id", p.id);
            profile.put("displayName", p.displayName);
            profile.put("username", p.username);
            profile.put("color", p.color);
            profile.put("avatarPhotoId", p.avatarPhotoId);
            profile.put("createdAt", p.createdAt);
            profile.put("notifications", p.notifications);
            profile.put("friends", Social.friendIds(data, me).stream()
                    .map(id -> data.person(id).map(f -> Map.of("id", f.id, "displayName", f.displayName,
                            "username", f.username)).orElse(null))
                    .filter(x -> x != null).toList());
            profile.put("blocked", p.blocked);
            profile.put("appLinks", p.appTokens.stream().map(t -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("label", t.label);
                m.put("createdAt", t.createdAt);
                m.put("lastUsedAt", t.lastUsedAt);
                return m;
            }).toList());
            profile.put("devices", p.devices.stream().map(d -> Map.of("platform", d.platform,
                    "registeredAt", String.valueOf(d.registeredAt))).toList());
            files.put("profile.json", profile);

            List<Map<String, Object>> mine = new ArrayList<>();
            List<Map<String, Object>> checkins = new ArrayList<>();
            List<Map<String, Object>> messages = new ArrayList<>();
            for (Cohabit c : data.cohabits().cohabits) {
                Member m = c.member(me).orElse(null);
                if (m != null) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("id", c.id);
                    entry.put("config", CohabitConfigView.of(c));
                    entry.put("archived", c.archived);
                    entry.put("role", m.role);
                    entry.put("joinedAt", m.joinedAt);
                    entry.put("settings", m.settings);
                    entry.put("pauses", m.pauses);
                    mine.add(entry);
                }
                for (Checkin ch : data.checkins(c.id)) {
                    if (ch.personId.equals(me)) {
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("cohabit", c.name);
                        entry.put("cohabitId", c.id);
                        entry.put("kind", ch.kind);
                        entry.put("date", ch.date);
                        entry.put("createdAt", ch.createdAt);
                        entry.put("value", ch.value);
                        entry.put("note", ch.note);
                        entry.put("caption", ch.caption);
                        entry.put("photo", ch.photoId == null ? null : "photos/" + ch.photoId + ".jpg");
                        entry.put("photos", ch.photos().stream().map(id -> "photos/" + id + ".jpg").toList());
                        entry.put("source", ch.source);
                        checkins.add(entry);
                    }
                }
                for (Message msg : data.messages(c.id)) {
                    if (me.equals(msg.authorId) && !msg.deleted) {
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("cohabit", c.name);
                        entry.put("kind", msg.kind);
                        entry.put("createdAt", msg.createdAt);
                        entry.put("text", msg.text);
                        entry.put("photo", msg.photoId == null ? null : "photos/" + msg.photoId + ".jpg");
                        messages.add(entry);
                    }
                }
            }
            files.put("cohabits.json", mine);
            files.put("checkins.json", checkins);
            files.put("messages.json", messages);
            for (PhotoMeta meta : data.photos().photos) {
                if (me.equals(meta.ownerId)) {
                    photoIds.add(meta.id);
                }
            }
            return null;
        });
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, Object> e : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(json.writeValueAsBytes(e.getValue()));
                zip.closeEntry();
            }
            for (String id : photoIds) {
                Path file = photos.full(id);
                if (Files.exists(file)) {
                    zip.putNextEntry(new ZipEntry("photos/" + id + ".jpg"));
                    zip.write(Files.readAllBytes(file));
                    zip.closeEntry();
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    // MARK: - Loeschen

    /**
     * Loescht Profil, Eintraege, Fotos, Freundschaften, Geraete und App-Token.
     * Nachrichten bleiben als "Nachricht geloescht" stehen, damit Gespraeche lesbar
     * bleiben. Aus jedem Co-Habit tritt die Person aus (Admin geht an das am
     * laengsten beteiligte Mitglied, allein -> Co-Habit weg). Healthy-Token bleiben:
     * sie gehoeren nicht coHabit.
     */
    public void delete(Viewer viewer, String confirm) {
        if (confirm == null || !confirm.trim().toUpperCase(Locale.GERMANY).equals("LÖSCHEN")) {
            throw Errors.badRequest("Zum Löschen bitte „LÖSCHEN“ eingeben.");
        }
        String me = viewer.personId();
        Instant now = views.now();
        store.update(tx -> {
            PeopleService.requirePerson(tx, me);
            for (Cohabit c : new ArrayList<>(tx.cohabits().cohabits)) {
                List<Checkin> mine = tx.checkins(c.id).stream().filter(ch -> ch.personId.equals(me)).toList();
                for (Checkin ch : mine) {
                    tx.checkinsW(c.id).remove(ch);
                    if (ch.messageId != null) {
                        tx.messagesW(c.id).messages.removeIf(m -> m.id.equals(ch.messageId));
                    }
                }
                MessagesFile file = tx.messagesOf(c.id);
                for (Message m : file.messages) {
                    boolean mineMessage = me.equals(m.authorId);
                    boolean reacted = m.reactions.stream().anyMatch(r -> r.personId.equals(me));
                    if (mineMessage || reacted || file.readState.containsKey(me)) {
                        tx.messagesW(c.id);
                    }
                    if (mineMessage) {
                        chat.markDeleted(tx, m);
                        m.authorId = null;
                    }
                    m.reactions.removeIf(r -> r.personId.equals(me));
                }
                file.readState.remove(me);
                if (c.isMember(me)) {
                    cohabits.leave(tx, c, me, me, now);
                }
            }
            List<Event> events = tx.eventsW().events;
            // Eigene Ereignisse gehen, gemeinsame (Challenge-Ende) bleiben - nur ohne die Person.
            events.removeIf(e -> me.equals(e.personId) && e.kind != EventKind.CHALLENGE_ENDED);
            events.forEach(e -> {
                e.reactions.removeIf(r -> r.personId.equals(me));
                if (me.equals(e.personId)) {
                    e.personId = null;
                }
            });
            tx.eventsW().nudges.removeIf(n -> n.fromId.equals(me) || n.toId.equals(me));
            tx.eventsW().timelineSeen.remove(me);
            tx.cohabitsW().invitations.removeIf(i -> i.fromId.equals(me) || i.toId.equals(me));
            tx.cohabitsW().inviteLinks.removeIf(l -> l.createdBy.equals(me));
            tx.peopleW().friendships.removeIf(f -> f.involves(me));
            tx.peopleW().friendRequests.removeIf(r -> r.fromId.equals(me) || r.toId.equals(me));
            tx.peopleW().persons.forEach(p -> p.blocked.remove(me));
            tx.reportsW().reports.forEach(r -> {
                if (me.equals(r.reporterId)) {
                    r.reporterId = null;
                }
            });
            photos.delete(tx, tx.photos().photos.stream().filter(p -> me.equals(p.ownerId)).map(p -> p.id).toList());
            tx.peopleW().persons.removeIf(p -> p.id.equals(me));
        });
    }
}
