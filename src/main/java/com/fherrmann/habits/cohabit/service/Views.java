package com.fherrmann.habits.cohabit.service;

import com.fherrmann.habits.cohabit.api.AbstinenceBlock;
import com.fherrmann.habits.cohabit.api.ChallengeBlock;
import com.fherrmann.habits.cohabit.api.CheckinView;
import com.fherrmann.habits.cohabit.api.CohabitConfigView;
import com.fherrmann.habits.cohabit.api.CohabitDetail;
import com.fherrmann.habits.cohabit.api.CohabitRef;
import com.fherrmann.habits.cohabit.api.CohabitSummary;
import com.fherrmann.habits.cohabit.api.FinishedDialog;
import com.fherrmann.habits.cohabit.api.GoalBlock;
import com.fherrmann.habits.cohabit.api.Headline;
import com.fherrmann.habits.cohabit.api.HealthBlock;
import com.fherrmann.habits.cohabit.api.InviteCohabit;
import com.fherrmann.habits.cohabit.api.MemberView;
import com.fherrmann.habits.cohabit.api.MySettings;
import com.fherrmann.habits.cohabit.api.PauseView;
import com.fherrmann.habits.cohabit.api.PersonView;
import com.fherrmann.habits.cohabit.api.ProgressView;
import com.fherrmann.habits.cohabit.api.RankView;
import com.fherrmann.habits.cohabit.api.RunView;
import com.fherrmann.habits.cohabit.api.Seats;
import com.fherrmann.habits.cohabit.api.StreakBlock;
import com.fherrmann.habits.cohabit.model.AutoSource;
import com.fherrmann.habits.cohabit.model.ChallengeConfig;
import com.fherrmann.habits.cohabit.model.ChallengeRound;
import com.fherrmann.habits.cohabit.model.Checkin;
import com.fherrmann.habits.cohabit.model.Cohabit;
import com.fherrmann.habits.cohabit.model.CohabitType;
import com.fherrmann.habits.cohabit.model.GoalConfig;
import com.fherrmann.habits.cohabit.model.GoalCounting;
import com.fherrmann.habits.cohabit.model.HealthMetric;
import com.fherrmann.habits.cohabit.model.GoalMode;
import com.fherrmann.habits.cohabit.model.Invitation;
import com.fherrmann.habits.cohabit.model.Member;
import com.fherrmann.habits.cohabit.model.Message;
import com.fherrmann.habits.cohabit.model.MessagesFile;
import com.fherrmann.habits.cohabit.model.Person;
import com.fherrmann.habits.cohabit.model.Recurrence;
import com.fherrmann.habits.cohabit.model.Rhythm;
import com.fherrmann.habits.cohabit.model.RhythmKind;
import com.fherrmann.habits.cohabit.model.Role;
import com.fherrmann.habits.cohabit.model.RunScoring;
import com.fherrmann.habits.cohabit.model.Scoring;
import com.fherrmann.habits.cohabit.model.Standing;
import com.fherrmann.habits.cohabit.model.TrackingMode;
import com.fherrmann.habits.cohabit.model.ValueUnit;
import com.fherrmann.habits.cohabit.rules.AbstinenceCalc;
import com.fherrmann.habits.cohabit.rules.CohabitEval;
import com.fherrmann.habits.cohabit.rules.CohabitEval.MemberEval;
import com.fherrmann.habits.cohabit.rules.CohabitEval.Place;
import com.fherrmann.habits.cohabit.rules.PeriodScheme;
import com.fherrmann.habits.cohabit.rules.RunPoints;
import com.fherrmann.habits.cohabit.rules.StreakModel;
import com.fherrmann.habits.cohabit.rules.StreakUnit;
import com.fherrmann.habits.cohabit.rules.Texts;
import com.fherrmann.habits.cohabit.store.CohabitStore;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Baut aus dem gerechneten Stand ({@link CohabitEval}) die Antworten der API -
 * mit allen Texten. Rechnen tut nur der Dienst, die Clients zeigen an.
 */
public final class Views {

    public static final int MAX_SEATS = 8;

    private Views() {
    }

    // MARK: - Kleinteile

    public static PersonView person(CohabitStore.Data data, String id) {
        return PersonView.of(data.person(id).orElse(null));
    }

    public static String name(CohabitStore.Data data, String id) {
        return data.person(id).map(p -> p.displayName).orElse("Jemand");
    }

    /** Aktive Mitglieder in Beitrittsreihenfolge. */
    public static List<PersonView> memberViews(CohabitStore.Data data, Cohabit c) {
        return c.members.stream().map(m -> person(data, m.personId)).filter(Objects::nonNull).toList();
    }

    public static int seatsUsed(CohabitStore.Data data, Cohabit c) {
        int invited = (int) data.cohabits().invitations.stream().filter(i -> i.cohabitId.equals(c.id)).count();
        return c.members.size() + invited;
    }

    public static Seats seats(CohabitStore.Data data, Cohabit c) {
        return new Seats(seatsUsed(data, c), MAX_SEATS);
    }

    public static boolean canInvite(Cohabit c, String personId) {
        if (c.archived) {
            return false;
        }
        return c.member(personId).map(m -> m.role == Role.ADMIN || c.membersCanInvite).orElse(false);
    }

    /** Ungelesene Nachrichten: nach dem eigenen Lesestand, nicht von mir, nicht geloescht, nicht blockiert. */
    public static int unread(CohabitStore.Data data, Cohabit c, String viewerId) {
        MessagesFile file = data.messagesOf(c.id);
        String lastRead = file.readState.get(viewerId);
        Set<String> blocked = data.person(viewerId).map(p -> Set.copyOf(p.blocked)).orElse(Set.of());
        boolean counting = lastRead == null;
        int count = 0;
        for (Message m : file.messages) {
            if (!counting) {
                if (m.id.equals(lastRead)) {
                    counting = true;
                }
                continue;
            }
            if (m.deleted || viewerId.equals(m.authorId) || viewerId.equals(m.actorId)
                    || (m.authorId != null && blocked.contains(m.authorId))) {
                continue;
            }
            count++;
        }
        return count;
    }

    public static String typeName(CohabitType type) {
        return switch (type) {
            case STREAK -> "Streak";
            case ABSTINENCE -> "Abstinenz";
            case GOAL -> "Ziel";
            case CHALLENGE -> "Challenge";
        };
    }

