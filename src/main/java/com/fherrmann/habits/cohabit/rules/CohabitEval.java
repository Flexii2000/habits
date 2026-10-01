package com.fherrmann.habits.cohabit.rules;

import com.fherrmann.habits.cohabit.model.AutoSource;
import com.fherrmann.habits.cohabit.model.ChallengeConfig;
import com.fherrmann.habits.cohabit.model.Checkin;
import com.fherrmann.habits.cohabit.model.CheckinKind;
import com.fherrmann.habits.cohabit.model.Cohabit;
import com.fherrmann.habits.cohabit.model.CohabitType;
import com.fherrmann.habits.cohabit.model.GoalConfig;
import com.fherrmann.habits.cohabit.model.GoalCounting;
import com.fherrmann.habits.cohabit.model.Member;
import com.fherrmann.habits.cohabit.model.Scoring;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Der gerechnete Stand eines Co-Habits zu einem Zeitpunkt - fuer alle Mitglieder,
 * ohne Ein-/Ausgabe. Die Sichten (Heute, Detail, Widget, Statistik) und der
 * Scheduler lesen daraus.
 */
public final class CohabitEval {

    public final Cohabit cohabit;
    public final ZoneId zone;
    public final LocalDate today;
    public final Instant now;
    public final Map<String, MemberEval> members = new LinkedHashMap<>();
    public final List<Checkin> checkins;

    /** STREAK mit Gruppen-Streak. */
    public Integer groupStreak;
    /** ABSTINENCE im Gruppenmodus. */
    public Integer groupDays;
    /** GOAL: Summe aller Beitraege (TEAM). */
    public double goalTotal;
    /** CHALLENGE: Stand der aktuellen Runde. */
    public List<Place> leaderboard = List.of();
    public ChallengePhase phase;

    public enum ChallengePhase {
        UPCOMING, RUNNING, FINISHED, BETWEEN_ROUNDS
    }

    /** Ein Platz in der Rangliste; {@code reachedAt} nur bei FIRST_TO_TARGET, wenn das Ziel erreicht ist. */
    public record Place(String personId, double score, Instant reachedAt, int rank) {
    }

    /** Was die Rechnung ueber ein Mitglied weiss. */
    public static final class MemberEval {
        public Member member;
        public LocalDate start;
        public AutoFacts facts;
        public String unavailable;
        public Judge judge;
        public StreakCalc.Outcome streak;
        public int longest;
        public StreakCalc.Rate rate;
        public boolean doneToday;
        public boolean dueToday;
        public boolean pausedToday;
        public AbstinenceCalc.Outcome abstinence;
        public double amount;
        public int entries;
        public boolean entryToday;
        public Set<LocalDate> doneDays = new HashSet<>();
        public Set<LocalDate> breakDays = new HashSet<>();
    }

    private CohabitEval(Cohabit cohabit, List<Checkin> checkins, Instant now) {
        this.cohabit = cohabit;
        this.zone = zoneOf(cohabit);
        this.now = now;
        this.today = LocalDate.ofInstant(now, zone);
        this.checkins = checkins;
    }

    public static ZoneId zoneOf(Cohabit c) {
        try {
            return ZoneId.of(c.timezone);
        } catch (RuntimeException e) {
            return ZoneId.of("Europe/Berlin");
        }
    }

    public MemberEval member(String personId) {
        return members.get(personId);
    }

    /**
     * @param facts je Person, was die automatische Quelle hergab (nur bei automatischen
     *              Co-Habits; fehlt eine Person, gilt die Quelle als nicht erreichbar)
     */
    public static CohabitEval evaluate(Cohabit c, List<Checkin> checkins, Map<String, AutoFacts> facts, Instant now) {
        CohabitEval eval = new CohabitEval(c, checkins, now);
        for (Member m : c.members) {
            MemberEval me = new MemberEval();
            me.member = m;
            me.start = Evaluations.memberStart(c, m);
            me.doneDays = Evaluations.doneDays(checkins, m.personId);
            me.breakDays = Evaluations.breakDays(checkins, m.personId);
            eval.members.put(m.personId, me);
        }
        switch (c.type) {
            case STREAK -> eval.evaluateStreak(facts == null ? Map.of() : facts);
            case ABSTINENCE -> eval.evaluateAbstinence();
            case GOAL -> eval.evaluateGoal();
            case CHALLENGE -> eval.evaluateChallenge();
        }
        return eval;
    }

    // MARK: - STREAK

