package com.fherrmann.habits.cohabit.service;

import com.fherrmann.habits.cohabit.api.CohabitRef;
import com.fherrmann.habits.cohabit.api.CohabitSummary;
import com.fherrmann.habits.cohabit.api.NudgeView;
import com.fherrmann.habits.cohabit.api.StatsView;
import com.fherrmann.habits.cohabit.api.TodayView;
import com.fherrmann.habits.cohabit.api.WidgetData;
import com.fherrmann.habits.cohabit.model.Checkin;
import com.fherrmann.habits.cohabit.model.CheckinKind;
import com.fherrmann.habits.cohabit.model.CheckinSource;
import com.fherrmann.habits.cohabit.model.Cohabit;
import com.fherrmann.habits.cohabit.model.CohabitType;
import com.fherrmann.habits.cohabit.model.Event;
import com.fherrmann.habits.cohabit.model.EventKind;
import com.fherrmann.habits.cohabit.model.GoalMode;
import com.fherrmann.habits.cohabit.model.Nudge;
import com.fherrmann.habits.cohabit.model.Person;
import com.fherrmann.habits.cohabit.model.TrackingMode;
import com.fherrmann.habits.cohabit.rules.CohabitEval;
import com.fherrmann.habits.cohabit.rules.CohabitEval.MemberEval;
import com.fherrmann.habits.cohabit.rules.StreakCalc;
import com.fherrmann.habits.cohabit.rules.StreakModel;
import com.fherrmann.habits.cohabit.rules.StreakUnit;
import com.fherrmann.habits.cohabit.rules.Texts;
import com.fherrmann.habits.cohabit.store.CohabitStore;
import com.fherrmann.habits.security.Viewer;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Heute, Statistik und Widget - die Uebersichten ueber alle eigenen Co-Habits. */
@Service
public class OverviewService {

    /** Die Vorgabezone fuer "heute", wo kein einzelnes Co-Habit gemeint ist. */
    static final ZoneId HOME = ZoneId.of("Europe/Berlin");
    static final int PHOTO_PREVIEWS = 3;

    private final CohabitStore store;
    private final ViewService views;

    public OverviewService(CohabitStore store, ViewService views) {
        this.store = store;
        this.views = views;
    }

    private record Evaluated(Cohabit cohabit, CohabitEval eval, CohabitSummary summary) {
    }

    private List<Evaluated> evaluate(CohabitStore.Data data, String me, ViewService.Facts facts, Instant now) {
        List<Evaluated> list = new ArrayList<>();
        for (Cohabit c : ViewService.mine(data, me, false)) {
            CohabitEval e = views.evaluate(data, c, facts, now);
            list.add(new Evaluated(c, e, Views.summary(data, e, me)));
        }
        list.sort(Comparator.comparing(Evaluated::summary, ViewService.order()));
        return list;
    }

    // MARK: - Heute

    public TodayView today(Viewer viewer) {
        String me = viewer.personId();
        ViewService.Facts facts = views.factsForViewer(me);
        Instant now = views.now();
        return store.read(data -> {
            PeopleService.requirePerson(data, me);
            List<Evaluated> all = evaluate(data, me, facts, now);
            List<CohabitSummary> summaries = all.stream().map(Evaluated::summary).toList();
            int open = (int) summaries.stream().filter(s -> s.status().equals("OPEN")).count();
            String headline = open == 0 ? "Alles erledigt"
                    : "Noch " + open + " " + Texts.plural(open, "Haken", "Haken") + " offen";
            LocalDate today = LocalDate.ofInstant(now, HOME);
            return new TodayView(today, open, headline, nudges(data, me, now), newPhotos(data, me, now),
                    CohabitService.invitationsOf(data, me, now), summaries);
        });
    }

    /** Ungesehene Stupser von heute - als Banner mit "Zurueckstupsen". */
    static List<NudgeView> nudges(CohabitStore.Data data, String me, Instant now) {
        Set<String> blocked = data.person(me).map(p -> Set.copyOf(p.blocked)).orElse(Set.of());
        List<NudgeView> list = new ArrayList<>();
        for (Nudge n : data.events().nudges) {
            if (!n.toId.equals(me) || n.seenAt != null || blocked.contains(n.fromId)) {
                continue;
            }
            Cohabit c = data.cohabit(n.cohabitId).orElse(null);
            if (c == null || !c.isMember(me) || !n.day.equals(LocalDate.ofInstant(now, CohabitEval.zoneOf(c)))) {
                continue;
            }
            list.add(nudgeView(data, n, c));
        }
        list.sort(Comparator.comparing(NudgeView::createdAt).reversed());
        return list;
    }