    public static String typeLine(Cohabit c, LocalDate today) {
        return switch (c.type) {
            case STREAK -> "Streak · " + (c.auto != null ? Texts.autoRhythm(c.auto) : Texts.rhythm(StreakModel.rhythm(c)));
            case ABSTINENCE -> c.abstinence != null && c.abstinence.groupMode() ? "Abstinenz · Gruppenmodus" : "Abstinenz";
            case GOAL -> (c.goal != null && c.goal.mode() == GoalMode.INDIVIDUAL ? "Einzelziel" : "Teamziel")
                    + (c.goal == null ? "" : " · bis " + Texts.dateShort(c.goal.deadline()));
            case CHALLENGE -> "Challenge" + (c.challenge == null ? "" : " · " + periodLabel(c.challenge.start(),
                    c.challenge.end(), today));
        };
    }

    /** "September", "KW 40" oder "01.09.–15.09.". */
    public static String periodLabel(LocalDate start, LocalDate end, LocalDate today) {
        if (start.getDayOfMonth() == 1 && end.equals(start.withDayOfMonth(start.lengthOfMonth()))) {
            return Texts.month(start) + (start.getYear() == today.getYear() ? "" : " " + start.getYear());
        }
        if (start.getDayOfWeek().getValue() == 1 && end.equals(start.plusDays(6))) {
            return "KW " + start.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        }
        return Texts.dateShort(start) + "–" + Texts.dateShort(end);
    }

    /** Die Regeln als Chips auf der Detailseite. */
    public static List<String> rules(Cohabit c) {
        List<String> rules = new ArrayList<>(inviteRules(c));
        rules.add(Texts.backfillRule(c.backfillHours));
        rules.add(c.timezone);
        if (c.reminderTime != null) {
            rules.add("Erinnerung " + c.reminderTime);
        }
        if (c.membersCanInvite) {
            rules.add("Mitglieder dürfen einladen");
        }
        return rules;
    }

    /** Die Regeln, die eine eingeladene Person vorab sieht - ohne Zeitzone und Frist. */
    public static List<String> inviteRules(Cohabit c) {
        List<String> rules = new ArrayList<>();
        if (c.photoRequired) {
            rules.add("Beweisfoto-Pflicht");
        }
        if (c.tracking != null && c.tracking.mode() == TrackingMode.VALUE && c.tracking.unit() != null && c.health == null) {
            rules.add("Mit Wert (" + Texts.unitLabel(c.tracking.unit()) + ")");
        }
        if (c.streak != null && c.streak.groupStreak()) {
            rules.add("Gruppen-Streak");
        }
        if (c.health != null) {
            rules.add("Health: " + Texts.healthLabel(c.health.metric()));
        }
        if (c.auto != null) {
            rules.add(Texts.autoRule(c.auto));
        }
        if (c.type == CohabitType.CHALLENGE && c.challenge != null && c.challenge.runPoints()) {
            rules.addAll(runRules(RunPoints.scoringOf(c)));
        }
        if (c.type == CohabitType.CHALLENGE && c.challenge != null && c.challenge.stake() != null) {
            rules.add("Einsatz: " + c.challenge.stake());
        }
        return rules;
    }

    /** "Basis 10 P ab 20 Min.", "1 P je km", "1 P je 6 Min.", "Pace unter 8:00 min/km". */
    static List<String> runRules(RunScoring r) {
        List<String> rules = new ArrayList<>();
        if (r.base() > 0) {
            rules.add("Basis " + r.base() + " P" + (r.baseMinutes() > 0 ? " ab " + r.baseMinutes() + " Min." : ""));
        }
        if (r.perKm() > 0) {
            rules.add(r.perKm() + " P je km");
        }
        rules.add("1 P je " + r.perMinutes() + " Min.");
        rules.add("Pace unter " + RunPoints.paceLimitText(r.paceLimit()) + " min/km");
        return rules;
    }

    public static InviteCohabit inviteCohabit(CohabitStore.Data data, Cohabit c, LocalDate today) {
        return new InviteCohabit(CohabitRef.of(c), typeLine(c, today), inviteRules(c), memberViews(data, c),
                seats(data, c));
    }

    public static String valueUnit(Cohabit c) {
        return c.tracking != null && c.tracking.mode() == TrackingMode.VALUE && c.tracking.unit() != null
                ? c.tracking.unit().name() : null;
    }

    static double fraction(double done, double goal) {
        if (goal <= 0) {
            return 0;
        }
        return Texts.round2(Math.min(1.0, done / goal));
    }

    static Number num(double value) {
        double v = Texts.round2(value);
        return v == Math.rint(v) && Math.abs(v) < 1e15 ? (Number) (long) v : (Number) v;
    }

    // MARK: - Zusammenfassung

    public static CohabitSummary summary(CohabitStore.Data data, CohabitEval e, String viewerId) {
        Cohabit c = e.cohabit;
        MemberEval me = e.member(viewerId);
        if (me == null) {
            throw Errors.notFound("Co-Habit nicht gefunden.");
        }
        List<PersonView> members = memberViews(data, c);
        List<String> doneTodayBy = doneTodayBy(e);
        return switch (c.type) {
            case STREAK -> streakSummary(data, e, me, members, doneTodayBy, viewerId);
            case ABSTINENCE -> abstinenceSummary(data, e, me, members, viewerId);
            case GOAL -> goalSummary(data, e, me, members, doneTodayBy, viewerId);
            case CHALLENGE -> challengeSummary(data, e, me, members, doneTodayBy, viewerId);
        };
    }

    static List<String> doneTodayBy(CohabitEval e) {
        List<String> ids = new ArrayList<>();
        for (MemberEval m : e.members.values()) {
            boolean done = switch (e.cohabit.type) {
                case STREAK -> m.unavailable == null && m.doneToday;
                case GOAL, CHALLENGE -> m.entryToday;
                case ABSTINENCE -> false;
            };
            if (done) {
                ids.add(m.member.personId);
            }
        }
        return ids;
    }

