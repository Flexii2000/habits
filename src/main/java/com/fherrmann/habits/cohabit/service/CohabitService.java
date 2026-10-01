package com.fherrmann.habits.cohabit.service;

import com.fherrmann.habits.cohabit.api.CohabitDetail;
import com.fherrmann.habits.cohabit.api.CohabitSummary;
import com.fherrmann.habits.cohabit.api.InvitationView;
import com.fherrmann.habits.cohabit.api.InviteCandidates;
import com.fherrmann.habits.cohabit.api.LinkView;
import com.fherrmann.habits.cohabit.api.PersonView;
import com.fherrmann.habits.cohabit.model.ChallengeState;
import com.fherrmann.habits.cohabit.model.Cohabit;
import com.fherrmann.habits.cohabit.model.CohabitType;
import com.fherrmann.habits.cohabit.model.Invitation;
import com.fherrmann.habits.cohabit.model.InviteLinkKind;
import com.fherrmann.habits.cohabit.model.HealthMetric;
import com.fherrmann.habits.cohabit.model.Member;
import com.fherrmann.habits.cohabit.model.MessagesFile;
import com.fherrmann.habits.cohabit.model.Pause;
import com.fherrmann.habits.cohabit.model.Person;
import com.fherrmann.habits.cohabit.model.Role;
import com.fherrmann.habits.cohabit.model.AutoConfig;
import com.fherrmann.habits.cohabit.push.Notifier;
import com.fherrmann.habits.cohabit.push.PushMessage;
import com.fherrmann.habits.cohabit.push.Setting;
import com.fherrmann.habits.cohabit.rules.CohabitEval;
import com.fherrmann.habits.cohabit.store.CohabitStore;
import com.fherrmann.habits.security.Viewer;
import com.fherrmann.habits.service.FocusService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Co-Habits anlegen und verwalten: Einstellungen, Mitglieder, Einladungen, Pausen. */
@Service
public class CohabitService {

    static final int MAX_PAUSE_DAYS = 366;

    private final CohabitStore store;
    private final ViewService views;
    private final EventService events;
    private final Notifier notifier;
    private final AutoSources sources;
    private final PhotoFiles photos;
    private final InviteLinks links;
    private final KcalSync kcal;
    private final FocusService focus;

    public CohabitService(CohabitStore store, ViewService views, EventService events, Notifier notifier,
                          AutoSources sources, PhotoFiles photos, InviteLinks links, KcalSync kcal,
                          FocusService focus) {
        this.store = store;
        this.views = views;
        this.events = events;
        this.notifier = notifier;
        this.sources = sources;
        this.photos = photos;
        this.links = links;
        this.kcal = kcal;
        this.focus = focus;
    }

    private Instant now() {
        return views.now();
    }

    static LocalDate today(Cohabit c, Instant now) {
        return LocalDate.ofInstant(now, CohabitEval.zoneOf(c));
    }

    static Cohabit adminOnly(CohabitStore.Data data, String id, String personId) {
        Cohabit c = ViewService.visible(data, id, personId);
        if (c.member(personId).map(m -> m.role != Role.ADMIN).orElse(true)) {
            throw Errors.forbidden("Das darf nur der Admin.");
        }
        return c;
    }

    // MARK: - Lesen

    public List<CohabitSummary> list(Viewer viewer) {
        return views.summaries(viewer.personId(), false);
    }

    public List<CohabitSummary> archived(Viewer viewer) {
        return views.summaries(viewer.personId(), true);
    }

    public CohabitDetail get(Viewer viewer, String id) {
        return views.detail(viewer.personId(), id);
    }

    // MARK: - Anlegen, aendern, archivieren, loeschen

    public CohabitDetail create(Viewer viewer, CohabitInput input) {
        String me = viewer.personId();
        Instant now = now();
        CohabitInput checked = withFocusCategory(input, null);
        String id = store.write(tx -> {
            PeopleService.requirePerson(tx, me);
            Cohabit c = new Cohabit();
            c.id = "c-" + UUID.randomUUID();
            c.createdBy = me;
            c.createdAt = now;
            ZoneId zone = zoneOrDefault(checked == null ? null : checked.timezone());
            LocalDate today = LocalDate.ofInstant(now, zone);
            CohabitConfigs.apply(c, checked, false, today, sources.sourcesOf(me));
            c.startDate = today(c, now);
            if (c.type == CohabitType.CHALLENGE) {
                c.challengeState = new ChallengeState();
                // Die erste Runde bekommt keine "neue Runde"-Meldung.
                c.challengeState.roundStartAnnounced = true;
            }
            Member admin = newMember(me, Role.ADMIN, now, c.startDate);
            c.members.add(admin);
            tx.cohabitsW().cohabits.add(c);
            tx.checkinsW(c.id);
            tx.messagesW(c.id);
            List<String> invite = checked.invitePersonIds() == null ? List.of() : checked.invitePersonIds();
            invite(tx, c, me, invite, now);
            return c.id;
        });
        return views.detail(me, id);
    }

