package com.fherrmann.habits.cohabit.service;

import com.fherrmann.habits.cohabit.api.ClassicHabit;
import com.fherrmann.habits.cohabit.api.ClassicKind;
import com.fherrmann.habits.cohabit.api.CohabitDetail;
import com.fherrmann.habits.cohabit.api.CohabitSummary;
import com.fherrmann.habits.cohabit.model.AbstinenceConfig;
import com.fherrmann.habits.cohabit.model.AutoConfig;
import com.fherrmann.habits.cohabit.model.AutoSource;
import com.fherrmann.habits.cohabit.model.CheckinKind;
import com.fherrmann.habits.cohabit.model.CheckinSource;
import com.fherrmann.habits.cohabit.model.Cohabit;
import com.fherrmann.habits.cohabit.model.CohabitType;
import com.fherrmann.habits.cohabit.model.FocusPeriod;
import com.fherrmann.habits.cohabit.model.Palette;
import com.fherrmann.habits.cohabit.model.Rhythm;
import com.fherrmann.habits.cohabit.model.Role;
import com.fherrmann.habits.cohabit.model.StreakConfig;
import com.fherrmann.habits.cohabit.model.Tracking;
import com.fherrmann.habits.cohabit.rules.CohabitEval;
import com.fherrmann.habits.cohabit.rules.CohabitEval.MemberEval;
import com.fherrmann.habits.cohabit.rules.Judge;
import com.fherrmann.habits.cohabit.rules.PeriodScheme;
import com.fherrmann.habits.cohabit.rules.StreakCalc;
import com.fherrmann.habits.cohabit.rules.StreakModel;
import com.fherrmann.habits.cohabit.rules.StreakUnit;
import com.fherrmann.habits.cohabit.store.CohabitStore;
import com.fherrmann.habits.legacy.HabitKind;
import com.fherrmann.habits.legacy.Period;
import com.fherrmann.habits.security.Viewer;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Die klassische Liste: Felix' alte Habit-Ansicht aus der Fokus-App, in coHabit
 * per Schalter zurueck (iOS). Liefert alle aktiven Co-Habits der Person - auch
 * geteilte, seit 01.10. auch Ziele und Challenges (Felix: "alles, was die neue Liste
 * zeigt") - in der Form der alten API, gerechnet mit den Regeln von coHabit.
 * Die Eigenheiten der alten Antwort bleiben: sieben Punkte, die markierten Tage
 * der letzten 31 Tage, und die Schritte-Woche gilt nie als gefaehrdet.
 *
 * <p>Abhaken, Anlegen, Aendern und Loeschen laufen ueber die normalen Dienste -
 * Nachtragsfrist, Foto-Pflicht, Timeline und Push gelten also genauso wie in der
 * neuen Ansicht.
 */
@Service
public class ClassicService {

    static final int RECENT = 7;
    static final int MARKED_DAYS = 31;
    /** Neu angelegte Habits bekommen die 14 Tage Nachtragen der alten App. */
    static final int BACKFILL_HOURS = 336;

    /**
     * Was die alte App beim Anlegen und Bearbeiten schickte ({@code HabitDraft}), dazu seit
     * 01.10. Kategorie und Zeitraum fuer Fokus-Habits. {@code focusCategoryId} weglassen
     * heisst beim Bearbeiten "unveraendert", {@code ""} heisst "alle Baeume" - ein aelterer
     * Editor, der das Feld nicht kennt, setzt so nichts zurueck.
     */
    public record Draft(String name, HabitKind kind, Integer weeklyStepGoal, Integer focusMinutesGoal,
                        Period period, Integer timesPerPeriod, String focusCategoryId, FocusPeriod focusPeriod) {
    }

    /**
     * Ein Haken bzw. Rueckfall. Die {@code id} vergibt die App, damit ein
     * Nachsenden aus dem Postausgang keinen zweiten Eintrag anlegt.
     */
    public record Mark(LocalDate date, String id) {
    }

    private final CohabitStore store;
    private final ViewService views;
    private final CheckinService checkins;
    private final CohabitService cohabits;

    public ClassicService(CohabitStore store, ViewService views, CheckinService checkins, CohabitService cohabits) {
        this.store = store;
        this.views = views;
        this.checkins = checkins;
        this.cohabits = cohabits;
    }

    // MARK: - Lesen

    /** Alle aktiven Co-Habits der Person in Anlegereihenfolge - sortiert wird in der App. */
    public List<ClassicHabit> list(Viewer viewer) {
        String me = viewer.personId();
        ViewService.Facts facts = views.factsForViewer(me);
        Instant now = views.now();
        return store.read(data -> {
            List<ClassicHabit> list = new ArrayList<>();
            for (Cohabit c : ViewService.mine(data, me, false)) {
                CohabitEval e = views.evaluate(data, c, facts, now);
                list.add(isClassic(c) ? habit(e, me) : goalOrChallenge(data, e, me));
            }
            return list;
        });
    }

    public ClassicHabit one(String me, String cohabitId) {
        ViewService.Facts facts = views.factsForCohabit(cohabitId);
        Instant now = views.now();
        return store.read(data -> {
            Cohabit c = classic(data, cohabitId, me);
            return habit(views.evaluate(data, c, facts, now), me);
        });
    }

    static boolean isClassic(Cohabit c) {
        return c.type == CohabitType.STREAK || c.type == CohabitType.ABSTINENCE;
    }

    private static Cohabit classic(CohabitStore.Data data, String cohabitId, String me) {
        Cohabit c = ViewService.visible(data, cohabitId, me);
        if (!isClassic(c)) {
            throw Errors.badRequest("Ziele und Challenges trägst du im Co-Habit ein.");
        }
        return c;
    }

    /** Der Stand einer Person in der Form der alten API. */
    public static ClassicHabit habit(CohabitEval e, String me) {
        Cohabit c = e.cohabit;
        MemberEval m = e.member(me);
        LocalDate today = e.today;
        boolean shared = c.members.size() > 1;
        boolean admin = m.member.role == Role.ADMIN;
        LocalDate backfillFrom = Views.backfillFrom(e, m);
        if (c.type == CohabitType.ABSTINENCE) {
            // Wie "Lassen" frueher: ein Tag zaehlt, wenn er nach dem Start liegt und keinen
            // Rueckfall hat - heute eingeschlossen, gefaehrdet ist hier nie etwas.
            List<Boolean> recent = new ArrayList<>();
            for (int i = RECENT - 1; i >= 0; i--) {
                LocalDate day = today.minusDays(i);
                recent.add(!day.isBefore(m.start) && !m.breakDays.contains(day));
            }
            boolean doneToday = !today.isBefore(m.start) && !m.breakDays.contains(today);
            return new ClassicHabit(c.id, c.name, ClassicKind.QUIT, StreakUnit.DAYS, null,
                    m.abstinence.current(), doneToday, false, null, recent, null, null, null, null,
                    marked(m.breakDays, today), m.start, false, shared, admin, backfillFrom, null, null);
        }

        PeriodScheme scheme = StreakModel.scheme(c);
        StreakCalc.Outcome streak = m.streak;
        ClassicKind kind = kindOf(c);
        Integer stepGoal = null;
        Integer focusGoal = null;
        Period period = null;
        Integer times = null;
        ClassicHabit.Progress progress = null;
        ClassicHabit.Focus focus = null;
        boolean atRisk = streak.atRisk();
        if (c.auto == null) {
            Rhythm rhythm = StreakModel.rhythm(c);
            switch (rhythm.kind()) {
                case DAILY -> period = Period.DAY;
                case TIMES_PER_WEEK -> period = Period.WEEK;
                case TIMES_PER_MONTH -> period = Period.MONTH;
                default -> period = null;
            }
            if (period == Period.WEEK || period == Period.MONTH) {
                times = StreakModel.times(rhythm);
                progress = new ClassicHabit.Progress(streak.currentAchieved(), required(streak, times));
            }
        } else {
            switch (c.auto.source()) {
                case FOOD -> progress = m.unavailable != null ? null
                        : new ClassicHabit.Progress(m.facts.todayValue(), m.facts.todayGoal());
                case FOOD_TARGET_WEEKLY -> {
                    // Der Schnitt der getrackten Tage dieser Woche gegen das Ziel; entschieden
                    // wird nach Sonntag, gefaehrdet ist hier nichts.
                    progress = m.unavailable != null ? null
                            : new ClassicHabit.Progress(m.facts.todayValue(), m.facts.todayGoal());
                    atRisk = false;
                }
                case STEPS_WEEKLY -> {
                    stepGoal = c.auto.stepGoal();
                    progress = m.unavailable != null ? null
                            : new ClassicHabit.Progress(streak.currentAchieved(), required(streak, stepGoal));
                    // Die alte App meldete die Schritte-Woche nie als gefaehrdet - sonst stuende
                    // jede Woche bis zum Ziel in Orange da.
                    atRisk = false;
                }
                case FOCUS -> {
                    focusGoal = c.auto.focusGoal();
                    focus = new ClassicHabit.Focus(c.auto.focusCategoryId(), c.auto.focusCategoryName(),
                            c.auto.focusWeekly() ? FocusPeriod.WEEK : FocusPeriod.DAY);
                    if (c.auto.focusWeekly()) {
                        // Wie die Schritte: der Stand der Woche, und gefaehrdet ist eine Woche nie.
                        progress = m.unavailable != null ? null
                                : new ClassicHabit.Progress(streak.currentAchieved(), required(streak, focusGoal));
                        atRisk = false;
                    } else {
                        progress = m.unavailable != null ? null
                                : new ClassicHabit.Progress(m.facts.todayValue(), m.facts.todayGoal());
                    }
                }
            }
        }
        if (m.unavailable != null) {
            return new ClassicHabit(c.id, c.name, kind, scheme.unit(), stepGoal, 0, false, false, null, List.of(),
                    m.unavailable, focusGoal, period, times, List.of(), m.start, c.photoRequired, shared, admin,
                    backfillFrom, null, focus);
        }
        List<LocalDate> markedDays = c.auto == null ? marked(m.doneDays, today) : List.of();
        return new ClassicHabit(c.id, c.name, kind, scheme.unit(), stepGoal, streak.current(), m.doneToday, atRisk,
                progress, recent(scheme, m.judge, today), null, focusGoal, period, times, markedDays, m.start,
                c.photoRequired, shared, admin, backfillFrom, null, focus);
    }

    /**
     * Ziele und Challenges: die alten Felder neutral, dazu die Zusammenfassung der
     * neuen Liste - die Zeile zeigt Kennzahl, Text und Fortschritt daraus, und
     * eingetragen wird wie in der neuen Ansicht (Wert, +1, Beweisfoto).
     */
    static ClassicHabit goalOrChallenge(CohabitStore.Data data, CohabitEval e, String me) {
        Cohabit c = e.cohabit;
        MemberEval m = e.member(me);
        CohabitSummary summary = Views.summary(data, e, me);
        // Die sieben Punkte wie bei "Track food": Tage mit eigenem Eintrag - so sieht man in
        // der Zeile, dass heute schon eingetragen ist (Felix, 01.10.).
        List<Boolean> recent = new ArrayList<>();
        for (int i = RECENT - 1; i >= 0; i--) {
            recent.add(m.doneDays.contains(e.today.minusDays(i)));
        }
        return new ClassicHabit(c.id, c.name, c.type == CohabitType.GOAL ? ClassicKind.GOAL : ClassicKind.CHALLENGE,
                StreakUnit.DAYS, null, 0, m.entryToday, false, null, recent, summary.unavailableText(), null,
                null, null, List.of(), m.start, c.photoRequired, c.members.size() > 1,
                m.member.role == Role.ADMIN, Views.backfillFrom(e, m), summary, null);
    }

    static ClassicKind kindOf(Cohabit c) {
        switch (c.type) {
            case ABSTINENCE -> {
                return ClassicKind.QUIT;
            }
            case GOAL -> {
                return ClassicKind.GOAL;
            }
            case CHALLENGE -> {
                return ClassicKind.CHALLENGE;
            }
            default -> {
                if (c.auto == null) {
                    return ClassicKind.BUILD;
                }
                return switch (c.auto.source()) {
                    // Im Kalorienziel zeigt die Zeile wie "Track food" - kcal gegen Ziel, in Wochen.
                    case FOOD, FOOD_TARGET_WEEKLY -> ClassicKind.FOOD;
                    case STEPS_WEEKLY -> ClassicKind.STEPS;
                    case FOCUS -> ClassicKind.FOCUS;
                };
            }
        }
    }

    /** Was der laufende Zeitraum verlangt - mit Pausen anteilig weniger, ganz pausiert der volle Wert. */
    private static int required(StreakCalc.Outcome streak, int full) {
        return streak.currentRequired() > 0 ? streak.currentRequired() : full;
    }

    /** Die letzten sieben Zeitraeume, aelteste zuerst: erfuellt, wenn faellig und geschafft. */
    static List<Boolean> recent(PeriodScheme scheme, Judge judge, LocalDate today) {
        List<LocalDate> starts = new ArrayList<>();
        LocalDate start = scheme.startOf(today);
        for (int i = 0; i < RECENT; i++) {
            starts.addFirst(start);
            start = scheme.previous(start);
        }
        List<Boolean> recent = new ArrayList<>();
        for (LocalDate s : starts) {
            LocalDate end = scheme.endOf(s);
            int required = judge.required(s, end);
            recent.add(required > 0 && judge.achieved(s, end) >= required);
        }
        return recent;
    }

    static List<LocalDate> marked(Collection<LocalDate> days, LocalDate today) {
        LocalDate from = today.minusDays(MARKED_DAYS - 1);
        return days.stream().filter(d -> !d.isBefore(from) && !d.isAfter(today)).sorted().toList();
    }

    // MARK: - Abhaken

    /** BUILD: Haken, QUIT: Rueckfall - mit denselben Pruefungen wie jeder Eintrag. */
    public ClassicHabit mark(Viewer viewer, String cohabitId, Mark mark) {
        String me = viewer.personId();
        store.read(data -> classic(data, cohabitId, me));
        checkins.create(viewer, cohabitId, new CheckinService.CheckinInput(
                mark == null ? null : mark.id(), null, mark == null ? null : mark.date(), null, null, null, null));
        return one(me, cohabitId);
    }

    /** Nimmt den eigenen Eintrag des Tages zurueck. Gibt es keinen, bleibt alles, wie es ist. */
    public ClassicHabit unmark(Viewer viewer, String cohabitId, LocalDate date) {
        String me = viewer.personId();
        String checkinId = store.read(data -> {
            Cohabit c = classic(data, cohabitId, me);
            CheckinKind kind = c.type == CohabitType.ABSTINENCE ? CheckinKind.BREAK : CheckinKind.DONE;
            return data.checkins(c.id).stream()
                    .filter(ch -> ch.personId.equals(me) && ch.kind == kind && ch.date.equals(date)
                            && ch.source != CheckinSource.HEALTH)
                    .map(ch -> ch.id)
                    .findFirst()
                    .orElse(null);
        });
        if (checkinId != null) {
            checkins.delete(viewer, cohabitId, checkinId);
        }
        return one(me, cohabitId);
    }

    // MARK: - Anlegen, aendern, loeschen

    /** Ein Co-Habit fuer die Person allein - Einstellungen wie in der alten App. */
    public ClassicHabit create(Viewer viewer, Draft draft) {
        if (draft == null || draft.kind() == null) {
            throw Errors.badRequest("Welche Art soll es sein?");
        }
        String me = viewer.personId();
        int index = store.read(data -> ViewService.mine(data, me, false).size());
        CohabitType type = draft.kind() == HabitKind.QUIT ? CohabitType.ABSTINENCE : CohabitType.STREAK;
        StreakConfig streak = null;
        AbstinenceConfig abstinence = null;
        AutoConfig auto = null;
        switch (draft.kind()) {
            case BUILD -> streak = new StreakConfig(rhythm(draft.period(), draft.timesPerPeriod()), false);
            case QUIT -> abstinence = new AbstinenceConfig(false);
            case FOOD -> auto = new AutoConfig(AutoSource.FOOD, null, null);
            case STEPS -> auto = new AutoConfig(AutoSource.STEPS_WEEKLY, draft.weeklyStepGoal(), null);
            case FOCUS -> auto = new AutoConfig(AutoSource.FOCUS, null, draft.focusMinutesGoal(),
                    blankToNull(draft.focusCategoryId()), null,
                    draft.focusPeriod() == null ? FocusPeriod.DAY : draft.focusPeriod());
        }
        CohabitInput input = new CohabitInput(type, draft.name(), Palette.roundRobin(index), "Europe/Berlin",
                Tracking.CHECK, false, BACKFILL_HOURS, null, false, streak, abstinence, null, null, null, auto,
                List.of());
        CohabitDetail created = cohabits.create(viewer, input);
        return one(me, created.summary().ref().id());
    }

    /**
     * Name, Rhythmus (nur wenn mitgeschickt) und Ziele. Alles andere - Farbe,
     * Erinnerung, Foto-Pflicht, Nachtragsfrist, Health - bleibt, wie es ist: das
     * alte Formular kennt es nicht und darf es darum auch nicht zuruecksetzen.
     */
    public ClassicHabit update(Viewer viewer, String cohabitId, Draft draft) {
        if (draft == null) {
            throw Errors.badRequest("Die Einstellungen fehlen.");
        }
        String me = viewer.personId();
        CohabitInput input = store.read(data -> {
            Cohabit c = classic(data, cohabitId, me);
            if (draft.kind() != null && !draft.kind().name().equals(kindOf(c).name())) {
                throw Errors.badRequest("Die Art lässt sich nicht ändern.");
            }
            StreakConfig streak = c.streak;
            if (c.type == CohabitType.STREAK && c.auto == null && draft.period() != null) {
                streak = new StreakConfig(rhythm(draft.period(), draft.timesPerPeriod()),
                        c.streak != null && c.streak.groupStreak());
            }
            AutoConfig auto = c.auto == null ? null : new AutoConfig(c.auto.source(),
                    draft.weeklyStepGoal() != null ? draft.weeklyStepGoal() : c.auto.weeklyStepGoal(),
                    draft.focusMinutesGoal() != null ? draft.focusMinutesGoal() : c.auto.focusMinutesGoal(),
                    draft.focusCategoryId() == null ? c.auto.focusCategoryId() : blankToNull(draft.focusCategoryId()),
                    c.auto.focusCategoryName(),
                    draft.focusPeriod() != null ? draft.focusPeriod() : c.auto.focusPeriod());
            return new CohabitInput(c.type, draft.name() == null ? c.name : draft.name(), c.color, c.timezone,
                    c.tracking, c.photoRequired, c.backfillHours, c.reminderTime, c.membersCanInvite, streak,
                    c.abstinence, null, null, c.health, auto, null);
        });
        cohabits.update(viewer, cohabitId, input);
        return one(me, cohabitId);
    }

    /**
     * Allein: weg samt Eintraegen. Geteilt: nur die Person geht, die anderen behalten
     * es. Gilt fuer jede Art - auch eine Challenge laesst sich aus der Liste verlassen.
     */
    public void delete(Viewer viewer, String cohabitId) {
        String me = viewer.personId();
        store.read(data -> ViewService.visible(data, cohabitId, me));
        cohabits.removeMember(viewer, cohabitId, "me");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static Rhythm rhythm(Period period, Integer times) {
        int n = times == null ? 1 : times;
        if (period == null) {
            return Rhythm.daily();
        }
        return switch (period) {
            case DAY -> Rhythm.daily();
            case WEEK -> Rhythm.timesPerWeek(n);
            case MONTH -> Rhythm.timesPerMonth(n);
        };
    }
}