    private static CohabitSummary streakSummary(CohabitStore.Data data, CohabitEval e, MemberEval me,
                                                List<PersonView> members, List<String> doneTodayBy,
                                                String viewerId) {
        Cohabit c = e.cohabit;
        StreakUnit unit = StreakModel.scheme(c).unit();
        String status = streakStatus(c, me);
        String remaining = remainingText(e, me);
        int current = me.streak.current();
        Headline headline = me.unavailable != null
                ? new Headline("–", unit.plural(), "–")
                : new Headline(String.valueOf(current), unit.label(current), current + " " + unit.abbreviation());
        List<String> others = new ArrayList<>();
        for (String id : doneTodayBy) {
            if (!id.equals(viewerId)) {
                others.add(name(data, id));
            }
        }
        String subline = others.isEmpty() ? remaining : Texts.names(others) + " heute schon";
        boolean manual = c.auto == null;
        boolean canCheckIn = manual && !c.archived && !me.doneToday && !e.today.isBefore(me.start);
        String label = !manual ? null
                : me.doneToday ? "Heute erledigt"
                : c.photoRequired ? "Beweisfoto & abhaken" : "Abhaken";
        return new CohabitSummary(CohabitRef.of(c), c.archived, headline, typeLine(c, e.today), subline,
                streakListLine(c, e, me, remaining), status, section(status), me.unavailable, canCheckIn,
                c.photoRequired, valueUnit(c), label, false, members, members.size(), doneTodayBy,
                streakProgress(e, me), null, unread(data, c, viewerId));
    }

    static String streakStatus(Cohabit c, MemberEval me) {
        if (me.unavailable != null) {
            return "UNAVAILABLE";
        }
        if (c.auto != null && c.auto.source() == AutoSource.FOOD_TARGET_WEEKLY) {
            // Nichts zu tun - entschieden wird nach Sonntag. Laeuft, wie eine Abstinenz.
            return "RUNNING";
        }
        if (isDayBased(c)) {
            if (me.doneToday) {
                return "DONE";
            }
            return me.dueToday ? "OPEN" : "RUNNING";
        }
        if (me.doneToday || me.streak.currentDone()) {
            return "DONE";
        }
        return me.streak.currentRequired() == 0 ? "RUNNING" : "OPEN";
    }

    static boolean isDayBased(Cohabit c) {
        if (c.auto != null) {
            return !StreakModel.weeklyAuto(c.auto);
        }
        RhythmKind kind = StreakModel.rhythm(c).kind();
        return kind == RhythmKind.DAILY || kind == RhythmKind.WEEKDAYS;
    }

    static String section(String status) {
        return status.equals("OPEN") || status.equals("DONE") ? "OPEN_TODAY" : "RUNNING";
    }

    /** "noch 1 Eintrag diese Woche", "heute noch offen", "diese Woche geschafft" ... */
    public static String remainingText(CohabitEval e, MemberEval me) {
        Cohabit c = e.cohabit;
        if (me.unavailable != null) {
            return me.unavailable;
        }
        if (c.auto != null && c.auto.source() == AutoSource.FOOD_TARGET_WEEKLY) {
            if (me.streak.currentRequired() == 0) {
                return "pausiert";
            }
            if (me.facts.todayValue() == 0) {
                return "diese Woche noch nichts getrackt";
            }
            return me.facts.todayValue() <= me.facts.todayGoal() ? "bisher im Ziel" : "bisher über dem Ziel";
        }
        if (c.auto != null && StreakModel.weeklyAuto(c.auto)) {
            if (me.streak.currentRequired() == 0) {
                return "pausiert";
            }
            if (me.streak.currentDone()) {
                return "diese Woche geschafft";
            }
            String missing = Texts.number(me.streak.currentRequired() - me.streak.currentAchieved());
            return c.auto.focusWeekly() ? "noch " + missing + " Min. diese Woche" : "noch " + missing + " Schritte diese Woche";
        }
        if (isDayBased(c)) {
            if (me.doneToday) {
                return "heute erledigt";
            }
            if (me.pausedToday) {
                return "pausiert";
            }
            return me.dueToday ? "heute noch offen" : "heute frei";
        }
        Rhythm r = StreakModel.rhythm(c);
        if (me.streak.currentRequired() == 0) {
            return "pausiert";
        }
        if (r.kind() == RhythmKind.INTERVAL) {
            return (me.streak.currentDone() ? "geschafft bis " : "fällig bis ") + Texts.dateShort(me.streak.currentEnd());
        }
        boolean week = r.kind() == RhythmKind.TIMES_PER_WEEK;
        if (me.streak.currentDone()) {
            return week ? "diese Woche geschafft" : "diesen Monat geschafft";
        }
        int missing = me.streak.currentRequired() - me.streak.currentAchieved();
        return "noch " + missing + " " + Texts.plural(missing, "Eintrag", "Einträge")
                + (week ? " diese Woche" : " diesen Monat");
    }

    private static String streakListLine(Cohabit c, CohabitEval e, MemberEval me, String remaining) {
        String photo = c.photoRequired ? " · Foto" : "";
        if (me.unavailable != null) {
            return me.unavailable;
        }
        if (c.auto != null) {
            ProgressView p = streakProgress(e, me);
            return switch (c.auto.source()) {
                case FOOD -> Texts.number(p.done().doubleValue()) + " von " + Texts.number(p.goal().doubleValue()) + " kcal";
                case STEPS_WEEKLY -> Texts.number(p.done().doubleValue()) + " von " + Texts.number(p.goal().doubleValue());
                case FOCUS -> Texts.number(p.done().doubleValue()) + " von " + Texts.number(p.goal().doubleValue()) + " Min.";
                case FOOD_TARGET_WEEKLY -> p.done().intValue() == 0 ? remaining
                        : "Ø " + Texts.number(p.done().doubleValue()) + " von " + Texts.number(p.goal().doubleValue()) + " kcal";
            };
        }
        Rhythm r = StreakModel.rhythm(c);
        return switch (r.kind()) {
            case TIMES_PER_WEEK, TIMES_PER_MONTH -> me.streak.currentRequired() == 0 ? "pausiert" + photo
                    : me.streak.currentAchieved() + " von " + me.streak.currentRequired() + photo;
            case WEEKDAYS -> Texts.rhythm(r) + " · " + remaining + photo;
            default -> remaining + photo;
        };
    }