    /**
     * Fokus-Habit mit Kategorie: die Kategorie muss im Wald zur Auswahl stehen (eine
     * inzwischen geloeschte darf ein bestehendes Co-Habit behalten), und ihr Name kommt
     * von dort - nie vom Client.
     */
    private CohabitInput withFocusCategory(CohabitInput in, String keptCategoryId) {
        if (in == null || in.auto() == null || in.auto().focusCategoryId() == null
                || in.auto().focusCategoryId().isBlank()) {
            return in;
        }
        String categoryId = in.auto().focusCategoryId().trim();
        boolean kept = categoryId.equals(keptCategoryId);
        if (!kept && !focus.isActiveCategory(categoryId)) {
            throw Errors.badRequest("Unbekannte Kategorie.");
        }
        String name = focus.categoryName(categoryId).orElseThrow(() -> Errors.badRequest("Unbekannte Kategorie."));
        AutoConfig a = in.auto();
        AutoConfig auto = new AutoConfig(a.source(), a.weeklyStepGoal(), a.focusMinutesGoal(), categoryId, name,
                a.focusPeriod());
        return new CohabitInput(in.type(), in.name(), in.color(), in.timezone(), in.tracking(), in.photoRequired(),
                in.backfillHours(), in.reminderTime(), in.membersCanInvite(), in.streak(), in.abstinence(), in.goal(),
                in.challenge(), in.health(), auto, in.invitePersonIds());
    }

    private static ZoneId zoneOrDefault(String zone) {
        try {
            return zone == null || zone.isBlank() ? ZoneId.of("Europe/Berlin") : ZoneId.of(zone.trim());
        } catch (RuntimeException e) {
            return ZoneId.of("Europe/Berlin");
        }
    }

    static Member newMember(String personId, Role role, Instant now, LocalDate startDate) {
        Member m = new Member();
        m.personId = personId;
        m.role = role;
        m.joinedAt = now;
        m.startDate = startDate;
        return m;
    }

    public CohabitDetail update(Viewer viewer, String id, CohabitInput input) {
        String me = viewer.personId();
        Instant now = now();
        String keptCategory = store.read(data -> data.cohabit(id)
                .map(c -> c.auto == null ? null : c.auto.focusCategoryId()).orElse(null));
        CohabitInput checked = withFocusCategory(input, keptCategory);
        store.update(tx -> {
            Cohabit c = adminOnly(tx, id, me);
            if (c.archived) {
                throw Errors.conflict("Das Co-Habit ist archiviert.");
            }
            tx.cohabitsW();
            CohabitConfigs.apply(c, checked, true, today(c, now), sources.sourcesOf(me));
            events.system(tx, c, Views.name(tx, me) + " hat die Einstellungen geändert", me, now);
        });
        return views.detail(me, id);
    }

    public CohabitDetail archive(Viewer viewer, String id, boolean archived) {
        String me = viewer.personId();
        Instant now = now();
        store.update(tx -> {
            Cohabit c = adminOnly(tx, id, me);
            tx.cohabitsW();
            c.archived = archived;
            c.archivedAt = archived ? now : null;
        });
        return views.detail(me, id);
    }

    public void delete(Viewer viewer, String id, boolean confirm) {
        if (!confirm) {
            throw Errors.badRequest("Bitte das Löschen bestätigen.");
        }
        store.update(tx -> deleteCohabit(tx, adminOnly(tx, id, viewer.personId())));
    }

    /** Loescht ein Co-Habit samt Eintraegen, Chat, Timeline und Fotos. */
    public void deleteCohabit(CohabitStore.Tx tx, Cohabit c) {
        tx.cohabitsW().cohabits.remove(c);
        tx.cohabitsW().invitations.removeIf(i -> i.cohabitId.equals(c.id));
        tx.cohabitsW().inviteLinks.removeIf(l -> c.id.equals(l.cohabitId));
        events.dropCohabit(tx, c.id);
        photos.delete(tx, PhotoFiles.usedIn(tx, c.id));
        tx.dropCohabitFiles(c.id);
    }

    // MARK: - Mitglieder

