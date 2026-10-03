package com.fherrmann.habits.cohabit.service;

import com.fherrmann.habits.cohabit.api.CheckinResult;
import com.fherrmann.habits.cohabit.api.CheckinView;
import com.fherrmann.habits.cohabit.api.CohabitDetail;
import com.fherrmann.habits.cohabit.model.Checkin;
import com.fherrmann.habits.cohabit.model.CheckinKind;
import com.fherrmann.habits.cohabit.model.CheckinSource;
import com.fherrmann.habits.cohabit.model.Cohabit;
import com.fherrmann.habits.cohabit.model.CohabitType;
import com.fherrmann.habits.cohabit.model.Event;
import com.fherrmann.habits.cohabit.model.EventKind;
import com.fherrmann.habits.cohabit.model.GoalCounting;
import com.fherrmann.habits.cohabit.model.HealthMetric;
import com.fherrmann.habits.cohabit.model.Member;
import com.fherrmann.habits.cohabit.model.Message;
import com.fherrmann.habits.cohabit.model.MessageKind;
import com.fherrmann.habits.cohabit.model.MessagesFile;
import com.fherrmann.habits.cohabit.model.PhotoMeta;
import com.fherrmann.habits.cohabit.model.RunScoring;
import com.fherrmann.habits.cohabit.model.Scoring;
import com.fherrmann.habits.cohabit.push.Notifier;
import com.fherrmann.habits.cohabit.push.PushMessage;
import com.fherrmann.habits.cohabit.push.Setting;
import com.fherrmann.habits.cohabit.rules.CohabitEval;
import com.fherrmann.habits.cohabit.rules.CohabitEval.MemberEval;
import com.fherrmann.habits.cohabit.rules.RunPoints;
import com.fherrmann.habits.cohabit.rules.StreakModel;
import com.fherrmann.habits.cohabit.rules.Texts;
import com.fherrmann.habits.cohabit.store.CohabitStore;
import com.fherrmann.habits.security.Viewer;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Eintraege: abhaken, Unterbrechung, Beitrag, Challenge-Punkt - und die
 * Health-Werte der Apps. Selbstauskunft, keine Verifikation.
 */
@Service
public class CheckinService {

    public static final Pattern CLIENT_ID = Pattern.compile("[A-Za-z0-9-]{8,64}");
    static final int MAX_NOTE = 500;
    static final int MAX_CAPTION = 280;
    static final double MAX_VALUE = 1e9;
    static final int MAX_RUN_MINUTES = 24 * 60;
    static final double MAX_RUN_KM = 500;

    /** {@code durationMinutes}/{@code distanceKm} nur bei Laufpunkten. */
    public record CheckinInput(String id, CheckinKind kind, LocalDate date, Double value, String note,
                               String photoId, String caption, Integer durationMinutes, Double distanceKm) {
    }

    public record Created(CheckinResult result, boolean created) {
    }

    private final CohabitStore store;
    private final ViewService views;
    private final EventService events;
    private final Achievements achievements;
    private final ChallengeService challenges;
    private final Notifier notifier;
    private final PhotoFiles photos;

    public CheckinService(CohabitStore store, ViewService views, EventService events, Achievements achievements,
                          ChallengeService challenges, Notifier notifier, PhotoFiles photos) {
        this.store = store;
        this.views = views;
        this.events = events;
        this.achievements = achievements;
        this.challenges = challenges;
        this.notifier = notifier;
        this.photos = photos;
    }

    // MARK: - Eintragen