    static ProgressView streakProgress(CohabitEval e, MemberEval me) {
        Cohabit c = e.cohabit;
        if (me.unavailable != null) {
            return null;
        }
        if (c.auto != null) {
            if (StreakModel.weeklyAuto(c.auto) && c.auto.source() != AutoSource.FOOD_TARGET_WEEKLY) {
                return new ProgressView(me.streak.currentAchieved(), me.streak.currentRequired(),
                        fraction(me.streak.currentAchieved(), me.streak.currentRequired()));
            }
            return new ProgressView(me.facts.todayValue(), me.facts.todayGoal(),
                    fraction(me.facts.todayValue(), me.facts.todayGoal()));
        }
        Rhythm r = StreakModel.rhythm(c);
        switch (r.kind()) {
            case TIMES_PER_WEEK, TIMES_PER_MONTH -> {
                return new ProgressView(me.streak.currentAchieved(), me.streak.currentRequired(),
                        fraction(me.streak.currentAchieved(), me.streak.currentRequired()));
            }
            case INTERVAL -> {
                int done = me.streak.currentAchieved() > 0 ? 1 : 0;
                return new ProgressView(done, 1, done);
            }
            default -> {
                // Tagesrhythmen: die Woche als Punkte - erledigte gegen faellige Tage.
                LocalDate monday = PeriodScheme.mondayOf(e.today);
                Predicate<LocalDate> paused = StreakModel.paused(me.member.pauses);
                int due = 0;
                int done = 0;
                for (LocalDate d = monday; d.isBefore(monday.plusDays(7)); d = d.plusDays(1)) {
                    if (d.isBefore(me.start) || paused.test(d) || !StreakModel.dueByRhythm(c, d)) {
                        continue;
                    }
                    due++;
                    if (me.doneDays.contains(d)) {
                        done++;
                    }
                }
                return new ProgressView(done, due, fraction(done, due));
            }
        }
    }

    private static CohabitSummary abstinenceSummary(CohabitStore.Data data, CohabitEval e, MemberEval me,
                                                    List<PersonView> members, String viewerId) {
        Cohabit c = e.cohabit;
        AbstinenceCalc.Outcome o = me.abstinence;
        int days = e.groupDays != null ? e.groupDays : o.current();
        Headline headline = new Headline(String.valueOf(days), Texts.plural(days, "Tag", "Tage"), days + " T");
        String record = "Rekord " + o.record() + " " + Texts.plural(o.record(), "Tag", "Tage");
        String toRecord = toRecordText(o);
        boolean canCheckIn = !c.archived && !me.entryToday && !e.today.isBefore(me.start);
        return new CohabitSummary(CohabitRef.of(c), c.archived, headline, typeLine(c, e.today),
                toRecord == null ? record : toRecord, "Abstinenz · " + record, "RUNNING", "RUNNING", null,
                canCheckIn, false, null, "Unterbrechung eintragen", false, members, members.size(), List.of(), null,
                null, unread(data, c, viewerId));
    }

    static String toRecordText(AbstinenceCalc.Outcome o) {
        if (o.record() > o.current()) {
            return "noch " + (o.record() - o.current()) + " bis zum Rekord";
        }
        return o.current() > 0 ? "neuer Rekord" : null;
    }

    private static CohabitSummary goalSummary(CohabitStore.Data data, CohabitEval e, MemberEval me,
                                              List<PersonView> members, List<String> doneTodayBy,
                                              String viewerId) {
        Cohabit c = e.cohabit;
        GoalConfig g = c.goal;
        double total = goalTotal(e, me);
        int percent = goalPercent(total, g.target());
        Headline headline = new Headline(percent + "%", "", percent + "%");
        boolean team = g.mode() == GoalMode.TEAM;
        String subline = team
                ? "Teamziel · " + (members.size() == 1 ? "allein" : members.size() + " machen mit")
                : "Einzelziel · " + goalRemainingText(e, total);
        String listLine = Texts.number(total) + " von " + Texts.number(g.target()) + " · " + goalRemainingText(e, total);
        boolean open = !e.today.isBefore(g.start()) && !e.today.isAfter(g.deadline());
        boolean canCheckIn = open && !c.archived && !e.today.isBefore(me.start);
        return new CohabitSummary(CohabitRef.of(c), c.archived, headline, typeLine(c, e.today), subline, listLine,
                "RUNNING", "RUNNING", null, canCheckIn, c.photoRequired, valueUnit(c), goalCheckInLabel(c), false,
                members, members.size(), doneTodayBy,
                new ProgressView(num(total), num(g.target()), fraction(total, g.target())), null,
                unread(data, c, viewerId));
    }

    static double goalTotal(CohabitEval e, MemberEval me) {
        return e.cohabit.goal.mode() == GoalMode.TEAM ? e.goalTotal : me.amount;
    }

    static int goalPercent(double total, double target) {
        if (target <= 0) {
            return 0;
        }
        return (int) Math.min(999, Math.floor(100.0 * total / target + 1e-9));
    }

    static String goalCheckInLabel(Cohabit c) {
        if (c.goal != null && c.goal.counting() == GoalCounting.AMOUNT && c.tracking.unit() != null) {
            return Texts.unitLabel(c.tracking.unit()) + " manuell eintragen";
        }
        return "Eintrag hinzufügen";
    }

    /** Beim Teamziel das festgehaltene Ergebnis, beim Einzelziel das eigene. */
    static boolean goalReached(Cohabit c, double total) {
        if (c.goal.mode() == GoalMode.TEAM && c.goalState != null && c.goalState.finishedAt != null) {
            return c.goalState.reached;
        }
        return total >= c.goal.target();
    }

    static String goalRemainingText(CohabitEval e, double total) {
        GoalConfig g = e.cohabit.goal;
        if (e.today.isAfter(g.deadline())) {
            return goalReached(e.cohabit, total) ? "Ziel erreicht" : "Ziel verfehlt";
        }
        long days = ChronoUnit.DAYS.between(e.today, g.deadline());
        if (days == 0) {
            return "letzter Tag";
        }
        return "noch " + days + " " + Texts.plural(days, "Tag", "Tage");
    }

