package com.fherrmann.habits.cohabit.rules;

import com.fherrmann.habits.cohabit.model.AbstinenceConfig;
import com.fherrmann.habits.cohabit.model.ChallengeConfig;
import com.fherrmann.habits.cohabit.model.ChallengeState;
import com.fherrmann.habits.cohabit.model.Checkin;
import com.fherrmann.habits.cohabit.model.CheckinKind;
import com.fherrmann.habits.cohabit.model.CheckinSource;
import com.fherrmann.habits.cohabit.model.Cohabit;
import com.fherrmann.habits.cohabit.model.CohabitType;
import com.fherrmann.habits.cohabit.model.GoalConfig;
import com.fherrmann.habits.cohabit.model.GoalCounting;
import com.fherrmann.habits.cohabit.model.GoalMode;
import com.fherrmann.habits.cohabit.model.Member;
import com.fherrmann.habits.cohabit.model.Pause;
import com.fherrmann.habits.cohabit.model.Recurrence;
import com.fherrmann.habits.cohabit.model.Rhythm;
import com.fherrmann.habits.cohabit.model.Role;
import com.fherrmann.habits.cohabit.model.Scoring;
import com.fherrmann.habits.cohabit.model.StreakConfig;
import com.fherrmann.habits.cohabit.model.Tracking;
import com.fherrmann.habits.cohabit.model.TrackingMode;
import com.fherrmann.habits.cohabit.model.ValueUnit;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Co-Habits und Eintraege von Hand - fuer die Tests der Rechenregeln. */
final class Fixture {

    static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    /** Mittwoch. */
    static final LocalDate TODAY = LocalDate.of(2026, 9, 30);
    static final Instant NOW = TODAY.atTime(18, 0).atZone(BERLIN).toInstant();

    final Cohabit c = new Cohabit();
    final List<Checkin> checkins = new ArrayList<>();

    private Fixture(CohabitType type, LocalDate start) {
        c.id = "c-test";
        c.type = type;
        c.name = "Test";
        c.color = "peach";
        c.timezone = "Europe/Berlin";
        c.tracking = Tracking.CHECK;
        c.backfillHours = 336;
        c.startDate = start;
        c.createdAt = start.atStartOfDay(BERLIN).toInstant();
    }

    static Fixture streak(Rhythm rhythm, LocalDate start) {
        Fixture f = new Fixture(CohabitType.STREAK, start);
        f.c.streak = new StreakConfig(rhythm, false);
        return f;
    }

    static Fixture abstinence(LocalDate start, boolean group) {
        Fixture f = new Fixture(CohabitType.ABSTINENCE, start);
        f.c.abstinence = new AbstinenceConfig(group);
        return f;
    }

    static Fixture goal(double target, LocalDate start, LocalDate deadline, GoalCounting counting, GoalMode mode) {
        Fixture f = new Fixture(CohabitType.GOAL, start);
        f.c.goal = new GoalConfig(target, start, deadline, counting, mode);
        f.c.tracking = new Tracking(TrackingMode.VALUE, ValueUnit.STEPS);
        return f;
    }

    static Fixture challenge(Scoring scoring, Double target, LocalDate start, LocalDate end) {
        Fixture f = new Fixture(CohabitType.CHALLENGE, start);
        f.c.challenge = new ChallengeConfig(start, end, scoring, target, "Verlierer kocht", Recurrence.NONE);
        f.c.challengeState = new ChallengeState();
        if (scoring != Scoring.MOST_ENTRIES) {
            f.c.tracking = new Tracking(TrackingMode.VALUE, ValueUnit.KM);
        }
        return f;
    }

    Fixture group() {
        c.streak = new StreakConfig(c.streak.rhythm(), true);
        return this;
    }

    Fixture member(String id, LocalDate start) {
        Member m = new Member();
        m.personId = id;
        m.role = c.members.isEmpty() ? Role.ADMIN : Role.MEMBER;
        m.startDate = start;
        m.joinedAt = start.atStartOfDay(BERLIN).toInstant();
        c.members.add(m);
        return this;
    }

    Fixture member(String id) {
        return member(id, c.startDate);
    }

    Fixture pause(String id, LocalDate from, LocalDate to) {
        Pause p = new Pause();
        p.id = UUID.randomUUID().toString();
        p.from = from;
        p.to = to;
        c.member(id).orElseThrow().pauses.add(p);
        return this;
    }

    Fixture done(String person, LocalDate... days) {
        for (LocalDate d : days) {
            add(person, CheckinKind.DONE, d, null, d.atTime(LocalTime.NOON).atZone(BERLIN).toInstant());
        }
        return this;
    }

    Fixture value(String person, LocalDate day, double value, Instant at) {
        add(person, CheckinKind.DONE, day, value, at);
        return this;
    }

    Fixture breaks(String person, LocalDate... days) {
        for (LocalDate d : days) {
            add(person, CheckinKind.BREAK, d, null, d.atTime(LocalTime.NOON).atZone(BERLIN).toInstant());
        }
        return this;
    }

    private void add(String person, CheckinKind kind, LocalDate day, Double value, Instant at) {
        Checkin ch = new Checkin();
        ch.id = UUID.randomUUID().toString();
        ch.cohabitId = c.id;
        ch.personId = person;
        ch.kind = kind;
        ch.date = day;
        ch.value = value;
        ch.createdAt = at;
        ch.source = CheckinSource.MANUAL;
        checkins.add(ch);
    }

    CohabitEval eval() {
        return eval(NOW);
    }

    CohabitEval eval(Instant now) {
        return CohabitEval.evaluate(c, checkins, Map.of(), now);
    }

    static Instant at(LocalDate day, int hour) {
        return day.atTime(hour, 0).atZone(BERLIN).toInstant();
    }

    static LocalDate d(int daysAgo) {
        return TODAY.minusDays(daysAgo);
    }
}
