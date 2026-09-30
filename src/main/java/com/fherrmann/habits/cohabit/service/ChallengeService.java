package com.fherrmann.habits.cohabit.service;

import com.fherrmann.habits.cohabit.model.ChallengeConfig;
import com.fherrmann.habits.cohabit.model.ChallengeRound;
import com.fherrmann.habits.cohabit.model.ChallengeState;
import com.fherrmann.habits.cohabit.model.Cohabit;
import com.fherrmann.habits.cohabit.model.Event;
import com.fherrmann.habits.cohabit.model.EventKind;
import com.fherrmann.habits.cohabit.model.Message;
import com.fherrmann.habits.cohabit.model.Recurrence;
import com.fherrmann.habits.cohabit.model.Standing;
import com.fherrmann.habits.cohabit.push.Notifier;
import com.fherrmann.habits.cohabit.push.PushMessage;
import com.fherrmann.habits.cohabit.push.Setting;
import com.fherrmann.habits.cohabit.rules.CohabitEval;
import com.fherrmann.habits.cohabit.rules.CohabitEval.Place;
import com.fherrmann.habits.cohabit.store.CohabitStore;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

/**
 * Das Ende einer Challenge-Runde: Rangliste festhalten, Gewinner und Einsatz
 * verkuenden, bei wiederkehrenden Challenges die naechste Runde vorbereiten.
 */
@Service
public class ChallengeService {

    private final EventService events;
    private final Notifier notifier;

    public ChallengeService(EventService events, Notifier notifier) {
        this.events = events;
        this.notifier = notifier;
    }

    /** Bei FIRST_TO_TARGET endet die Runde, sobald jemand das Ziel erreicht - direkt nach dem Eintrag. */
    public void finishIfTargetReached(CohabitStore.Tx tx, CohabitEval eval, Instant now) {
        Cohabit c = eval.cohabit;
        if (c.challenge == null || c.challenge.target() == null || !eval.challengeRunning()) {
            return;
        }
        boolean reached = eval.leaderboard.stream().anyMatch(p -> p.reachedAt() != null);
        if (reached) {
            finish(tx, c, eval, eval.today, now);
        }
    }

    /**
     * Wertet die laufende Runde aus.
     *
     * @param endedOn der letzte Tag der Runde (bei FIRST_TO_TARGET der Tag, an dem das Ziel fiel)
     */
    public ChallengeRound finish(CohabitStore.Tx tx, Cohabit c, CohabitEval eval, LocalDate endedOn, Instant now) {
        tx.cohabitsW();
        if (c.challengeState == null) {
            c.challengeState = new ChallengeState();
        }
        ChallengeState state = c.challengeState;
        ChallengeConfig ch = c.challenge;
        List<Place> places = CohabitEval.rank(c, c.members.stream().map(m -> m.personId).toList(), eval.checkins,
                ch.start(), ch.end(), now);
        ChallengeRound round = new ChallengeRound();
        round.number = state.currentRound;
        round.start = ch.start();
        round.end = endedOn.isBefore(ch.end()) ? endedOn : ch.end();
        round.endedAt = now;
        round.label = Views.periodLabel(ch.start(), ch.end(), eval.today);
        round.stake = ch.stake();
        double top = places.isEmpty() ? 0 : places.getFirst().score();
        int lastRank = places.stream().mapToInt(Place::rank).max().orElse(1);
        for (Place p : places) {
            Standing s = new Standing();
            s.personId = p.personId();
            s.score = p.score();
            s.rank = p.rank();
            s.reachedAt = p.reachedAt();
            round.standings.add(s);
            if (p.rank() == 1 && top > 0) {
                round.winners.add(p.personId());
            }
            if (lastRank > 1 && p.rank() == lastRank) {
                round.losers.add(p.personId());
            }
        }
        if (ch.recurrence() != Recurrence.NONE) {
            LocalDate[] next = nextRound(ch, round.end);
            round.nextStart = next[0];
        }
        Message system = events.system(tx, c, Views.challengeTitle(tx, c, round), now);
        round.messageId = system.id;
        Event e = events.event(tx, c, EventKind.CHALLENGE_ENDED, round.winners.isEmpty() ? null
                : round.winners.getFirst(), now);
        e.detail = round.label;
        state.rounds.add(round);
        state.roundEndedAt = now;
        for (Place p : places) {
            String body = "Du bist auf Platz " + p.rank() + ".";
            if (round.losers.contains(p.personId()) && round.stake != null) {
                body += " " + round.stake + ": du bist dran.";
            }
            notifier.notify(tx, List.of(p.personId()), new PushMessage("challenge-ended",
                    Views.challengeTitle(tx, c, round), body, c.id, "cohabit://cohabit/" + c.id),
                    Setting.CHALLENGE_END, c, null);
        }
        if (round.nextStart != null) {
            LocalDate[] next = nextRound(ch, round.end);
            c.challenge = new ChallengeConfig(next[0], next[1], ch.scoring(), ch.target(), ch.stake(), ch.recurrence());
            state.currentRound++;
            state.roundEndedAt = null;
            state.endingNotified = false;
            state.roundStartAnnounced = false;
            if (!next[0].isAfter(eval.today)) {
                announceStart(tx, c, now);
            }
        }
        return round;
    }

    /** "Runde 4 hat begonnen" - wenn der erste Tag der neuen Runde da ist. */
    public void announceStart(CohabitStore.Tx tx, Cohabit c, Instant now) {
        tx.cohabitsW();
        c.challengeState.roundStartAnnounced = true;
        events.system(tx, c, "Runde " + c.challengeState.currentRound + " hat begonnen ("
                + Views.periodLabel(c.challenge.start(), c.challenge.end(), c.challenge.start()) + ")", now);
    }

    /**
     * Die naechste Runde: WEEKLY ab dem naechsten Montag, MONTHLY ab dem naechsten
     * Monatsersten, jeweils gleich lang. War die Runde genau ein Kalendermonat,
     * wird es die neue auch (der Oktober hat einen Tag mehr als der September).
     */
    public static LocalDate[] nextRound(ChallengeConfig ch, LocalDate endedOn) {
        long length = ChronoUnit.DAYS.between(ch.start(), ch.end()) + 1;
        if (ch.recurrence() == Recurrence.WEEKLY) {
            LocalDate start = endedOn.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
            return new LocalDate[]{start, start.plusDays(length - 1)};
        }
        LocalDate start = endedOn.with(TemporalAdjusters.firstDayOfNextMonth());
        boolean fullMonth = ch.start().getDayOfMonth() == 1
                && ch.end().equals(ch.start().with(TemporalAdjusters.lastDayOfMonth()));
        LocalDate end = fullMonth ? start.with(TemporalAdjusters.lastDayOfMonth()) : start.plusDays(length - 1);
        return new LocalDate[]{start, end};
    }
}