    private static CohabitSummary challengeSummary(CohabitStore.Data data, CohabitEval e, MemberEval me,
                                                   List<PersonView> members, List<String> doneTodayBy,
                                                   String viewerId) {
        Cohabit c = e.cohabit;
        boolean running = e.challengeRunning();
        Place mine = e.placeOf(viewerId);
        Headline headline;
        if (e.phase == CohabitEval.ChallengePhase.UPCOMING || e.phase == CohabitEval.ChallengePhase.BETWEEN_ROUNDS) {
            long d = ChronoUnit.DAYS.between(e.today, c.challenge.start());
            headline = new Headline(String.valueOf(d), Texts.plural(d, "Tag bis Start", "Tage bis Start"),
                    "in " + d + " T");
        } else {
            headline = new Headline("#" + mine.rank(), "dein Platz", "#" + mine.rank());
        }
        String gap = running || e.phase == CohabitEval.ChallengePhase.FINISHED ? gapText(data, e, viewerId) : null;
        String ends = endsShort(e);
        String subline = joinNonNull(" · ", gap, ends);
        String status = running ? (me.entryToday ? "DONE" : "OPEN") : "RUNNING";
        boolean canCheckIn = running && !c.archived && !e.today.isBefore(me.start);
        boolean run = c.challenge.runPoints();
        String label = run ? "Lauf eintragen"
                : c.tracking.mode() == TrackingMode.VALUE ? "Wert eintragen" : "+1 " + c.name + " eintragen";
        RankView rank = new RankView(mine == null ? 0 : mine.rank(), e.leaderboard.size(), gap);
        boolean scored = running || e.phase == CohabitEval.ChallengePhase.FINISHED;
        return new CohabitSummary(CohabitRef.of(c), c.archived, headline, typeLine(c, e.today), subline,
                joinNonNull(" · ", "Challenge", ends, gap), status, section(status), null, canCheckIn,
                c.photoRequired, run ? null : valueUnit(c), label, run, members, members.size(), doneTodayBy,
                scored ? challengeProgress(e, mine) : null, rank, unread(data, c, viewerId));
    }

    /**
     * Der eigene Stand als Balken: bis zum Zielwert, sonst bis zum Fuehrenden - wer
     * fuehrt, hat ihn voll. Vor dem Start gibt es keinen.
     */
    static ProgressView challengeProgress(CohabitEval e, Place mine) {
        double score = mine == null ? 0 : mine.score();
        Double target = e.cohabit.challenge.target();
        double goal = target != null && target > 0 ? target
                : e.leaderboard.stream().mapToDouble(Place::score).max().orElse(0);
        return new ProgressView(num(score), num(goal), fraction(score, goal));
    }

    static String joinNonNull(String sep, String... parts) {
        List<String> list = new ArrayList<>();
        for (String p : parts) {
            if (p != null && !p.isBlank()) {
                list.add(p);
            }
        }
        return String.join(sep, list);
    }

    /** "noch 2 bis Lena", auf Platz 1 "vorn" bzw. "gleichauf mit Lena". */
    static String gapText(CohabitStore.Data data, CohabitEval e, String viewerId) {
        Place mine = e.placeOf(viewerId);
        if (mine == null) {
            return null;
        }
        if (mine.rank() == 1) {
            List<String> tied = e.leaderboard.stream()
                    .filter(p -> p.rank() == 1 && !p.personId().equals(viewerId))
                    .map(p -> name(data, p.personId()))
                    .toList();
            return tied.isEmpty() ? "vorn" : "gleichauf mit " + Texts.names(tied);
        }
        Place better = e.leaderboard.stream()
                .filter(p -> p.rank() < mine.rank())
                .max(Comparator.comparingInt(Place::rank))
                .orElse(null);
        if (better == null) {
            return null;
        }
        double diff = Texts.round2(better.score() - mine.score());
        if (diff <= 0) {
            return "knapp hinter " + name(data, better.personId());
        }
        String amount = Texts.number(diff) + (e.cohabit.challenge.runPoints() ? " P" : "");
        return "noch " + amount + " bis " + name(data, better.personId());
    }

    /** "endet heute", "endet morgen", "endet in 3 Tagen", "startet am 05.10.", "beendet". */
    public static String endsShort(CohabitEval e) {
        ChallengeConfig ch = e.cohabit.challenge;
        switch (e.phase) {
            case RUNNING -> {
                long days = ChronoUnit.DAYS.between(e.today, ch.end());
                if (days == 0) {
                    return "endet heute";
                }
                return days == 1 ? "endet morgen" : "endet in " + days + " Tagen";
            }
            case UPCOMING, BETWEEN_ROUNDS -> {
                long days = ChronoUnit.DAYS.between(e.today, ch.start());
                return days == 1 ? "startet morgen" : "startet am " + Texts.dateShort(ch.start());
            }
            default -> {
                return "beendet";
            }
        }
    }

    /** "endet in 9 Std. 41 Min." - zum Abrufzeitpunkt, die Clients zaehlen nicht selbst. */
    public static String endsInText(CohabitEval e) {
        if (e.phase != CohabitEval.ChallengePhase.RUNNING) {
            return endsShort(e);
        }
        Duration left = Duration.between(e.now, e.challengeEndsAt());
        if (left.isNegative() || left.toMinutes() < 1) {
            return "endet gleich";
        }
        if (left.toHours() < 24) {
            long hours = left.toHours();
            long minutes = left.toMinutesPart();
            return hours == 0 ? "endet in " + minutes + " Min." : "endet in " + hours + " Std. " + minutes + " Min.";
        }
        return endsShort(e);
    }

    // MARK: - Detail

    public static CohabitDetail detail(CohabitStore.Data data, CohabitEval e, String viewerId) {
        Cohabit c = e.cohabit;
        MemberEval me = e.member(viewerId);
        CohabitSummary summary = summary(data, e, viewerId);
        Member m = me.member;
        return new CohabitDetail(summary, CohabitConfigView.of(c), c.createdBy, c.createdAt, m.role.name(),
                canInvite(c, viewerId), seats(data, c), memberList(data, e), rules(c),
                c.type == CohabitType.STREAK ? streakBlock(data, e, viewerId) : null,
                c.type == CohabitType.ABSTINENCE ? abstinenceBlock(data, e, viewerId) : null,
                c.type == CohabitType.GOAL ? goalBlock(data, e, viewerId) : null,
                c.type == CohabitType.CHALLENGE ? challengeBlock(data, e, viewerId) : null,
                healthBlock(c, m), myCheckins(data, e, viewerId), backfillFrom(e, me),
                m.pauses.stream().sorted(Comparator.comparing(p -> p.from))
                        .map(p -> new PauseView(p.id, p.from, p.to)).toList(),
                new MySettings(m.settings.muted, m.settings.checkins, m.settings.chat, m.settings.shareBreaks,
                        m.settings.healthConsent),
                summary.unreadMessages(), dialog(data, e, viewerId));
    }

    static List<MemberView> memberList(CohabitStore.Data data, CohabitEval e) {
        Cohabit c = e.cohabit;
        List<MemberView> list = new ArrayList<>();
        for (Member m : c.members) {
            PersonView p = person(data, m.personId);
            if (p == null) {
                continue;
            }
            boolean paused = m.pauses.stream().anyMatch(x -> x.covers(e.today));
            list.add(new MemberView(p, m.role.name(), paused ? "PAUSED" : "ACTIVE", m.joinedAt));
        }
        for (Invitation i : data.cohabits().invitations) {
            if (i.cohabitId.equals(c.id)) {
                PersonView p = person(data, i.toId);
                if (p != null) {
                    list.add(new MemberView(p, Role.MEMBER.name(), "INVITED", null));
                }
            }
        }
        return list;
    }

