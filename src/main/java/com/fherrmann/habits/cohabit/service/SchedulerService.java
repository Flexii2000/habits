package com.fherrmann.habits.cohabit.service;

import com.fherrmann.habits.cohabit.model.AutoSource;
import com.fherrmann.habits.cohabit.model.Cohabit;
import com.fherrmann.habits.cohabit.model.CohabitType;
import com.fherrmann.habits.cohabit.model.Event;
import com.fherrmann.habits.cohabit.model.EventKind;
import com.fherrmann.habits.cohabit.model.GoalConfig;
import com.fherrmann.habits.cohabit.model.GoalMode;
import com.fherrmann.habits.cohabit.model.GoalState;
import com.fherrmann.habits.cohabit.model.Member;
import com.fherrmann.habits.cohabit.model.Message;
import com.fherrmann.habits.cohabit.push.Notifier;
import com.fherrmann.habits.cohabit.push.PushMessage;
import com.fherrmann.habits.cohabit.push.Setting;
import com.fherrmann.habits.cohabit.rules.CohabitEval;
import com.fherrmann.habits.cohabit.rules.CohabitEval.MemberEval;
import com.fherrmann.habits.cohabit.rules.StreakModel;
import com.fherrmann.habits.cohabit.rules.Texts;
import com.fherrmann.habits.cohabit.store.CohabitStore;
import com.fherrmann.habits.cohabit.web.DevController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Jede Minute: Erinnerungen, gefaehrdete Serien, Challenge- und Ziel-Enden,
 * Meilensteine, abgelaufene Links - alles in der Zeitzone des jeweiligen Co-Habits.
 *
 * <p>Was schon verschickt ist, steht in {@code scheduler.json}: ein Neustart
 * mitten im Erinnerungsfenster schickt nichts doppelt. Die Fenster sind eine
 * halbe Stunde lang, damit ein verpasster Lauf (Neustart, Deploy) nichts verschluckt.
 */
@Service
public class SchedulerService implements DevController.Tickable {

    private static final Logger log = LoggerFactory.getLogger(SchedulerService.class);

    static final LocalTime AT_RISK = LocalTime.of(20, 0);
    static final Duration WINDOW = Duration.ofMinutes(30);
    static final Duration ENDING_NOTICE = Duration.ofHours(1);
    static final Duration KEEP_SENT = Duration.ofDays(8);
    /** Automatische Quellen nur alle 15 Minuten fragen - ausser eine Erinnerung ist faellig. */
    static final int SOURCE_EVERY_MINUTES = 15;

    private final CohabitStore store;
    private final ViewService views;
    private final ChallengeService challenges;
    private final Achievements achievements;
    private final EventService events;
    private final Notifier notifier;
    private final PhotoService photos;
    private final KcalSync kcal;
    private final Clock clock;
    private final boolean enabled;
    private volatile Instant lastCleanup = Instant.EPOCH;