    public static NudgeView nudgeView(CohabitStore.Data data, Nudge n, Cohabit c) {
        return new NudgeView(n.id, Views.person(data, n.fromId), CohabitRef.of(c), n.text, n.createdAt);
    }

    /**
     * Beweisfotos der anderen seit dem letzten Blick in die Timeline. Wer noch nie
     * hineingeschaut hat, bekommt die der letzten drei Tage.
     */
    static TodayView.NewPhotos newPhotos(CohabitStore.Data data, String me, Instant now) {
        String seen = data.events().timelineSeen.get(me);
        Set<String> blocked = data.person(me).map(p -> Set.copyOf(p.blocked)).orElse(Set.of());
        List<Event> events = data.events().events;
        int from = 0;
        if (seen != null) {
            for (int i = 0; i < events.size(); i++) {
                if (events.get(i).id.equals(seen)) {
                    from = i + 1;
                    break;
                }
            }
        }
        Instant cutoff = seen == null ? now.minus(3, ChronoUnit.DAYS) : Instant.MIN;
        List<String> photos = new ArrayList<>();
        for (int i = events.size() - 1; i >= from; i--) {
            Event e = events.get(i);
            if (e.kind != EventKind.PHOTO_CHECKIN || me.equals(e.personId) || e.photoId == null
                    || blocked.contains(e.personId) || e.at.isBefore(cutoff)) {
                continue;
            }
            Cohabit c = data.cohabit(e.cohabitId).orElse(null);
            if (c != null && c.isMember(me)) {
                photos.add(e.photoId);
            }
        }
        return new TodayView.NewPhotos(photos.size(), photos.stream().limit(PHOTO_PREVIEWS).toList());
    }

    // MARK: - Widget

    public WidgetData widget(Viewer viewer) {
        String me = viewer.personId();
        ViewService.Facts facts = views.factsForViewer(me);
        Instant now = views.now();
        return store.read(data -> {
            List<Evaluated> all = evaluate(data, me, facts, now);
            List<WidgetData.Item> items = new ArrayList<>();
            int open = 0;
            WidgetData.Challenge challenge = null;
            WidgetData.TeamGoal teamGoal = null;
            WidgetData.OpenStreak openStreak = null;
            List<Evaluated> byEnd = new ArrayList<>(all);
            byEnd.sort(Comparator.comparing(x -> x.cohabit.challenge == null ? LocalDate.MAX : x.cohabit.challenge.end()));
            for (Evaluated x : byEnd) {
                if (challenge == null && x.cohabit.type == CohabitType.CHALLENGE && x.eval.challengeRunning()) {
                    challenge = widgetChallenge(data, x, me);
                }
            }
            for (Evaluated x : all) {
                CohabitSummary s = x.summary;
                if (s.status().equals("OPEN")) {
                    open++;
                }
                items.add(new WidgetData.Item(s.ref(), s.headline().value(), s.headline().unit(), widgetSub(x, me),
                        s.status(), statusText(s), s.photoRequired(), quickCheckIn(x.cohabit, s)));
                if (teamGoal == null && x.cohabit.type == CohabitType.GOAL && x.cohabit.goal.mode() == GoalMode.TEAM
                        && !x.eval.today.isAfter(x.cohabit.goal.deadline())) {
                    teamGoal = new WidgetData.TeamGoal(s.ref(),
                            Views.goalPercent(x.eval.goalTotal, x.cohabit.goal.target()));
                }
                if (openStreak == null && x.cohabit.type == CohabitType.STREAK && s.status().equals("OPEN")) {
                    openStreak = new WidgetData.OpenStreak(s.ref(), s.ref().name() + " · " + s.headline().value()
                            + " " + s.headline().unit());
                }
            }
            return new WidgetData(now, open, items, challenge, teamGoal, openStreak);
        });
    }