    /** Ab wann nachgetragen werden darf: Frist vor jetzt, nie vor dem Beitritt (bzw. Ziel-/Rundenstart). */
    public static LocalDate backfillFrom(CohabitEval e, MemberEval me) {
        Cohabit c = e.cohabit;
        LocalDate from = LocalDate.ofInstant(e.now.minus(Duration.ofHours(c.backfillHours)), e.zone);
        if (from.isAfter(e.today)) {
            from = e.today;
        }
        if (from.isBefore(me.start)) {
            from = me.start;
        }
        if (c.type == CohabitType.GOAL && c.goal != null && from.isBefore(c.goal.start())) {
            from = c.goal.start();
        }
        if (c.type == CohabitType.CHALLENGE && c.challenge != null && from.isBefore(c.challenge.start())) {
            from = c.challenge.start();
        }
        return from;
    }

    static List<CheckinView> myCheckins(CohabitStore.Data data, CohabitEval e, String viewerId) {
        LocalDate from = backfillFrom(e, e.member(viewerId));
        return e.checkins.stream()
                .filter(ch -> ch.personId.equals(viewerId) && !ch.date.isBefore(from) && !ch.date.isAfter(e.today))
                .sorted(Comparator.comparing((Checkin ch) -> ch.date).thenComparing(ch -> ch.createdAt).reversed())
                .map(ch -> checkinView(data, e.cohabit, ch, viewerId, from, e.today))
                .toList();
    }

    public static CheckinView checkinView(CohabitStore.Data data, Cohabit c, Checkin ch, String viewerId,
                                          LocalDate backfillFrom, LocalDate today) {
        boolean editable = ch.personId.equals(viewerId) && !c.archived && !ch.date.isBefore(backfillFrom)
                && !ch.date.isAfter(today) && ch.source != com.fherrmann.habits.cohabit.model.CheckinSource.HEALTH;
        RunView run = runView(data, c, ch);
        return new CheckinView(ch.id, ch.cohabitId, person(data, ch.personId), ch.kind.name(), ch.date, ch.createdAt,
                ch.value == null ? null : num(ch.value), run != null ? runValueText(run) : valueText(c, ch.value),
                ch.note, ch.photoId, ch.caption, ch.source.name(), editable, run, ch.photos());
    }

    /** Ein Lauf samt seinen Punkten - die haengen an den anderen Laeufen des Tages. */
    static RunView runView(CohabitStore.Data data, Cohabit c, Checkin ch) {
        if (c.type != CohabitType.CHALLENGE || c.challenge == null || !c.challenge.runPoints() || !RunPoints.isRun(ch)) {
            return null;
        }
        RunScoring r = RunPoints.scoringOf(c);
        RunPoints.Points p = RunPoints.score(r, data.checkins(c.id)).get(ch.id);
        return new RunView(ch.durationMinutes, num(ch.distanceKm),
                RunPoints.paceText(RunPoints.paceSeconds(ch.durationMinutes, ch.distanceKm)), p.total(),
                RunPoints.pointsText(p.total()), RunPoints.breakdownText(r, p));
    }

    /** "5,8 km · 35 Min. · 6:02 min/km · +20 P" */
    static String runValueText(RunView run) {
        return Texts.number(run.distanceKm().doubleValue()) + " km · " + run.durationMinutes() + " Min. · "
                + run.paceText() + " · " + run.pointsText();
    }

    static String valueText(Cohabit c, Double value) {
        if (value == null) {
            return null;
        }
        ValueUnit unit = c.tracking == null ? null : c.tracking.unit();
        return Texts.valueText(value, unit);
    }

    static HealthBlock healthBlock(Cohabit c, Member m) {
        if (c.health == null) {
            return null;
        }
        return new HealthBlock(c.health.metric().name(), Texts.healthLabel(c.health.metric()),
                m.settings.healthConsent, m.lastHealthSyncAt, Texts.healthShareText(c.health.metric()),
                c.health.metric() == HealthMetric.KCAL ? "HEALTHY" : "DEVICE");
    }

    // MARK: - STREAK-Block