    private void evaluateStreak(Map<String, AutoFacts> facts) {
        PeriodScheme scheme = StreakModel.scheme(cohabit);
        for (MemberEval me : members.values()) {
            if (cohabit.auto != null) {
                me.facts = facts.get(me.member.personId);
                if (me.facts == null) {
                    me.facts = AutoFacts.unavailable(cohabit.auto.source(), "Quelle nicht abgefragt");
                }
                if (me.facts.isUnavailable()) {
                    me.unavailable = me.facts.unavailable();
                }
            }
            Predicate<LocalDate> paused = StreakModel.paused(me.member.pauses);
            me.judge = StreakModel.judge(cohabit, me.doneDays, me.member.pauses, me.facts);
            me.pausedToday = paused.test(today);
            me.dueToday = !me.pausedToday && StreakModel.dueByRhythm(cohabit, today);
            if (me.unavailable != null) {
                me.streak = new StreakCalc.Outcome(0, false, scheme.startOf(today), scheme.endOf(scheme.startOf(today)),
                        0, 0, false, null);
                me.rate = new StreakCalc.Rate(0, 0);
                me.longest = me.member.bestStreak;
                continue;
            }
            me.streak = StreakCalc.evaluate(scheme, me.judge, today, cohabit.auto == null ? me.start : null,
                    StreakModel.maxStreak(cohabit));
            me.longest = Math.max(StreakCalc.longest(scheme, me.judge, me.start, today), me.streak.current());
            me.rate = StreakCalc.rate(scheme, me.judge, me.start, today, null, null);
            if (cohabit.auto == null) {
                me.doneToday = me.doneDays.contains(today);
            } else if (StreakModel.weeklyAuto(cohabit.auto)) {
                me.doneToday = me.streak.currentDone();
            } else {
                me.doneToday = me.facts.done(today);
            }
        }
        if (cohabit.streak != null && cohabit.streak.groupStreak()) {
            groupStreak = groupStreak(scheme);
        }
    }

    /** Zeitraeume, in denen alle erfuellt haben, die in dem Zeitraum schon dabei und faellig waren. */
    private int groupStreak(PeriodScheme scheme) {
        List<MemberEval> list = new ArrayList<>(members.values());
        if (list.isEmpty() || list.stream().anyMatch(me -> me.unavailable != null)) {
            return 0;
        }
        Judge group = new Judge() {
            public int required(LocalDate start, LocalDate end) {
                for (MemberEval me : list) {
                    if (!end.isBefore(me.start) && me.judge.required(start, end) > 0) {
                        return 1;
                    }
                }
                return 0;
            }

            public int achieved(LocalDate start, LocalDate end) {
                for (MemberEval me : list) {
                    if (end.isBefore(me.start)) {
                        continue;
                    }
                    int r = me.judge.required(start, end);
                    if (r > 0 && me.judge.achieved(start, end) < r) {
                        return 0;
                    }
                }
                return 1;
            }
        };
        LocalDate lower = cohabit.auto == null ? cohabit.startDate : null;
        return StreakCalc.evaluate(scheme, group, today, lower, StreakModel.maxStreak(cohabit)).current();
    }

    // MARK: - ABSTINENCE

    private void evaluateAbstinence() {
        List<LocalDate> allBreaks = new ArrayList<>();
        for (MemberEval me : members.values()) {
            me.abstinence = AbstinenceCalc.evaluate(me.start, me.breakDays, today);
            me.entryToday = me.breakDays.contains(today);
            for (LocalDate b : me.breakDays) {
                if (!b.isBefore(me.start)) {
                    allBreaks.add(b);
                }
            }
        }
        if (cohabit.abstinence != null && cohabit.abstinence.groupMode()) {
            groupDays = AbstinenceCalc.groupDays(cohabit.startDate, allBreaks, today);
        }
    }

    // MARK: - GOAL

    private void evaluateGoal() {
        GoalConfig g = cohabit.goal;
        for (MemberEval me : members.values()) {
            for (Checkin ch : checkins) {
                if (ch.kind != CheckinKind.DONE || !ch.personId.equals(me.member.personId)) {
                    continue;
                }
                if (g != null && (ch.date.isBefore(g.start()) || ch.date.isAfter(g.deadline()))) {
                    continue;
                }
                me.entries++;
                me.amount += contribution(ch);
                if (ch.date.equals(today)) {
                    me.entryToday = true;
                }
            }
            me.amount = Texts.round2(me.amount);
            goalTotal += me.amount;
        }
        goalTotal = Texts.round2(goalTotal);
    }

    public double contribution(Checkin ch) {
        if (cohabit.goal != null && cohabit.goal.counting() == GoalCounting.ENTRIES) {
            return 1;
        }
        return ch.value == null ? 0 : ch.value;
    }

    /** Verstrichene Tage einschliesslich heute / Gesamttage. */
    public double goalElapsedShare() {
        GoalConfig g = cohabit.goal;
        long total = ChronoUnit.DAYS.between(g.start(), g.deadline()) + 1;
        long elapsed = Math.max(0, Math.min(total, ChronoUnit.DAYS.between(g.start(), today) + 1));
        return total <= 0 ? 1 : (double) elapsed / total;
    }

    // MARK: - CHALLENGE