    /** Beitreten - ueber eine Einladung oder einen Link. Freundet mit der einladenden Person an. */
    public void join(CohabitStore.Tx tx, Cohabit c, String personId, String inviterId, Instant now) {
        if (c.isMember(personId)) {
            return;
        }
        tx.cohabitsW();
        c.members.add(newMember(personId, Role.MEMBER, now, today(c, now)));
        tx.cohabitsW().invitations.removeIf(i -> i.cohabitId.equals(c.id) && i.toId.equals(personId));
        if (inviterId != null) {
            Social.befriend(tx, personId, inviterId, now);
        }
        MessagesFile chat = tx.messagesW(c.id);
        events.system(tx, c, Views.name(tx, personId) + " ist beigetreten", personId, now);
        // Was vor dem Beitritt geschrieben wurde, gilt als gelesen.
        chat.readState.put(personId, chat.messages.getLast().id);
    }

    /**
     * Verlassen oder entfernt werden. Geht der Admin, uebernimmt das am laengsten
     * beteiligte Mitglied; bleibt niemand, verschwindet das Co-Habit.
     */
    public void leave(CohabitStore.Tx tx, Cohabit c, String personId, String actorId, Instant now) {
        Member m = c.member(personId).orElseThrow(() -> Errors.notFound("Kein Mitglied."));
        tx.cohabitsW();
        c.members.remove(m);
        tx.eventsW().nudges.removeIf(n -> n.cohabitId.equals(c.id)
                && (n.fromId.equals(personId) || n.toId.equals(personId)));
        if (c.members.isEmpty()) {
            deleteCohabit(tx, c);
            return;
        }
        tx.messagesW(c.id).readState.remove(personId);
        String name = Views.name(tx, personId);
        events.system(tx, c, personId.equals(actorId) ? name + " hat das Co-Habit verlassen" : name + " wurde entfernt",
                actorId, now);
        if (m.role == Role.ADMIN) {
            Member next = c.members.stream()
                    .min(Comparator.comparing((Member x) -> x.joinedAt == null ? Instant.EPOCH : x.joinedAt))
                    .orElseThrow();
            next.role = Role.ADMIN;
            events.system(tx, c, Views.name(tx, next.personId) + " ist jetzt Admin", actorId, now);
        }
    }

    public void removeMember(Viewer viewer, String id, String personId) {
        String me = viewer.personId();
        String target = "me".equals(personId) ? me : personId;
        Instant now = now();
        store.update(tx -> {
            Cohabit c = ViewService.visible(tx, id, me);
            if (!target.equals(me)) {
                if (c.member(me).map(m -> m.role != Role.ADMIN).orElse(true)) {
                    throw Errors.forbidden("Mitglieder entfernen darf nur der Admin.");
                }
                if (!c.isMember(target)) {
                    // Eine offene Einladung zuruecknehmen geht auf demselben Weg.
                    if (tx.cohabitsW().invitations.removeIf(i -> i.cohabitId.equals(c.id) && i.toId.equals(target))) {
                        return;
                    }
                    throw Errors.notFound("Kein Mitglied.");
                }
            }
            leave(tx, c, target, me, now);
        });
    }

    public CohabitDetail transferAdmin(Viewer viewer, String id, String personId) {
        String me = viewer.personId();
        Instant now = now();
        store.update(tx -> {
            Cohabit c = adminOnly(tx, id, me);
            Member next = c.member(personId).orElseThrow(() -> Errors.badRequest("Die Person ist kein Mitglied."));
            if (next.personId.equals(me)) {
                return;
            }
            tx.cohabitsW();
            c.member(me).orElseThrow().role = Role.MEMBER;
            next.role = Role.ADMIN;
            events.system(tx, c, Views.name(tx, personId) + " ist jetzt Admin", me, now);
        });
        return views.detail(me, id);
    }