    static StreakBlock streakBlock(CohabitStore.Data data, CohabitEval e, String viewerId) {
        Cohabit c = e.cohabit;
        MemberEval me = e.member(viewerId);
        StreakUnit unit = StreakModel.scheme(c).unit();
        int current = me.streak.current();
        LocalDate monday = PeriodScheme.mondayOf(e.today);
        List<LocalDate> days = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            days.add(monday.plusDays(i));
        }
        List<StreakBlock.Row> rows = new ArrayList<>();
        List<MemberEval> ordered = new ArrayList<>(e.members.values());
        ordered.sort(Comparator.comparing((MemberEval m) -> !m.member.personId.equals(viewerId))
                .thenComparing(m -> m.member.joinedAt == null ? java.time.Instant.EPOCH : m.member.joinedAt));
        for (MemberEval m : ordered) {
            PersonView p = person(data, m.member.personId);
            if (p == null) {
                continue;
            }
            List<String> cells = new ArrayList<>();
            for (LocalDate d : days) {
                cells.add(cell(e, m, d));
            }
            rows.add(new StreakBlock.Row(p, cells));
        }
        StreakBlock.Record record = null;
        int best = 0;
        String bestPerson = null;
        for (MemberEval m : e.members.values()) {
            int value = Math.max(m.longest, m.member.bestStreak);
            if (value > best) {
                best = value;
                bestPerson = m.member.personId;
            }
        }
        if (best > 0) {
            record = new StreakBlock.Record(best, best + " " + unit.abbreviation(), person(data, bestPerson));
        }
        StreakBlock.Group group = e.groupStreak == null ? null
                : new StreakBlock.Group(e.groupStreak, unit.label(e.groupStreak));
        return new StreakBlock(current, unit.name(), unit.label(current), me.streak.atRisk(), remainingText(e, me),
                new StreakBlock.Week(days, e.today.getDayOfWeek().getValue() - 1, rows),
                me.rate == null ? 0 : me.rate.percent(), record, group);
    }

    static String cell(CohabitEval e, MemberEval m, LocalDate d) {
        Cohabit c = e.cohabit;
        if (d.isAfter(e.today)) {
            return "FUTURE";
        }
        if (d.isBefore(m.start)) {
            return "BEFORE_JOIN";
        }
        if (m.member.pauses.stream().anyMatch(p -> p.covers(d))) {
            return "PAUSED";
        }
        if (m.unavailable != null) {
            return "NOT_DUE";
        }
        boolean done;
        if (c.auto == null) {
            done = m.doneDays.contains(d);
        } else if (StreakModel.weeklyAuto(c.auto)) {
            done = m.streak.currentDone();
        } else {
            done = m.facts.done(d);
        }
        if (done) {
            return "DONE";
        }
        boolean dayBased = isDayBased(c);
        if (d.equals(e.today)) {
            if (dayBased) {
                return StreakModel.dueByRhythm(c, d) ? "OPEN" : "NOT_DUE";
            }
            return m.streak.currentRequired() > 0 && !m.streak.currentDone() ? "OPEN" : "NOT_DUE";
        }
        if (dayBased) {
            return StreakModel.dueByRhythm(c, d) ? "MISSED" : "NOT_DUE";
        }
        return "NOT_DUE";
    }

    // MARK: - ABSTINENCE-Block

    static AbstinenceBlock abstinenceBlock(CohabitStore.Data data, CohabitEval e, String viewerId) {
        MemberEval me = e.member(viewerId);
        AbstinenceCalc.Outcome o = me.abstinence;
        List<MemberEval> ordered = new ArrayList<>(e.members.values());
        ordered.sort(Comparator.comparing((MemberEval m) -> !m.member.personId.equals(viewerId))
                .thenComparing(m -> -m.abstinence.current()));
        List<AbstinenceBlock.MemberDays> members = new ArrayList<>();
        for (MemberEval m : ordered) {
            PersonView p = person(data, m.member.personId);
            if (p == null) {
                continue;
            }
            AbstinenceCalc.Outcome mo = m.abstinence;
            boolean newRecord = mo.priorRecord() > 0 && mo.current() > mo.priorRecord();
            members.add(new AbstinenceBlock.MemberDays(p, mo.current(), newRecord));
        }
        List<AbstinenceBlock.SeriesItem> series = new ArrayList<>();
        List<AbstinenceCalc.Series> all = o.series();
        int from = Math.max(0, all.size() - 6);
        for (AbstinenceCalc.Series s : all.subList(from, all.size())) {
            String label = s.current() ? "aktuell"
                    : Texts.month(s.end()) + (s.end().getYear() == e.today.getYear() ? "" : " " + s.end().getYear());
            series.add(new AbstinenceBlock.SeriesItem(label, s.days(), s.current()));
        }
        return new AbstinenceBlock(o.current(), o.record(), toRecordText(o), members, series,
                e.groupDays == null ? null : new AbstinenceBlock.Group(e.groupDays));
    }

    // MARK: - GOAL-Block

    static GoalBlock goalBlock(CohabitStore.Data data, CohabitEval e, String viewerId) {
        Cohabit c = e.cohabit;
        GoalConfig g = c.goal;
        MemberEval me = e.member(viewerId);
        double total = goalTotal(e, me);
        double planDelta = total - g.target() * e.goalElapsedShare();
        // Kaufmaennisch vom Nullpunkt weg: +1,5 und -1,5 werden gleich weit gerundet.
        planDelta = integral(c) ? Math.signum(planDelta) * Math.round(Math.abs(planDelta)) : Texts.round2(planDelta);
        String planText = planDelta > 0 ? Texts.signed(planDelta) + " vor Plan"
                : planDelta < 0 ? Texts.signed(planDelta) + " hinter Plan" : "im Plan";
        long remainingDays = Math.max(0, ChronoUnit.DAYS.between(e.today, g.deadline()));
        List<MemberEval> ordered = new ArrayList<>(e.members.values());
        ordered.sort(Comparator.comparing((MemberEval m) -> -m.amount).thenComparing(m -> m.member.personId));
        double max = ordered.stream().mapToDouble(m -> m.amount).max().orElse(0);
        List<GoalBlock.Contribution> contributions = new ArrayList<>();
        for (MemberEval m : ordered) {
            PersonView p = person(data, m.member.personId);
            if (p == null) {
                continue;
            }
            double f = g.mode() == GoalMode.TEAM ? fraction(m.amount, max) : fraction(m.amount, g.target());
            contributions.add(new GoalBlock.Contribution(p, num(m.amount), Texts.number(m.amount), f));
        }
        GoalBlock.Finished finished = null;
        if (e.today.isAfter(g.deadline())) {
            boolean reached = goalReached(c, total);
            finished = new GoalBlock.Finished(reached, reached ? "Ziel erreicht" : "Ziel verfehlt");
        }
        String unitLabel = g.counting() == GoalCounting.ENTRIES ? "Einträge" : Texts.unitLabel(c.tracking.unit());
        return new GoalBlock(num(g.target()), Texts.number(g.target()), unitLabel, g.deadline(), g.counting().name(),
                g.mode().name(), typeLine(c, e.today), num(total),
                Texts.number(total) + " von " + Texts.number(g.target()), goalPercent(total, g.target()),
                num(planDelta), planText, (int) remainingDays, goalRemainingText(e, total), contributions, finished);
    }

    static boolean integral(Cohabit c) {
        if (c.goal != null && c.goal.counting() == GoalCounting.ENTRIES) {
            return true;
        }
        return c.tracking == null || c.tracking.unit() != ValueUnit.KM;
    }

    // MARK: - CHALLENGE-Block

    static ChallengeBlock challengeBlock(CohabitStore.Data data, CohabitEval e, String viewerId) {
        Cohabit c = e.cohabit;
        ChallengeConfig ch = c.challenge;
        Place mine = e.placeOf(viewerId);
        double top = e.leaderboard.isEmpty() ? 0 : e.leaderboard.getFirst().score();
        List<ChallengeBlock.LeaderRow> rows = new ArrayList<>();
        for (Place p : e.leaderboard) {
            PersonView pv = person(data, p.personId());
            if (pv == null) {
                continue;
            }
            double f = ch.scoring() == Scoring.FIRST_TO_TARGET && ch.target() != null
                    ? fraction(p.score(), ch.target()) : fraction(p.score(), top);
            rows.add(new ChallengeBlock.LeaderRow(p.rank(), pv, num(p.score()), scoreText(c, p.score()), f));
        }
        List<ChallengeBlock.PastRound> past = new ArrayList<>();
        if (c.challengeState != null) {
            List<ChallengeRound> rounds = new ArrayList<>(c.challengeState.rounds);
            rounds.sort(Comparator.comparingInt((ChallengeRound r) -> r.number).reversed());
            for (ChallengeRound r : rounds) {
                past.add(new ChallengeBlock.PastRound(r.label,
                        r.winners.stream().map(id -> person(data, id)).filter(Objects::nonNull).toList()));
            }
        }
        boolean finished = ch.recurrence() == Recurrence.NONE && c.challengeState != null
                && c.challengeState.roundEndedAt != null;
        int round = c.challengeState == null ? 1 : c.challengeState.currentRound;
        return new ChallengeBlock(round, ch.start(), ch.end(), e.challengeEndsAt(), endsInText(e),
                periodLabel(ch.start(), ch.end(), e.today), ch.scoring().name(), scoringText(c),
                ch.target() == null ? null : num(ch.target()), ch.stake(), ch.recurrence().name(),
                recurrenceText(ch.recurrence()), mine == null ? 0 : mine.rank(), rows, past, finished);
    }

    public static String scoreText(Cohabit c, double score) {
        if (c.challenge != null && c.challenge.runPoints()) {
            return Texts.number(score) + " P";
        }
        if (c.challenge != null && c.challenge.scoring() != Scoring.MOST_ENTRIES
                && c.tracking.mode() == TrackingMode.VALUE && c.tracking.unit() != null) {
            return Texts.valueText(score, c.tracking.unit());
        }
        return Texts.number(score);
    }

    static String scoringText(Cohabit c) {
        ChallengeConfig ch = c.challenge;
        return switch (ch.scoring()) {
            case MOST_ENTRIES -> "Meiste Einträge";
            case HIGHEST_SUM -> "Höchste Summe";
            case RUN_POINTS -> "Laufpunkte";
            case FIRST_TO_TARGET -> "Zuerst bei " + (ch.target() == null ? "?"
                    : c.tracking.mode() == TrackingMode.VALUE && c.tracking.unit() != null
                    ? Texts.valueText(ch.target(), c.tracking.unit()) : Texts.number(ch.target()));
        };
    }

    static String recurrenceText(Recurrence r) {
        return switch (r) {
            case NONE -> null;
            case WEEKLY -> "startet jede Woche neu";
            case MONTHLY -> "startet jeden Monat neu";
        };
    }

    // MARK: - Abschlussdialog

    static FinishedDialog dialog(CohabitStore.Data data, CohabitEval e, String viewerId) {
        Cohabit c = e.cohabit;
        Member m = e.member(viewerId).member;
        if (c.type == CohabitType.CHALLENGE && c.challengeState != null) {
            ChallengeRound latest = c.challengeState.rounds.stream()
                    .max(Comparator.comparingInt(r -> r.number)).orElse(null);
            if (latest == null || m.dialogsSeen.contains("challenge-" + latest.number)
                    || (m.joinedAt != null && latest.endedAt != null && latest.endedAt.isBefore(m.joinedAt))) {
                return null;
            }
            return challengeDialog(data, c, latest);
        }
        if (c.type == CohabitType.GOAL && c.goalState != null && c.goalState.finishedAt != null) {
            if (m.dialogsSeen.contains("goal")
                    || (m.joinedAt != null && c.goalState.finishedAt.isBefore(m.joinedAt))) {
                return null;
            }
            List<MemberEval> ordered = new ArrayList<>(e.members.values());
            ordered.sort(Comparator.comparing((MemberEval x) -> -x.amount));
            List<FinishedDialog.PodiumEntry> podium = new ArrayList<>();
            int rank = 0;
            double last = Double.NaN;
            for (int i = 0; i < ordered.size() && podium.size() < 3; i++) {
                MemberEval x = ordered.get(i);
                if (x.amount != last) {
                    rank = i + 1;
                    last = x.amount;
                }
                PersonView p = person(data, x.member.personId);
                if (p != null) {
                    podium.add(new FinishedDialog.PodiumEntry(rank, p, num(x.amount), Texts.number(x.amount)));
                }
            }
            boolean reached = goalReached(c, goalTotal(e, e.member(viewerId)));
            String title = (reached ? "Ziel erreicht: „" : "Ziel verfehlt: „") + c.name + "“";
            return new FinishedDialog("goal", "GOAL", title, podium, null, null,
                    c.goalState.messageId == null ? null : "message:" + c.goalState.messageId);
        }
        return null;
    }

    public static FinishedDialog challengeDialog(CohabitStore.Data data, Cohabit c, ChallengeRound round) {
        List<FinishedDialog.PodiumEntry> podium = new ArrayList<>();
        for (Standing s : round.standings) {
            if (podium.size() >= 3) {
                break;
            }
            PersonView p = person(data, s.personId);
            if (p != null) {
                podium.add(new FinishedDialog.PodiumEntry(s.rank, p, num(s.score), scoreText(c, s.score)));
            }
        }
        return new FinishedDialog("challenge-" + round.number, "CHALLENGE", challengeTitle(data, c, round), podium,
                stakeText(data, round), round.nextStart == null ? null
                : "Die nächste Runde startet am " + Texts.dateLong(round.nextStart) + " automatisch.",
                round.messageId == null ? null : "message:" + round.messageId);
    }

    public static String challengeTitle(CohabitStore.Data data, Cohabit c, ChallengeRound round) {
        List<String> winners = round.winners.stream().map(id -> name(data, id)).toList();
        if (winners.isEmpty()) {
            return "„" + c.name + "“ ist beendet";
        }
        return Texts.namesAnd(winners) + (winners.size() == 1 ? " gewinnt „" : " gewinnen „") + c.name + "“";
    }

    public static String stakeText(CohabitStore.Data data, ChallengeRound round) {
        if (round.stake == null || round.stake.isBlank() || round.losers.isEmpty()) {
            return null;
        }
        List<String> losers = round.losers.stream().map(id -> name(data, id)).toList();
        return round.stake + ": " + Texts.namesAnd(losers) + (losers.size() == 1 ? " ist dran." : " sind dran.");
    }

    public static ZoneId zone(Cohabit c) {
        return CohabitEval.zoneOf(c);
    }

    static Person personOrNull(CohabitStore.Data data, String id) {
        return data.person(id).orElse(null);
    }
}