    public Created create(Viewer viewer, String cohabitId, CheckinInput in) {
        String me = viewer.personId();
        String id = in == null || in.id() == null || in.id().isBlank() ? UUID.randomUUID().toString() : in.id().trim();
        if (!CLIENT_ID.matcher(id).matches()) {
            throw Errors.badRequest("Die ID des Eintrags ist ungültig.");
        }
        Instant now = views.now();
        boolean[] created = {false};
        String checkinId = store.write(tx -> {
            Cohabit c = ViewService.visible(tx, cohabitId, me);
            Checkin existing = tx.checkins(c.id).stream().filter(ch -> ch.id.equals(id)).findFirst().orElse(null);
            if (existing != null) {
                if (!existing.personId.equals(me)) {
                    throw Errors.conflict("Diese ID ist schon vergeben.");
                }
                return existing.id;
            }
            requireWritable(c);
            CohabitEval before = CohabitEval.evaluate(c, tx.checkins(c.id), Map.of(), now);
            MemberEval mine = before.member(me);
            CheckinKind kind = kind(c, in == null ? null : in.kind());
            LocalDate date = in == null || in.date() == null ? before.today : in.date();
            checkDate(c, before, mine, date);
            if (c.type == CohabitType.STREAK && mine.doneDays.contains(date)) {
                throw Errors.conflict(date.equals(before.today) ? "Heute schon erledigt." : "An diesem Tag schon erledigt.");
            }
            if (c.type == CohabitType.ABSTINENCE && mine.breakDays.contains(date)) {
                throw Errors.conflict("An diesem Tag ist schon eine Unterbrechung eingetragen.");
            }
            Checkin ch = new Checkin();
            ch.id = id;
            ch.cohabitId = c.id;
            ch.personId = me;
            ch.kind = kind;
            ch.date = date;
            ch.createdAt = now;
            ch.source = CheckinSource.MANUAL;
            ch.value = value(c, in == null ? null : in.value());
            if (isRunChallenge(c)) {
                run(c, ch, in == null ? null : in.durationMinutes(), in == null ? null : in.distanceKm(), true);
            }
            ch.note = text(in == null ? null : in.note(), MAX_NOTE, "Die Notiz");
            ch.caption = text(in == null ? null : in.caption(), MAX_CAPTION, "Die Caption");
            ch.photoId = in == null ? null : blankToNull(in.photoId());
            if (c.photoRequired && kind == CheckinKind.DONE && ch.photoId == null) {
                throw Errors.badRequest("Ein Beweisfoto ist Pflicht.");
            }
            if (ch.photoId != null) {
                attachPhoto(tx, c, me, ch.photoId);
            }
            tx.checkinsW(c.id).add(ch);
            created[0] = true;
            afterCheckin(tx, c, ch, now);
            return ch.id;
        });
        return new Created(result(me, cohabitId, checkinId), created[0]);
    }

    private static void requireWritable(Cohabit c) {
        if (c.archived) {
            throw Errors.conflict("Das Co-Habit ist archiviert.");
        }
        if (c.auto != null) {
            throw Errors.forbidden("Dieses Co-Habit wird automatisch erfasst.");
        }
    }

    private static CheckinKind kind(Cohabit c, CheckinKind requested) {
        if (c.type == CohabitType.ABSTINENCE) {
            if (requested == CheckinKind.DONE) {
                throw Errors.badRequest("Bei Abstinenz trägst du nur Unterbrechungen ein.");
            }
            return CheckinKind.BREAK;
        }
        if (requested == CheckinKind.BREAK) {
            throw Errors.badRequest("Unterbrechungen gibt es nur bei Abstinenz.");
        }
        return CheckinKind.DONE;
    }

    /** Nachtragsfrist, Beitritt, Zukunft, Ziel- und Rundenzeitraum. */
    static void checkDate(Cohabit c, CohabitEval e, MemberEval mine, LocalDate date) {
        if (date.isAfter(e.today)) {
            throw Errors.badRequest("In der Zukunft gibt es nichts einzutragen.");
        }
        if (c.type == CohabitType.CHALLENGE) {
            boolean ended = c.challengeState != null && c.challengeState.roundEndedAt != null;
            if (ended || date.isBefore(c.challenge.start()) || date.isAfter(c.challenge.end())) {
                throw Errors.badRequest("Die Challenge läuft gerade nicht.");
            }
        }
        if (c.type == CohabitType.GOAL && date.isAfter(c.goal.deadline())) {
            throw Errors.badRequest("Das Zieldatum ist vorbei.");
        }
        if (date.isBefore(Views.backfillFrom(e, mine))) {
            throw Errors.badRequest("Außerhalb der Nachtragsfrist.");
        }
    }