    /** Nur die mitgeschickten Felder aendern sich; {@code checkins}/{@code chat} null heisst "wie global". */
    public CohabitDetail updateMySettings(Viewer viewer, String id, Map<String, Object> body) {
        String me = viewer.personId();
        boolean kcalConsent = store.write(tx -> {
            Cohabit c = ViewService.visible(tx, id, me);
            Member m = c.member(me).orElseThrow();
            tx.cohabitsW();
            if (body.containsKey("muted")) {
                m.settings.muted = bool(body.get("muted"));
            }
            if (body.containsKey("checkins")) {
                m.settings.checkins = nullableBool(body.get("checkins"));
            }
            if (body.containsKey("chat")) {
                m.settings.chat = nullableBool(body.get("chat"));
            }
            if (body.containsKey("shareBreaks")) {
                m.settings.shareBreaks = bool(body.get("shareBreaks"));
            }
            if (body.containsKey("healthConsent")) {
                boolean consent = bool(body.get("healthConsent"));
                boolean kcalMetric = c.health != null && c.health.metric() == HealthMetric.KCAL;
                if (consent && kcalMetric && !kcal.canProvide(me)) {
                    throw Errors.badRequest("Dafür braucht es einen Healthy-Zugang.");
                }
                boolean newlyGiven = consent && !m.settings.healthConsent;
                m.settings.healthConsent = consent;
                return newlyGiven && kcalMetric;
            }
            return false;
        });
        if (kcalConsent) {
            // Gleich holen, was die Nachtragsfrist hergibt - nicht erst beim naechsten Scheduler-Lauf.
            kcal.sync(id, me, now());
        }
        return views.detail(me, id);
    }

    private static boolean bool(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        throw Errors.badRequest("Erwartet wurde true oder false.");
    }

    private static Boolean nullableBool(Object value) {
        return value == null ? null : bool(value);
    }

    // MARK: - Pausen