    private void evaluateChallenge() {
        ChallengeConfig ch = cohabit.challenge;
        if (ch == null) {
            phase = ChallengePhase.FINISHED;
            return;
        }
        boolean ended = cohabit.challengeState != null && cohabit.challengeState.roundEndedAt != null;
        if (ended) {
            phase = ChallengePhase.FINISHED;
        } else if (today.isBefore(ch.start())) {
            phase = cohabit.challengeState != null && !cohabit.challengeState.rounds.isEmpty()
                    ? ChallengePhase.BETWEEN_ROUNDS : ChallengePhase.UPCOMING;
        } else if (today.isAfter(ch.end())) {
            // Vorbei, aber noch nicht ausgewertet - der Scheduler holt das gleich nach.
            phase = ChallengePhase.FINISHED;
        } else {
            phase = ChallengePhase.RUNNING;
        }
        Instant cutoff = ended ? cohabit.challengeState.roundEndedAt : null;
        leaderboard = rank(cohabit, new ArrayList<>(members.keySet()), checkins, ch.start(), ch.end(), cutoff);
        for (MemberEval me : members.values()) {
            for (Checkin c : checkins) {
                if (c.kind == CheckinKind.DONE && c.personId.equals(me.member.personId)
                        && !c.date.isBefore(ch.start()) && !c.date.isAfter(ch.end())) {
                    me.entries++;
                    me.amount += c.value == null ? 1 : c.value;
                    if (c.date.equals(today)) {
                        me.entryToday = true;
                    }
                }
            }
            me.amount = Texts.round2(me.amount);
        }
    }

    public boolean challengeRunning() {
        return phase == ChallengePhase.RUNNING;
    }

    /**
     * Die Rangliste einer Runde. Gleichstand -> gleicher Rang (1, 1, 3). Bei
     * FIRST_TO_TARGET liegt vorn, wer das Ziel zuerst erreicht hat (Zeitpunkt des
     * Eintrags), dahinter der Rest nach Summe.
     *
     * @param cutoff nur Eintraege bis zu diesem Zeitpunkt (eine schon ausgewertete Runde)
     */
    public static List<Place> rank(Cohabit c, List<String> personIds, List<Checkin> checkins, LocalDate start,
                                   LocalDate end, Instant cutoff) {
        ChallengeConfig ch = c.challenge;
        Scoring scoring = ch == null ? Scoring.MOST_ENTRIES : ch.scoring();
        Map<String, Double> scores = new HashMap<>();
        Map<String, Instant> reached = new HashMap<>();
        for (String id : personIds) {
            scores.put(id, 0.0);
        }
        List<Checkin> relevant = checkins.stream()
                .filter(x -> x.kind == CheckinKind.DONE && scores.containsKey(x.personId))
                .filter(x -> !x.date.isBefore(start) && !x.date.isAfter(end))
                .filter(x -> cutoff == null || !x.createdAt.isAfter(cutoff))
                .sorted(Comparator.comparing((Checkin x) -> x.createdAt).thenComparing(x -> x.id))
                .toList();
        for (Checkin x : relevant) {
            double add = scoring == Scoring.MOST_ENTRIES ? 1 : (x.value == null ? 1 : x.value);
            double score = Texts.round2(scores.get(x.personId) + add);
            scores.put(x.personId, score);
            if (scoring == Scoring.FIRST_TO_TARGET && ch.target() != null && score >= ch.target()
                    && !reached.containsKey(x.personId)) {
                reached.put(x.personId, x.createdAt);
            }
        }
        Comparator<String> order;
        if (scoring == Scoring.FIRST_TO_TARGET) {
            order = Comparator.comparing((String id) -> reached.containsKey(id) ? 0 : 1)
                    .thenComparing(id -> reached.getOrDefault(id, Instant.MAX))
                    .thenComparing(id -> -scores.get(id));
        } else {
            order = Comparator.comparing((String id) -> -scores.get(id));
        }
        List<String> sorted = new ArrayList<>(personIds);
        sorted.sort(order.thenComparing(id -> id));
        List<Place> places = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            String id = sorted.get(i);
            int rank = i + 1;
            if (i > 0) {
                String prev = sorted.get(i - 1);
                if (order.compare(prev, id) == 0) {
                    rank = places.get(i - 1).rank();
                }
            }
            places.add(new Place(id, scores.get(id), reached.get(id), rank));
        }
        return places;
    }

    public Place placeOf(String personId) {
        return leaderboard.stream().filter(p -> p.personId().equals(personId)).findFirst().orElse(null);
    }

    /** Ende der laufenden Runde: letzter Tag 23:59:59 in der Zone des Co-Habits. */
    public Instant challengeEndsAt() {
        return cohabit.challenge.end().plusDays(1).atStartOfDay(zone).toInstant().minusSeconds(1);
    }

    public boolean isType(CohabitType type) {
        return cohabit.type == type;
    }
}