    /** Wo ein Wert noetig ist (Menge, Summe), fehlt er nicht; ohne Wert-Erfassung gibt es keinen. */
    static Double value(Cohabit c, Double value) {
        if (!c.tracking.withValue()) {
            return null;
        }
        boolean required = (c.type == CohabitType.GOAL && c.goal.counting() == GoalCounting.AMOUNT)
                || (c.type == CohabitType.CHALLENGE && c.challenge.scoring() != Scoring.MOST_ENTRIES);
        if (value == null) {
            if (required) {
                throw Errors.badRequest("Der Wert fehlt.");
            }
            return null;
        }
        if (value.isNaN() || value < 0 || value > MAX_VALUE || (required && value <= 0)) {
            throw Errors.badRequest("Der Wert ist ungültig.");
        }
        return Texts.round2(value);
    }

    static boolean isRunChallenge(Cohabit c) {
        return c.type == CohabitType.CHALLENGE && c.challenge != null && c.challenge.runPoints();
    }

    /**
     * Dauer und Distanz eines Laufs - und die Pace: was nicht schneller ist als die
     * Grenze, zaehlt nicht und wird gar nicht erst gespeichert.
     *
     * @param required beim Anlegen; beim Bearbeiten heisst {@code null} "unveraendert"
     */
    static void run(Cohabit c, Checkin ch, Integer durationMinutes, Double distanceKm, boolean required) {
        Integer minutes = durationMinutes == null && !required ? ch.durationMinutes : durationMinutes;
        Double km = distanceKm == null && !required ? ch.distanceKm : distanceKm;
        if (minutes == null) {
            throw Errors.badRequest("Die Dauer fehlt.");
        }
        if (km == null) {
            throw Errors.badRequest("Die Distanz fehlt.");
        }
        if (minutes < 1 || minutes > MAX_RUN_MINUTES) {
            throw Errors.badRequest("Die Dauer muss zwischen 1 und 1.440 Minuten liegen.");
        }
        double rounded = km.isNaN() ? 0 : Texts.round2(km);
        if (rounded < 0.01 || rounded > MAX_RUN_KM) {
            throw Errors.badRequest("Die Distanz muss zwischen 0,01 und 500 km liegen.");
        }
        RunScoring r = RunPoints.scoringOf(c);
        if (!RunPoints.fastEnough(r, minutes, rounded)) {
            throw Errors.badRequest("Ø-Pace " + RunPoints.paceText(RunPoints.paceSeconds(minutes, rounded))
                    + " – zählt nur unter " + RunPoints.paceLimitText(r.paceLimit()) + " min/km.");
        }
        ch.durationMinutes = minutes;
        ch.distanceKm = rounded;
    }

