package com.fherrmann.habits.cohabit.service;

import com.fherrmann.habits.cohabit.api.CheckinResult;
import com.fherrmann.habits.cohabit.api.CohabitDetail;
import com.fherrmann.habits.cohabit.model.Checkin;
import com.fherrmann.habits.cohabit.model.Cohabit;
import com.fherrmann.habits.cohabit.model.Member;
import com.fherrmann.habits.cohabit.rules.CohabitEval;
import com.fherrmann.habits.cohabit.rules.Evaluations;
import com.fherrmann.habits.cohabit.store.CohabitStore;
import com.fherrmann.habits.security.Viewer;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Der Timer bei Minuten pro Woche (seit 2026-10-28): Start und Stopp in der Liste, die
 * Startzeit liegt hier - so laeuft er nach einem Neustart der App und auf jedem Geraet
 * weiter. Der Stopp traegt die Minuten als gewoehnlichen Eintrag ein, am Tag des Starts.
 */
@Service
public class TimerService {

    /** Laenger laeuft keine Sitzung - so lang wie der groesste Eintrag. */
    static final Duration MAX_RUN = Duration.ofMinutes(CheckinService.MAX_MINUTES);

    /** {@code startedAt}: wann gestartet wurde (Postausgang der Apps); {@code null} = jetzt. */
    public record StartInput(Instant startedAt) {
    }

    /** {@code id} vergibt die App wie bei jedem Eintrag; {@code stoppedAt} {@code null} = jetzt. */
    public record StopInput(String id, Instant stoppedAt) {
    }

    public record Stopped(CheckinResult result, boolean created) {
    }

    private final CohabitStore store;
    private final ViewService views;
    private final CheckinService checkins;

    public TimerService(CohabitStore store, ViewService views, CheckinService checkins) {
        this.store = store;
        this.views = views;
        this.checkins = checkins;
    }

    /** Laeuft der Timer schon, bleibt der erste Start - zwei Geraete oder ein Nachsenden starten nicht neu. */
    public CohabitDetail start(Viewer viewer, String cohabitId, StartInput in) {
        String me = viewer.personId();
        Instant now = views.now();
        store.update(tx -> {
            Cohabit c = ViewService.visible(tx, cohabitId, me);
            Member m = writableTimer(c, me);
            if (m.timerStartedAt != null) {
                return;
            }
            Instant start = in == null || in.startedAt() == null || in.startedAt().isAfter(now) ? now : in.startedAt();
            if (start.isBefore(now.minus(MAX_RUN))) {
                throw Errors.badRequest("Der Start liegt zu weit zurück.");
            }
            if (LocalDate.ofInstant(start, CohabitEval.zoneOf(c)).isBefore(Evaluations.memberStart(c, m))) {
                throw Errors.badRequest("Außerhalb der Nachtragsfrist.");
            }
            tx.cohabitsW();
            m.timerStartedAt = start;
        });
        return views.detail(me, cohabitId);
    }

    /**
     * Haelt den Timer an und traegt die Minuten ein - gerundet, unter einer halben Minute gar
     * nicht. Laeuft er laenger als einen Tag, bleibt er stehen, wie er ist: verwerfen und von
     * Hand nachtragen, statt eine vergessene Nacht als Uebung zu zaehlen.
     */
    public Stopped stop(Viewer viewer, String cohabitId, StopInput in) {
        String me = viewer.personId();
        String id = in == null || in.id() == null || in.id().isBlank() ? UUID.randomUUID().toString() : in.id().trim();
        if (!CheckinService.CLIENT_ID.matcher(id).matches()) {
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
            Member m = writableTimer(c, me);
            if (m.timerStartedAt == null) {
                throw Errors.conflict("Der Timer läuft nicht.");
            }
            Instant start = m.timerStartedAt;
            Instant stop = in == null || in.stoppedAt() == null || in.stoppedAt().isAfter(now) ? now : in.stoppedAt();
            if (stop.isBefore(start)) {
                throw Errors.badRequest("Der Stopp liegt vor dem Start.");
            }
            long minutes = Math.round(Duration.between(start, stop).toSeconds() / 60.0);
            if (minutes > CheckinService.MAX_MINUTES) {
                throw Errors.badRequest("Der Timer lief länger als 24 Stunden.");
            }
            tx.cohabitsW();
            m.timerStartedAt = null;
            if (minutes < 1) {
                return null;
            }
            LocalDate day = LocalDate.ofInstant(start, CohabitEval.zoneOf(c));
            LocalDate first = Evaluations.memberStart(c, m);
            created[0] = true;
            return checkins.addTimed(tx, c, me, id, day.isBefore(first) ? first : day, (int) minutes, now).id;
        });
        if (checkinId == null) {
            return new Stopped(new CheckinResult(null, views.detail(me, cohabitId)), false);
        }
        return new Stopped(checkins.result(me, cohabitId, checkinId), created[0]);
    }

    /** Verwirft den laufenden Timer ohne Eintrag; steht er schon, bleibt alles, wie es ist. */
    public CohabitDetail discard(Viewer viewer, String cohabitId) {
        String me = viewer.personId();
        store.update(tx -> {
            Cohabit c = ViewService.visible(tx, cohabitId, me);
            Member m = c.member(me).orElseThrow(() -> Errors.notFound("Co-Habit nicht gefunden."));
            if (m.timerStartedAt != null) {
                tx.cohabitsW();
                m.timerStartedAt = null;
            }
        });
        return views.detail(me, cohabitId);
    }

    private static Member writableTimer(Cohabit c, String me) {
        if (!c.tracking.withTimer()) {
            throw Errors.badRequest("Dieses Co-Habit hat keinen Timer.");
        }
        if (c.archived) {
            throw Errors.conflict("Das Co-Habit ist archiviert.");
        }
        return c.member(me).orElseThrow(() -> Errors.notFound("Co-Habit nicht gefunden."));
    }
}