    static String statusText(CohabitSummary s) {
        return switch (s.status()) {
            case "OPEN" -> "offen";
            case "UNAVAILABLE" -> "–";
            default -> s.headline().shortText();
        };
    }

    /** CHECK-Modus, ohne Foto, ohne Wert, heute offen - dann hakt das Widget direkt ab. */
    static boolean quickCheckIn(Cohabit c, CohabitSummary s) {
        return c.type != CohabitType.ABSTINENCE && c.tracking.mode() == TrackingMode.CHECK && !c.photoRequired
                && s.status().equals("OPEN") && s.canCheckIn();
    }

    /** "Wochen · 2/3", "Tage · Rekord 41", "Platz 2 · endet heute". */
    static String widgetSub(Evaluated x, String me) {
        Cohabit c = x.cohabit;
        CohabitSummary s = x.summary;
        MemberEval m = x.eval.member(me);
        return switch (c.type) {
            case STREAK -> {
                if (m.unavailable != null) {
                    yield m.unavailable;
                }
                boolean dayBased = Views.isDayBased(c);
                String detail = !dayBased && s.progress() != null
                        ? s.progress().done() + "/" + s.progress().goal()
                        : Views.remainingText(x.eval, m);
                // Die Einheit der Kopfzahl, nicht stur die Mehrzahl: das Widget zeigt
                // "1" gross und darunter "Tag · …", nicht "Tage".
                yield s.headline().unit() + " · " + detail;
            }
            case ABSTINENCE -> s.headline().unit() + " · Rekord " + m.abstinence.record();
            case GOAL -> (c.goal.mode() == GoalMode.TEAM ? "Teamziel" : "Einzelziel") + " · "
                    + Texts.number(Views.goalTotal(x.eval, m));
            case CHALLENGE -> {
                CohabitEval.Place p = x.eval.placeOf(me);
                yield Views.joinNonNull(" · ", p == null ? null : "Platz " + p.rank(), Views.endsShort(x.eval));
            }
        };
    }

    private static WidgetData.Challenge widgetChallenge(CohabitStore.Data data, Evaluated x, String me) {
        List<WidgetData.Row> rows = new ArrayList<>();
        for (CohabitEval.Place p : x.eval.leaderboard) {
            if (rows.size() >= 3) {
                break;
            }
            rows.add(new WidgetData.Row(p.rank(), Views.name(data, p.personId()), Views.num(p.score()),
                    p.personId().equals(me)));
        }
        CohabitEval.Place mine = x.eval.placeOf(me);
        return new WidgetData.Challenge(x.summary.ref(), Views.endsShort(x.eval), mine == null ? 0 : mine.rank(), rows);
    }

    // MARK: - Statistik