    private static String text(String value, int max, String what) {
        String t = blankToNull(value);
        if (t != null && t.length() > max) {
            throw Errors.badRequest(what + " darf höchstens " + max + " Zeichen haben.");
        }
        return t;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Ein Foto gehoert der Person, die es hochgeladen hat, und wird genau einmal verwendet. */
    static void attachPhoto(CohabitStore.Tx tx, Cohabit c, String me, String photoId) {
        PhotoMeta meta = PhotoFiles.meta(tx, photoId);
        if (meta == null || !meta.ownerId.equals(me)) {
            throw Errors.badRequest("Foto nicht gefunden.");
        }
        if (meta.isUsed()) {
            throw Errors.conflict("Das Foto wird schon verwendet.");
        }
        tx.photosW();
        meta.cohabitId = c.id;
    }

    /** Timeline, Chat-Post, Push, Bestserie, Challenge-Ende - alles, was ein neuer Eintrag ausloest. */
    private void afterCheckin(CohabitStore.Tx tx, Cohabit c, Checkin ch, Instant now) {
        CohabitEval after = CohabitEval.evaluate(c, tx.checkins(c.id), Map.of(), now);
        String me = ch.personId;
        String name = Views.name(tx, me);
        List<String> others = c.members.stream().map(m -> m.personId).filter(id -> !id.equals(me)).toList();
        if (ch.kind == CheckinKind.BREAK) {
            Member member = c.member(me).orElseThrow();
            if (member.settings.shareBreaks) {
                Event e = events.event(tx, c, EventKind.BREAK, me, now);
                e.checkinId = ch.id;
                e.checkinDate = ch.date;
                ch.eventId = e.id;
                events.system(tx, c, name + " hat eine Unterbrechung eingetragen", me, now);
            }
            achievements.check(tx, after, me, now);
            return;
        }
        Event e = events.event(tx, c, ch.photoId == null ? EventKind.CHECKIN : EventKind.PHOTO_CHECKIN, me, now);
        e.checkinId = ch.id;
        e.checkinDate = ch.date;
        e.photoId = ch.photoId;
        e.caption = ch.caption;
        e.value = ch.value;
        e.detail = detail(after, me);
        ch.eventId = e.id;
        String runTitle = TimelineTexts.runTitle(c, name, ch, tx.checkins(c.id));
        String title = runTitle != null ? runTitle : TimelineTexts.checkinTitle(c, name, ch.value);
        if (ch.photoId != null) {
            MessagesFile chat = tx.messagesW(c.id);
            Message post = new Message();
            post.id = UUID.randomUUID().toString();
            post.cohabitId = c.id;
            post.kind = MessageKind.CHECKIN;
            post.authorId = me;
            post.createdAt = now;
            post.photoId = ch.photoId;
            post.checkinId = ch.id;
            post.eventId = e.id;
            chat.messages.add(post);
            chat.readState.put(me, post.id);
            ch.messageId = post.id;
            notifier.notify(tx, others, new PushMessage("photo", title,
                    ch.caption == null ? "Neues Beweisfoto" : ch.caption, c.id, "cohabit://cohabit/" + c.id + "/chat"),
                    Setting.PHOTOS, c, me);
        } else {
            notifier.notify(tx, others, new PushMessage("checkin", title,
                    ch.note != null ? ch.note : e.detail == null ? "" : e.detail, c.id, "cohabit://cohabit/" + c.id),
                    Setting.CHECKINS, c, me);
        }
        if (c.type == CohabitType.STREAK) {
            achievements.check(tx, after, me, now);
        }
        if (c.type == CohabitType.CHALLENGE) {
            challenges.finishIfTargetReached(tx, after, now);
        }
    }

    /** Was im Moment des Eintrags feststeht: die Serie, der Stand, der Fortschritt. */
    static String detail(CohabitEval e, String personId) {
        Cohabit c = e.cohabit;
        MemberEval m = e.member(personId);
        return switch (c.type) {
            case STREAK -> {
                int n = m.streak.current();
                yield n > 0 ? "Serie " + n + " " + StreakModel.scheme(c).unit().label(n) : null;
            }
            case CHALLENGE -> {
                CohabitEval.Place p = e.placeOf(personId);
                yield p == null ? null : "jetzt " + Views.scoreText(c, p.score());
            }
            case GOAL -> "jetzt " + Views.goalPercent(Views.goalTotal(e, m), c.goal.target()) + " %";
            case ABSTINENCE -> null;
        };
    }

    private CheckinResult result(String me, String cohabitId, String checkinId) {
        ViewService.Facts facts = views.factsForCohabit(cohabitId);
        return store.read(data -> {
            CohabitDetail detail = views.detail(data, me, cohabitId, facts);
            Cohabit c = data.cohabit(cohabitId).orElseThrow();
            CohabitEval e = CohabitEval.evaluate(c, data.checkins(c.id), Map.of(), views.now());
            Checkin ch = data.checkins(cohabitId).stream().filter(x -> x.id.equals(checkinId)).findFirst().orElseThrow();
            CheckinView view = Views.checkinView(data, c, ch, me, Views.backfillFrom(e, e.member(me)), e.today);
            return new CheckinResult(view, detail);
        });
    }

    // MARK: - Bearbeiten und loeschen

    private static Checkin ownEditable(CohabitStore.Data data, Cohabit c, String me, String checkinId, Instant now) {
        Checkin ch = data.checkins(c.id).stream().filter(x -> x.id.equals(checkinId)).findFirst()
                .orElseThrow(() -> Errors.notFound("Eintrag nicht gefunden."));
        if (!ch.personId.equals(me)) {
            throw Errors.forbidden("Nur eigene Einträge lassen sich ändern.");
        }
        if (c.archived) {
            throw Errors.conflict("Das Co-Habit ist archiviert.");
        }
        if (ch.source == CheckinSource.HEALTH) {
            throw Errors.badRequest("Health-Werte kommen aus der App.");
        }
        CohabitEval e = CohabitEval.evaluate(c, data.checkins(c.id), Map.of(), now);
        if (ch.date.isBefore(Views.backfillFrom(e, e.member(me)))) {
            throw Errors.badRequest("Außerhalb der Nachtragsfrist.");
        }
        return ch;
    }

    public CheckinResult update(Viewer viewer, String cohabitId, String checkinId, CheckinInput in) {
        String me = viewer.personId();
        Instant now = views.now();
        store.update(tx -> {
            Cohabit c = ViewService.visible(tx, cohabitId, me);
            Checkin ch = ownEditable(tx, c, me, checkinId, now);
            tx.checkinsW(c.id);
            if (ch.kind == CheckinKind.DONE) {
                ch.value = value(c, in == null ? null : in.value());
                if (isRunChallenge(c)) {
                    run(c, ch, in == null ? null : in.durationMinutes(), in == null ? null : in.distanceKm(), false);
                }
            }
            ch.note = text(in == null ? null : in.note(), MAX_NOTE, "Die Notiz");
            ch.caption = text(in == null ? null : in.caption(), MAX_CAPTION, "Die Caption");
            ch.updatedAt = now;
            tx.events().events.stream().filter(e -> e.id.equals(ch.eventId)).findFirst().ifPresent(e -> {
                tx.eventsW();
                e.caption = ch.caption;
                e.value = ch.value;
            });
        });
        return result(me, cohabitId, checkinId);
    }

    public CohabitDetail delete(Viewer viewer, String cohabitId, String checkinId) {
        String me = viewer.personId();
        Instant now = views.now();
        store.update(tx -> {
            Cohabit c = ViewService.visible(tx, cohabitId, me);
            Checkin ch = ownEditable(tx, c, me, checkinId, now);
            removeCheckin(tx, c, ch);
        });
        return views.detail(me, cohabitId);
    }

    /** Eintrag samt Ereignis, Chat-Post und Foto. */
    void removeCheckin(CohabitStore.Tx tx, Cohabit c, Checkin ch) {
        tx.checkinsW(c.id).remove(ch);
        if (ch.eventId != null) {
            tx.eventsW().events.removeIf(e -> e.id.equals(ch.eventId));
        }
        if (ch.messageId != null) {
            tx.messagesW(c.id).messages.removeIf(m -> m.id.equals(ch.messageId));
        }
        photos.delete(tx, ch.photoId);
    }

    // MARK: - Health

    /** Ein Tageswert aus Apple Health / Health Connect: je Person und Tag ein Eintrag, der sich aktualisiert. */
    public CohabitDetail health(Viewer viewer, String cohabitId, LocalDate date, Double value) {
        String me = viewer.personId();
        Instant now = views.now();
        if (value == null || value.isNaN() || value < 0 || value > MAX_VALUE) {
            throw Errors.badRequest("Der Wert ist ungültig.");
        }
        store.update(tx -> {
            Cohabit c = ViewService.visible(tx, cohabitId, me);
            if (c.health == null) {
                throw Errors.badRequest("Dieses Co-Habit nutzt kein Health.");
            }
            if (c.health.metric() == HealthMetric.KCAL) {
                throw Errors.badRequest("Die kcal kommen aus Healthy.");
            }
            if (c.archived) {
                throw Errors.conflict("Das Co-Habit ist archiviert.");
            }
            Member member = c.member(me).orElseThrow();
            if (!member.settings.healthConsent) {
                throw Errors.forbidden("Ohne Einwilligung werden keine Health-Werte übernommen.");
            }
            CohabitEval before = CohabitEval.evaluate(c, tx.checkins(c.id), Map.of(), now);
            MemberEval mine = before.member(me);
            LocalDate earliest = Views.backfillFrom(before, mine);
            LocalDate yesterday = before.today.minusDays(1);
            // Heute und gestern gehen immer - auch bei einer Frist von 0 Stunden.
            if (yesterday.isBefore(earliest) && !yesterday.isBefore(mine.start)) {
                earliest = yesterday;
            }
            if (date == null || date.isAfter(before.today) || date.isBefore(earliest)) {
                throw Errors.badRequest("Außerhalb der Nachtragsfrist.");
            }
            if (c.type == CohabitType.GOAL && date.isAfter(c.goal.deadline())) {
                throw Errors.badRequest("Das Zieldatum ist vorbei.");
            }
            if (c.type == CohabitType.CHALLENGE && (date.isBefore(c.challenge.start()) || date.isAfter(c.challenge.end())
                    || (c.challengeState != null && c.challengeState.roundEndedAt != null))) {
                throw Errors.badRequest("Die Challenge läuft gerade nicht.");
            }
            tx.cohabitsW();
            member.lastHealthSyncAt = now;
            applyHealthValue(tx, c, me, date, value, now);
        });
        return views.detail(me, cohabitId);
    }

    /**
     * Der Tageswert einer Person: ein Eintrag je Tag, der sich aktualisiert; 0 nimmt ihn
     * weg. Fuer die Werte der Apps wie fuer die kcal, die der Dienst selbst holt
     * ({@link KcalSync}) - Fristen und Einwilligung prueft, wer aufruft.
     *
     * @return ob sich etwas geaendert hat
     */
    boolean applyHealthValue(CohabitStore.Tx tx, Cohabit c, String personId, LocalDate date, double value,
                             Instant now) {
        double v = Texts.round2(value);
        Checkin existing = tx.checkins(c.id).stream()
                .filter(x -> x.personId.equals(personId) && x.date.equals(date) && x.source == CheckinSource.HEALTH)
                .findFirst().orElse(null);
        if (v <= 0) {
            if (existing == null) {
                return false;
            }
            removeCheckin(tx, c, existing);
            return true;
        }
        if (existing != null) {
            if (existing.value != null && existing.value == v) {
                return false;
            }
            tx.checkinsW(c.id);
            existing.value = v;
            existing.updatedAt = now;
            tx.events().events.stream().filter(e -> e.id.equals(existing.eventId)).findFirst().ifPresent(e -> {
                tx.eventsW();
                e.value = v;
            });
        } else {
            Checkin ch = new Checkin();
            ch.id = UUID.randomUUID().toString();
            ch.cohabitId = c.id;
            ch.personId = personId;
            ch.kind = CheckinKind.DONE;
            ch.date = date;
            ch.createdAt = now;
            ch.value = v;
            ch.source = CheckinSource.HEALTH;
            tx.checkinsW(c.id).add(ch);
            Event e = events.event(tx, c, EventKind.HEALTH, personId, now);
            e.checkinId = ch.id;
            e.checkinDate = date;
            e.value = v;
            ch.eventId = e.id;
        }
        CohabitEval after = CohabitEval.evaluate(c, tx.checkins(c.id), Map.of(), now);
        if (c.type == CohabitType.STREAK) {
            achievements.check(tx, after, personId, now);
        }
        if (c.type == CohabitType.CHALLENGE) {
            challenges.finishIfTargetReached(tx, after, now);
        }
        return true;
    }

    /**
     * Welche Tage ein Tageswert betreffen darf: die Nachtragsfrist, mindestens heute
     * und gestern, nie vor dem Beitritt; bei Zielen bis zum Zieldatum, bei Challenges
     * nur in der laufenden Runde.
     */
    static boolean healthDayAllowed(Cohabit c, CohabitEval e, MemberEval mine, LocalDate date) {
        LocalDate earliest = Views.backfillFrom(e, mine);
        LocalDate yesterday = e.today.minusDays(1);
        if (yesterday.isBefore(earliest) && !yesterday.isBefore(mine.start)) {
            earliest = yesterday;
        }
        if (date == null || date.isAfter(e.today) || date.isBefore(earliest)) {
            return false;
        }
        if (c.type == CohabitType.GOAL && date.isAfter(c.goal.deadline())) {
            return false;
        }
        return c.type != CohabitType.CHALLENGE || (!date.isBefore(c.challenge.start()) && !date.isAfter(c.challenge.end())
                && (c.challengeState == null || c.challengeState.roundEndedAt == null));
    }
}