    public SchedulerService(CohabitStore store, ViewService views, ChallengeService challenges,
                            Achievements achievements, EventService events, Notifier notifier, PhotoService photos,
                            KcalSync kcal, Clock clock, @Value("${cohabit.scheduler.enabled:true}") boolean enabled) {
        this.store = store;
        this.views = views;
        this.challenges = challenges;
        this.achievements = achievements;
        this.events = events;
        this.notifier = notifier;
        this.photos = photos;
        this.kcal = kcal;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT30S")
    public void scheduled() {
        if (!enabled) {
            return;
        }
        try {
            tick(Instant.now(clock));
        } catch (RuntimeException e) {
            log.error("Scheduler-Lauf fehlgeschlagen", e);
        }
    }

    @Override
    public void tick(Instant now) {
        Instant at = now.truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        // kcal aus Healthy im selben Takt wie die automatischen Quellen - vor der Rechnung,
        // damit Ziele und Challenges schon mit dem frischen Stand laufen.
        if (at.atZone(java.time.ZoneId.of("Europe/Berlin")).getMinute() % SOURCE_EVERY_MINUTES == 0) {
            try {
                kcal.syncAll(at);
            } catch (RuntimeException e) {
                log.warn("kcal-Abgleich fehlgeschlagen", e);
            }
        }
        ViewService.Facts facts = views.fetchFacts(c -> !c.archived && needsSource(c, at));
        store.update(tx -> {
            for (Cohabit c : new ArrayList<>(tx.cohabits().cohabits)) {
                if (c.archived) {
                    continue;
                }
                CohabitEval eval = CohabitEval.evaluate(c, tx.checkins(c.id), facts.of(c.id), at);
                ZonedDateTime local = at.atZone(eval.zone);
                reminders(tx, eval, local);
                atRisk(tx, eval, local);
                challenge(tx, eval, at);
                goal(tx, eval, at);
                milestones(tx, eval, facts, at);
            }
            expireLinks(tx, at);
            pruneSent(tx, at);
            pruneNudges(tx, at);
        });
        if (Duration.between(lastCleanup, at).toMinutes() >= 60 || at.isBefore(lastCleanup)) {
            lastCleanup = at;
            photos.cleanup(at);
        }
    }

    /** Ob ein automatisches Co-Habit jetzt seine Quelle braucht. */
    private boolean needsSource(Cohabit c, Instant now) {
        if (c.auto == null) {
            return false;
        }
        ZonedDateTime local = now.atZone(CohabitEval.zoneOf(c));
        if (local.getMinute() % SOURCE_EVERY_MINUTES == 0) {
            return true;
        }
        return inWindow(local, reminderTime(c)) || inWindow(local, AT_RISK);
    }

    private static LocalTime reminderTime(Cohabit c) {
        return c.reminderTime == null ? null : LocalTime.parse(c.reminderTime);
    }

    /** Ab {@code start} eine halbe Stunde - kurz vor Mitternacht nur bis Mitternacht. */
    static boolean inWindow(ZonedDateTime local, LocalTime start) {
        if (start == null) {
            return false;
        }
        LocalTime t = local.toLocalTime();
        LocalTime end = start.plus(WINDOW);
        if (end.isAfter(start)) {
            return !t.isBefore(start) && t.isBefore(end);
        }
        return !t.isBefore(start);
    }

    private static boolean once(CohabitStore.Tx tx, String key, Instant now) {
        if (tx.scheduler().sent.containsKey(key)) {
            return false;
        }
        tx.schedulerW().sent.put(key, now);
        return true;
    }

    // MARK: - Erinnerungen

    /** Zur Erinnerungszeit an alle, die heute noch offen sind. */
    private void reminders(CohabitStore.Tx tx, CohabitEval e, ZonedDateTime local) {
        Cohabit c = e.cohabit;
        if (!inWindow(local, reminderTime(c)) || c.type == CohabitType.ABSTINENCE) {
            return;
        }
        LocalDate today = local.toLocalDate();
        for (MemberEval m : e.members.values()) {
            String body = openText(e, m);
            if (body == null) {
                continue;
            }
            if (once(tx, "reminder:" + c.id + ":" + m.member.personId + ":" + today, local.toInstant())) {
                notifier.notify(tx, List.of(m.member.personId), new PushMessage("reminder", c.name, body, c.id,
                        "cohabit://cohabit/" + c.id + "/checkin"), Setting.REMINDERS, c, null);
            }
        }
    }

    /** Was einer Person heute noch fehlt - {@code null}, wenn nichts offen ist. */
    static String openText(CohabitEval e, MemberEval m) {
        Cohabit c = e.cohabit;
        if (e.today.isBefore(m.start)) {
            return null;
        }
        return switch (c.type) {
            case STREAK -> {
                if (m.unavailable != null || !"OPEN".equals(Views.streakStatus(c, m))) {
                    yield null;
                }
                String text = Views.remainingText(e, m);
                yield Character.toUpperCase(text.charAt(0)) + text.substring(1);
            }
            case CHALLENGE -> e.challengeRunning() && !m.entryToday ? "Heute noch nichts eingetragen" : null;
            case GOAL -> !e.today.isAfter(c.goal.deadline()) && !m.entryToday && c.health == null
                    ? "Heute noch nichts eingetragen" : null;
            case ABSTINENCE -> null;
        };
    }

    // MARK: - Gefaehrdete Serien

    /**
     * Um 20:00 an alle mit einer Serie ab 2, deren faelliger Tag bzw. Zeitraum heute
     * endet und noch offen ist - und nur, wenn ein Eintrag heute ihn noch retten kann.
     */
    private void atRisk(CohabitStore.Tx tx, CohabitEval e, ZonedDateTime local) {
        Cohabit c = e.cohabit;
        if (c.type != CohabitType.STREAK || !inWindow(local, AT_RISK)) {
            return;
        }
        LocalDate today = local.toLocalDate();
        for (MemberEval m : e.members.values()) {
            if (!isAtRisk(e, m)) {
                continue;
            }
            if (once(tx, "atrisk:" + c.id + ":" + m.member.personId + ":" + today, local.toInstant())) {
                int n = m.streak.current();
                String unit = StreakModel.scheme(c).unit().label(n);
                String missing = Texts.number(m.streak.currentRequired() - m.streak.currentAchieved());
                String body = c.auto != null && c.auto.source() == AutoSource.STEPS_WEEKLY
                        ? c.name + ": " + n + " " + unit + " – heute noch " + missing + " Schritte"
                        : c.auto != null && c.auto.focusWeekly()
                        ? c.name + ": " + n + " " + unit + " – diese Woche noch " + missing + " Min."
                        : c.name + ": " + n + " " + unit + " – heute noch abhaken";
                notifier.notify(tx, List.of(m.member.personId), new PushMessage("streak-at-risk",
                        "Deine Serie ist gefährdet", body, c.id, "cohabit://cohabit/" + c.id + "/checkin"),
                        Setting.STREAK_AT_RISK, c, null);
            }
        }
    }

    static boolean isAtRisk(CohabitEval e, MemberEval m) {
        if (m.unavailable != null || m.streak == null || m.streak.current() < 2) {
            return false;
        }
        if (m.streak.currentRequired() == 0 || m.streak.currentDone() || !m.streak.currentEnd().equals(e.today)) {
            return false;
        }
        Cohabit c = e.cohabit;
        if (c.auto != null) {
            // Im Kalorienziel laesst sich am Sonntagabend nichts mehr "nachholen".
            return c.auto.source() != AutoSource.FOOD_TARGET_WEEKLY;
        }
        // Einer je Tag: wer heute schon hat oder mehr als einen braucht, ist nicht mehr zu retten.
        return !m.doneToday && m.streak.currentRequired() - m.streak.currentAchieved() == 1;
    }

    // MARK: - Challenge

    private void challenge(CohabitStore.Tx tx, CohabitEval e, Instant now) {
        Cohabit c = e.cohabit;
        if (c.type != CohabitType.CHALLENGE || c.challenge == null || c.challengeState == null) {
            return;
        }
        if (c.challengeState.roundEndedAt != null) {
            return;
        }
        Instant endsAt = e.challengeEndsAt();
        if (e.challengeRunning() && !c.challengeState.roundStartAnnounced && !e.today.isBefore(c.challenge.start())) {
            challenges.announceStart(tx, c, now);
        }
        if (e.challengeRunning() && !c.challengeState.endingNotified && !now.isBefore(endsAt.minus(ENDING_NOTICE))
                && now.isBefore(endsAt)) {
            tx.cohabitsW();
            c.challengeState.endingNotified = true;
            for (CohabitEval.Place p : e.leaderboard) {
                notifier.notify(tx, List.of(p.personId()), new PushMessage("challenge-ending",
                        "„" + c.name + "“ endet in einer Stunde", "Du bist auf Platz " + p.rank() + ".", c.id,
                        "cohabit://cohabit/" + c.id), Setting.CHALLENGE_END, c, null);
            }
        }
        if (now.isAfter(endsAt)) {
            challenges.finish(tx, c, e, c.challenge.end(), now);
            // Beginnt die naechste Runde schon heute (lange verpasster Lauf), meldet finish das selbst.
        }
    }

    // MARK: - Ziel

    /** Nach der Deadline: Ergebnis festhalten, melden, Abschlussdialog. */
    private void goal(CohabitStore.Tx tx, CohabitEval e, Instant now) {
        Cohabit c = e.cohabit;
        if (c.type != CohabitType.GOAL || c.goal == null || !e.today.isAfter(c.goal.deadline())) {
            return;
        }
        if (c.goalState != null && c.goalState.finishedAt != null) {
            return;
        }
        GoalConfig g = c.goal;
        tx.cohabitsW();
        GoalState state = new GoalState();
        state.finishedAt = now;
        state.total = e.goalTotal;
        List<String> reachedBy = new ArrayList<>();
        for (MemberEval m : e.members.values()) {
            if (m.amount >= g.target()) {
                reachedBy.add(m.member.personId);
            }
        }
        String text;
        if (g.mode() == GoalMode.TEAM) {
            state.reached = e.goalTotal >= g.target();
            text = (state.reached ? "Ziel erreicht: " : "Ziel verfehlt: ") + Texts.number(e.goalTotal) + " von "
                    + Texts.number(g.target());
        } else {
            state.reached = !reachedBy.isEmpty();
            List<String> names = reachedBy.stream().map(id -> Views.name(tx, id)).toList();
            text = names.isEmpty() ? "Das Ziel ist vorbei – niemand hat es erreicht"
                    : "Das Ziel ist vorbei – geschafft: " + Texts.namesAnd(names);
        }
        c.goalState = state;
        Message system = events.system(tx, c, text, now);
        state.messageId = system.id;
        Event event = events.event(tx, c, EventKind.GOAL_FINISHED, null, now);
        event.detail = Texts.number(e.goalTotal) + " von " + Texts.number(g.target());
        for (MemberEval m : e.members.values()) {
            boolean mine = g.mode() == GoalMode.TEAM ? state.reached : reachedBy.contains(m.member.personId);
            double total = g.mode() == GoalMode.TEAM ? e.goalTotal : m.amount;
            notifier.notify(tx, List.of(m.member.personId), new PushMessage("goal-finished",
                    "„" + c.name + "“: " + (mine ? "Ziel erreicht" : "Ziel verfehlt"),
                    Texts.number(total) + " von " + Texts.number(g.target()), c.id, "cohabit://cohabit/" + c.id),
                    Setting.CHALLENGE_END, c, null);
        }
    }

    // MARK: - Meilensteine

    /** Abstinenz waechst mit der Zeit, automatische Quellen mit der Quelle - beides ohne Eintrag. */
    private void milestones(CohabitStore.Tx tx, CohabitEval e, ViewService.Facts facts, Instant now) {
        Cohabit c = e.cohabit;
        boolean auto = c.type == CohabitType.STREAK && c.auto != null && !facts.of(c.id).isEmpty();
        if (c.type != CohabitType.ABSTINENCE && !auto) {
            return;
        }
        for (Member m : new ArrayList<>(c.members)) {
            achievements.check(tx, e, m.personId, now);
        }
    }

    // MARK: - Aufraeumen

    /** Abgelaufene Einladungslinks verschwinden einen Tag nach ihrem Ablauf. */
    private static void expireLinks(CohabitStore.Tx tx, Instant now) {
        Instant cutoff = now.minus(Duration.ofDays(1));
        if (tx.cohabits().inviteLinks.stream().anyMatch(l -> l.expiresAt.isBefore(cutoff))) {
            tx.cohabitsW().inviteLinks.removeIf(l -> l.expiresAt.isBefore(cutoff));
        }
    }

    /** Stupser zeigen nur heute einen Banner - nach einem Monat sind sie Ballast. */
    private static void pruneNudges(CohabitStore.Tx tx, Instant now) {
        Instant cutoff = now.minus(Duration.ofDays(30));
        if (tx.events().nudges.stream().anyMatch(n -> n.createdAt.isBefore(cutoff))) {
            tx.eventsW().nudges.removeIf(n -> n.createdAt.isBefore(cutoff));
        }
    }

    private static void pruneSent(CohabitStore.Tx tx, Instant now) {
        Instant cutoff = now.minus(KEEP_SENT);
        Map<String, Instant> sent = tx.scheduler().sent;
        if (sent.values().stream().anyMatch(t -> t.isBefore(cutoff))) {
            tx.schedulerW().sent.values().removeIf(t -> t.isBefore(cutoff));
        }
    }
}