    public CohabitDetail addPause(Viewer viewer, String id, LocalDate from, LocalDate to) {
        String me = viewer.personId();
        Instant now = now();
        store.update(tx -> {
            Cohabit c = ViewService.visible(tx, id, me);
            if (c.type != CohabitType.STREAK) {
                throw Errors.badRequest("Pausen gibt es nur bei Streaks.");
            }
            if (from == null || to == null || to.isBefore(from)) {
                throw Errors.badRequest("Die Pause braucht einen Anfang und ein Ende danach.");
            }
            if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_PAUSE_DAYS) {
                throw Errors.badRequest("Eine Pause dauert höchstens ein Jahr.");
            }
            Member m = c.member(me).orElseThrow();
            CohabitEval e = CohabitEval.evaluate(c, tx.checkins(c.id), Map.of(), now);
            LocalDate earliest = Views.backfillFrom(e, e.member(me));
            if (from.isBefore(earliest)) {
                throw Errors.badRequest("So weit zurück lässt sich keine Pause eintragen.");
            }
            if (to.isAfter(today(c, now).plusDays(MAX_PAUSE_DAYS))) {
                throw Errors.badRequest("So weit im Voraus lässt sich keine Pause eintragen.");
            }
            tx.cohabitsW();
            Pause p = new Pause();
            p.id = UUID.randomUUID().toString();
            p.from = from;
            p.to = to;
            m.pauses.add(p);
        });
        return views.detail(me, id);
    }

    public CohabitDetail deletePause(Viewer viewer, String id, String pauseId) {
        String me = viewer.personId();
        store.update(tx -> {
            Cohabit c = ViewService.visible(tx, id, me);
            Member m = c.member(me).orElseThrow();
            if (!m.pauses.removeIf(p -> p.id.equals(pauseId))) {
                throw Errors.notFound("Pause nicht gefunden.");
            }
            tx.cohabitsW();
        });
        return views.detail(me, id);
    }

    public void dialogSeen(Viewer viewer, String id, String dialogId) {
        String me = viewer.personId();
        store.update(tx -> {
            Cohabit c = ViewService.visible(tx, id, me);
            Member m = c.member(me).orElseThrow();
            if (dialogId != null && !m.dialogsSeen.contains(dialogId)) {
                tx.cohabitsW();
                m.dialogsSeen.add(dialogId);
            }
        });
    }

    // MARK: - Einladungen

    public InviteCandidates candidates(Viewer viewer, String id) {
        String me = viewer.personId();
        return store.read(data -> candidates(data, ViewService.visible(data, id, me), me));
    }

    static InviteCandidates candidates(CohabitStore.Data data, Cohabit c, String me) {
        List<InviteCandidates.Candidate> people = new ArrayList<>();
        List<Person> friends = Social.friendIds(data, me).stream()
                .map(fid -> data.person(fid).orElse(null))
                .filter(p -> p != null && !Social.blockedEitherWay(data, me, p.id))
                .sorted(Comparator.comparing((Person p) -> p.displayName.toLowerCase(Locale.GERMANY)))
                .toList();
        for (Person p : friends) {
            String status = c.isMember(p.id) ? "MEMBER"
                    : data.cohabits().invitations.stream().anyMatch(i -> i.cohabitId.equals(c.id) && i.toId.equals(p.id))
                    ? "INVITED" : "INVITE";
            people.add(new InviteCandidates.Candidate(PersonView.of(p), status));
        }
        return new InviteCandidates(Views.seats(data, c), Views.canInvite(c, me), people);
    }

    public InviteCandidates invite(Viewer viewer, String id, List<String> personIds) {
        String me = viewer.personId();
        Instant now = now();
        return store.write(tx -> {
            Cohabit c = ViewService.visible(tx, id, me);
            if (!Views.canInvite(c, me)) {
                throw Errors.forbidden("Einladen darf hier nur der Admin.");
            }
            invite(tx, c, me, personIds == null ? List.of() : personIds, now);
            return candidates(tx, c, me);
        });
    }

    private void invite(CohabitStore.Tx tx, Cohabit c, String me, List<String> personIds, Instant now) {
        List<String> fresh = new ArrayList<>();
        for (String pid : new LinkedHashSet<>(personIds)) {
            if (pid == null || pid.equals(me) || c.isMember(pid)) {
                continue;
            }
            if (tx.cohabits().invitations.stream().anyMatch(i -> i.cohabitId.equals(c.id) && i.toId.equals(pid))) {
                continue;
            }
            if (tx.person(pid).isEmpty() || !Social.areFriends(tx, me, pid) || Social.blockedEitherWay(tx, me, pid)) {
                throw Errors.badRequest("Einladen geht nur an Freunde.");
            }
            fresh.add(pid);
        }
        if (Views.seatsUsed(tx, c) + fresh.size() > Views.MAX_SEATS) {
            throw Errors.conflict("Alle Plätze sind belegt.");
        }
        String from = Views.name(tx, me);
        for (String pid : fresh) {
            Invitation inv = new Invitation();
            inv.id = UUID.randomUUID().toString();
            inv.cohabitId = c.id;
            inv.fromId = me;
            inv.toId = pid;
            inv.createdAt = now;
            tx.cohabitsW().invitations.add(inv);
            notifier.notify(tx, List.of(pid), new PushMessage("invite", "Einladung zu „" + c.name + "“",
                    from + " lädt dich ein", c.id, "cohabit://invitation/" + inv.id), Setting.INVITES, null, me);
        }
    }

    public LinkView inviteLink(Viewer viewer, String id) {
        String me = viewer.personId();
        Instant now = now();
        return store.write(tx -> {
            Cohabit c = ViewService.visible(tx, id, me);
            if (!Views.canInvite(c, me)) {
                throw Errors.forbidden("Einladen darf hier nur der Admin.");
            }
            return links.create(tx, InviteLinkKind.COHABIT, c.id, me, now);
        });
    }

    public List<InvitationView> myInvitations(Viewer viewer) {
        String me = viewer.personId();
        Instant now = now();
        return store.read(data -> invitationsOf(data, me, now));
    }

    public static List<InvitationView> invitationsOf(CohabitStore.Data data, String me, Instant now) {
        List<InvitationView> list = new ArrayList<>();
        for (Invitation i : data.cohabits().invitations) {
            if (!i.toId.equals(me) || Social.blockedEitherWay(data, me, i.fromId)) {
                continue;
            }
            Cohabit c = data.cohabit(i.cohabitId).orElse(null);
            if (c == null || c.archived) {
                continue;
            }
            list.add(new InvitationView(i.id, Views.person(data, i.fromId), i.createdAt,
                    Views.inviteCohabit(data, c, today(c, now))));
        }
        list.sort(Comparator.comparing(InvitationView::createdAt).reversed());
        return list;
    }

    public CohabitDetail acceptInvitation(Viewer viewer, String invitationId) {
        String me = viewer.personId();
        Instant now = now();
        String cohabitId = store.write(tx -> {
            Invitation inv = tx.cohabits().invitations.stream()
                    .filter(i -> i.id.equals(invitationId) && i.toId.equals(me)).findFirst()
                    .orElseThrow(() -> Errors.notFound("Einladung nicht gefunden."));
            Cohabit c = tx.cohabit(inv.cohabitId).orElseThrow(() -> Errors.notFound("Co-Habit nicht gefunden."));
            if (c.archived) {
                throw Errors.conflict("Das Co-Habit ist archiviert.");
            }
            join(tx, c, me, inv.fromId, now);
            return c.id;
        });
        return views.detail(me, cohabitId);
    }

    public void declineInvitation(Viewer viewer, String invitationId) {
        String me = viewer.personId();
        store.update(tx -> {
            if (!tx.cohabitsW().invitations.removeIf(i -> i.id.equals(invitationId) && i.toId.equals(me))) {
                throw Errors.notFound("Einladung nicht gefunden.");
            }
        });
    }
}