    public StatsView stats(Viewer viewer, String range, LocalDate anchor) {
        String me = viewer.personId();
        String r = range == null ? "MONTH" : range.trim().toUpperCase(java.util.Locale.ROOT);
        if (!r.equals("WEEK") && !r.equals("MONTH") && !r.equals("YEAR")) {
            throw Errors.badRequest("Zeitraum muss WEEK, MONTH oder YEAR sein.");
        }
        ViewService.Facts facts = views.factsForViewer(me);
        Instant now = views.now();
        LocalDate today = LocalDate.ofInstant(now, HOME);
        LocalDate a = anchor == null ? today : anchor;
        LocalDate from = switch (r) {
            case "WEEK" -> a.with(java.time.DayOfWeek.MONDAY);
            case "MONTH" -> a.withDayOfMonth(1);
            default -> a.withDayOfYear(1);
        };
        LocalDate to = switch (r) {
            case "WEEK" -> from.plusDays(6);
            case "MONTH" -> a.with(TemporalAdjusters.lastDayOfMonth());
            default -> a.with(TemporalAdjusters.lastDayOfYear());
        };
        String label = switch (r) {
            case "WEEK" -> "KW " + from.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
            case "MONTH" -> Texts.month(from) + (from.getYear() == today.getYear() ? "" : " " + from.getYear());
            default -> String.valueOf(from.getYear());
        };
        return store.read(data -> {
            List<Evaluated> all = evaluate(data, me, facts, now);
            StreakCalc.Rate total = new StreakCalc.Rate(0, 0);
            StatsView.LongestStreak longest = null;
            int longestDays = 0;
            List<StatsView.CohabitProgress> progress = new ArrayList<>();
            Map<LocalDate, Integer> counts = new HashMap<>();
            for (Evaluated x : all) {
                Cohabit c = x.cohabit;
                CohabitEval e = x.eval;
                MemberEval m = e.member(me);
                for (Checkin ch : e.checkins) {
                    if (ch.personId.equals(me) && ch.kind == CheckinKind.DONE && ch.source != CheckinSource.HEALTH
                            && !ch.date.isBefore(from) && !ch.date.isAfter(to)) {
                        counts.merge(ch.date, 1, Integer::sum);
                    }
                }
                switch (c.type) {
                    case STREAK -> {
                        if (m.unavailable != null) {
                            continue;
                        }
                        StreakCalc.Rate rate = StreakCalc.rate(StreakModel.scheme(c), m.judge, m.start, e.today, from, to);
                        total = total.plus(rate);
                        progress.add(new StatsView.CohabitProgress(CohabitRef.of(c),
                                rate.fulfilled() + "/" + rate.due(), Views.fraction(rate.fulfilled(), rate.due())));
                        StreakUnit unit = StreakModel.scheme(c).unit();
                        int days = m.streak.current() * unit.approxDays(StreakModel.intervalDays(StreakModel.rhythm(c)));
                        if (m.streak.current() > 0 && days > longestDays) {
                            longestDays = days;
                            longest = new StatsView.LongestStreak(m.streak.current() + " " + unit.abbreviation(),
                                    CohabitRef.of(c));
                        }
                    }
                    case ABSTINENCE -> {
                        LocalDate start = from.isBefore(m.start) ? m.start : from;
                        LocalDate end = to.isAfter(e.today) ? e.today : to;
                        int days = 0;
                        int clean = 0;
                        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                            days++;
                            if (!m.breakDays.contains(d)) {
                                clean++;
                            }
                        }
                        total = total.plus(new StreakCalc.Rate(clean, days));
                        progress.add(new StatsView.CohabitProgress(CohabitRef.of(c), clean + " T",
                                Views.fraction(clean, days)));
                        int current = m.abstinence.current();
                        if (current > longestDays) {
                            longestDays = current;
                            longest = new StatsView.LongestStreak(current + " T", CohabitRef.of(c));
                        }
                    }
                    case GOAL -> {
                        int pct = Views.goalPercent(Views.goalTotal(e, m), c.goal.target());
                        progress.add(new StatsView.CohabitProgress(CohabitRef.of(c), pct + "%",
                                Views.fraction(pct, 100)));
                    }
                    case CHALLENGE -> {
                        CohabitEval.Place p = e.placeOf(me);
                        double top = e.leaderboard.isEmpty() ? 0 : e.leaderboard.getFirst().score();
                        progress.add(new StatsView.CohabitProgress(CohabitRef.of(c), p == null ? "–" : "#" + p.rank(),
                                p == null ? 0 : Views.fraction(p.score(), top)));
                    }
                }
            }
            return new StatsView(r, label, total.percent(), longest, heatmap(from, to, counts), progress);
        });
    }

    /** Stufe 0 ohne Eintrag, sonst 1-4 nach den Quartilen der Tage mit Eintraegen im Zeitraum. */
    static StatsView.Heatmap heatmap(LocalDate from, LocalDate to, Map<LocalDate, Integer> counts) {
        List<Integer> nonZero = counts.values().stream().filter(v -> v > 0).sorted().toList();
        int q1 = quantile(nonZero, 0.25);
        int q2 = quantile(nonZero, 0.5);
        int q3 = quantile(nonZero, 0.75);
        List<StatsView.Day> days = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            int count = counts.getOrDefault(d, 0);
            int level = count == 0 ? 0 : 1 + (count > q1 ? 1 : 0) + (count > q2 ? 1 : 0) + (count > q3 ? 1 : 0);
            days.add(new StatsView.Day(d, count, level));
        }
        return new StatsView.Heatmap(from, to, days);
    }

    static int quantile(List<Integer> sorted, double q) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(q * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    static Person person(CohabitStore.Data data, String id) {
        return data.person(id).orElse(null);
    }
}
