package com.fherrmann.habits.cohabit.service;

import com.fherrmann.habits.cohabit.model.AbstinenceConfig;
import com.fherrmann.habits.cohabit.model.AutoConfig;
import com.fherrmann.habits.cohabit.model.AutoSource;
import com.fherrmann.habits.cohabit.model.ChallengeConfig;
import com.fherrmann.habits.cohabit.model.Cohabit;
import com.fherrmann.habits.cohabit.model.CohabitType;
import com.fherrmann.habits.cohabit.model.GoalConfig;
import com.fherrmann.habits.cohabit.model.GoalCounting;
import com.fherrmann.habits.cohabit.model.GoalMode;
import com.fherrmann.habits.cohabit.model.HealthConfig;
import com.fherrmann.habits.cohabit.model.Palette;
import com.fherrmann.habits.cohabit.model.Recurrence;
import com.fherrmann.habits.cohabit.model.Rhythm;
import com.fherrmann.habits.cohabit.model.RhythmKind;
import com.fherrmann.habits.cohabit.model.Scoring;
import com.fherrmann.habits.cohabit.model.StreakConfig;
import com.fherrmann.habits.cohabit.model.Tracking;
import com.fherrmann.habits.cohabit.model.TrackingMode;
import com.fherrmann.habits.cohabit.rules.Texts;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Prueft die Einstellungen eines Co-Habits und bringt sie in Form. Was nicht zum
 * Typ passt, faellt weg; was fehlt, bekommt die Vorgabe.
 */
public final class CohabitConfigs {

    public static final Set<Integer> BACKFILL_HOURS = Set.of(0, 24, 48, 72, 168, 336);
    static final int DEFAULT_BACKFILL = 48;
    static final int MAX_NAME = 40;
    static final int MAX_STAKE = 80;
    static final int MAX_STEP_GOAL = 500_000;
    static final int MAX_FOCUS_MINUTES = 24 * 60;
    private static final Pattern TIME = Pattern.compile("([01]\\d|2[0-3]):[0-5]\\d");

    private CohabitConfigs() {
    }

    /**
     * Uebernimmt die Eingabe in {@code c}.
     *
     * @param existing true beim Bearbeiten - dann bleibt der Typ, der Start des Ziels und
     *                 der Start einer laufenden Challenge-Runde, wie er ist
     * @param sources  welche automatischen Quellen die Person hat (nur beim Anlegen geprueft)
     */
    public static void apply(Cohabit c, CohabitInput in, boolean existing, LocalDate today, List<AutoSource> sources) {
        if (in == null) {
            throw Errors.badRequest("Die Einstellungen fehlen.");
        }
        if (existing) {
            if (in.type() != null && in.type() != c.type) {
                throw Errors.badRequest("Der Typ lässt sich nicht ändern.");
            }
        } else {
            if (in.type() == null) {
                throw Errors.badRequest("Welcher Typ soll es sein?");
            }
            c.type = in.type();
        }
        String name = in.name() == null ? "" : in.name().trim().replaceAll("\\s+", " ");
        if (name.isEmpty() || name.length() > MAX_NAME) {
            throw Errors.badRequest("Der Name braucht 1 bis 40 Zeichen.");
        }
        c.name = name;
        String color = in.color() == null ? (existing ? c.color : Palette.KEYS.getFirst()) : in.color();
        if (!Palette.isValid(color)) {
            throw Errors.badRequest("Unbekannte Farbe.");
        }
        c.color = color;
        String zone = in.timezone() == null || in.timezone().isBlank() ? "Europe/Berlin" : in.timezone().trim();
        try {
            ZoneId.of(zone);
        } catch (DateTimeException e) {
            throw Errors.badRequest("Unbekannte Zeitzone.");
        }
        c.timezone = zone;
        int backfill = in.backfillHours() == null ? (existing ? c.backfillHours : DEFAULT_BACKFILL) : in.backfillHours();
        if (!BACKFILL_HOURS.contains(backfill)) {
            throw Errors.badRequest("Die Nachtragsfrist muss 0, 24, 48, 72, 168 oder 336 Stunden sein.");
        }
        c.backfillHours = backfill;
        String reminder = in.reminderTime() == null || in.reminderTime().isBlank() ? null : in.reminderTime().trim();
        if (reminder != null && !TIME.matcher(reminder).matches()) {
            throw Errors.badRequest("Die Erinnerung braucht eine Uhrzeit wie 07:30.");
        }
        c.reminderTime = reminder;
        c.membersCanInvite = Boolean.TRUE.equals(in.membersCanInvite());
        c.photoRequired = Boolean.TRUE.equals(in.photoRequired());
        c.tracking = tracking(in.tracking());
        c.health = null;
        if (c.type != CohabitType.STREAK || c.auto == null || !existing) {
            c.auto = null;
        }
        c.streak = null;
        c.abstinence = null;
        if (c.type != CohabitType.GOAL) {
            c.goal = null;
        }
        if (c.type != CohabitType.CHALLENGE) {
            c.challenge = null;
        }
        switch (c.type) {
            case STREAK -> streak(c, in, existing, sources);
            case ABSTINENCE -> {
                c.abstinence = new AbstinenceConfig(in.abstinence() != null && in.abstinence().groupMode());
                // Eine Unterbrechung hat weder Wert noch Beweisfoto.
                c.tracking = Tracking.CHECK;
                c.photoRequired = false;
            }
            case GOAL -> goal(c, in, existing, today);
            case CHALLENGE -> challenge(c, in, existing, today);
        }
        if (in.health() != null && in.health().metric() != null) {
            if (c.type == CohabitType.ABSTINENCE || c.auto != null) {
                throw Errors.badRequest("Health passt nicht zu diesem Co-Habit.");
            }
            c.health = new HealthConfig(in.health().metric());
            // Der Wert kommt aus Health - die Einheit folgt der Metrik.
            c.tracking = new Tracking(TrackingMode.VALUE, Texts.unitOf(in.health().metric()));
        }
        if (c.type == CohabitType.GOAL && c.goal.counting() == GoalCounting.AMOUNT && !c.tracking.withValue()) {
            throw Errors.badRequest("Für eine Menge braucht es „Mit Wert erfassen“.");
        }
        if (c.type == CohabitType.CHALLENGE && c.challenge.scoring() == Scoring.HIGHEST_SUM && !c.tracking.withValue()) {
            throw Errors.badRequest("Für „Höchste Summe“ braucht es „Mit Wert erfassen“.");
        }
    }

    static Tracking tracking(Tracking t) {
        if (t == null || t.mode() == null || t.mode() == TrackingMode.CHECK) {
            return Tracking.CHECK;
        }
        if (t.unit() == null) {
            throw Errors.badRequest("Mit Wert erfassen braucht eine Einheit.");
        }
        return new Tracking(TrackingMode.VALUE, t.unit());
    }

    private static void streak(Cohabit c, CohabitInput in, boolean existing, List<AutoSource> sources) {
        AutoConfig auto = in.auto();
        if (existing && c.auto != null) {
            // Die Quelle bleibt; nur die Ziele lassen sich aendern.
            AutoSource source = c.auto.source();
            auto = new AutoConfig(source, auto == null ? c.auto.weeklyStepGoal() : auto.weeklyStepGoal(),
                    auto == null ? c.auto.focusMinutesGoal() : auto.focusMinutesGoal());
        } else if (existing && auto != null && auto.source() != null) {
            throw Errors.badRequest("Ein Co-Habit wird nachträglich nicht automatisch.");
        }
        if (auto != null && auto.source() != null) {
            if (!existing && !sources.contains(auto.source())) {
                throw Errors.forbidden("Diese Quelle hast du nicht.");
            }
            Integer steps = null;
            Integer minutes = null;
            if (auto.source() == AutoSource.STEPS_WEEKLY) {
                steps = auto.weeklyStepGoal();
                if (steps == null || steps < 1 || steps > MAX_STEP_GOAL) {
                    throw Errors.badRequest("Das Wochenziel muss zwischen 1 und 500.000 Schritten liegen.");
                }
            }
            if (auto.source() == AutoSource.FOCUS) {
                minutes = auto.focusMinutesGoal() == null ? AutoConfig.DEFAULT_FOCUS_MINUTES : auto.focusMinutesGoal();
                if (minutes < 1 || minutes > MAX_FOCUS_MINUTES) {
                    throw Errors.badRequest("Das Tagesziel muss zwischen 1 und 1440 Minuten liegen.");
                }
            }
            c.auto = new AutoConfig(auto.source(), steps, minutes);
            Rhythm rhythm = auto.source() == AutoSource.STEPS_WEEKLY ? Rhythm.timesPerWeek(1) : Rhythm.daily();
            c.streak = new StreakConfig(rhythm, in.streak() != null && in.streak().groupStreak());
            c.tracking = Tracking.CHECK;
            c.photoRequired = false;
            return;
        }
        c.auto = null;
        Rhythm rhythm = rhythm(in.streak() == null ? null : in.streak().rhythm());
        c.streak = new StreakConfig(rhythm, in.streak() != null && in.streak().groupStreak());
    }

    static Rhythm rhythm(Rhythm r) {
        if (r == null || r.kind() == null || r.kind() == RhythmKind.DAILY) {
            return Rhythm.daily();
        }
        return switch (r.kind()) {
            case WEEKDAYS -> {
                if (r.weekdays() == null || r.weekdays().isEmpty()) {
                    throw Errors.badRequest("Bitte mindestens einen Wochentag wählen.");
                }
                TreeSet<Integer> days = new TreeSet<>();
                for (Integer d : r.weekdays()) {
                    if (d == null || d < 1 || d > 7) {
                        throw Errors.badRequest("Wochentage sind 1 (Montag) bis 7 (Sonntag).");
                    }
                    days.add(d);
                }
                yield new Rhythm(RhythmKind.WEEKDAYS, List.copyOf(days), null, null);
            }
            case TIMES_PER_WEEK -> {
                int times = r.times() == null ? 1 : r.times();
                if (times < 1 || times > 7) {
                    throw Errors.badRequest("Pro Woche geht 1- bis 7-mal.");
                }
                yield Rhythm.timesPerWeek(times);
            }
            case TIMES_PER_MONTH -> {
                int times = r.times() == null ? 1 : r.times();
                if (times < 1 || times > 31) {
                    throw Errors.badRequest("Pro Monat geht 1- bis 31-mal.");
                }
                yield Rhythm.timesPerMonth(times);
            }
            case INTERVAL -> {
                int days = r.days() == null ? 2 : r.days();
                if (days < 2 || days > 30) {
                    throw Errors.badRequest("Das Intervall muss 2 bis 30 Tage lang sein.");
                }
                yield new Rhythm(RhythmKind.INTERVAL, null, null, days);
            }
            case DAILY -> Rhythm.daily();
        };
    }

    private static void goal(Cohabit c, CohabitInput in, boolean existing, LocalDate today) {
        GoalConfig g = in.goal();
        if (g == null) {
            throw Errors.badRequest("Das Ziel fehlt.");
        }
        if (g.target() == null || !(g.target() > 0) || g.target() > 1e12) {
            throw Errors.badRequest("Der Zielwert muss größer als 0 sein.");
        }
        LocalDate start = existing && c.goal != null ? c.goal.start() : today;
        if (g.deadline() == null || g.deadline().isBefore(start)) {
            throw Errors.badRequest("Das Zieldatum darf nicht vor dem Start liegen.");
        }
        GoalCounting counting = g.counting() == null ? GoalCounting.AMOUNT : g.counting();
        GoalMode mode = g.mode() == null ? GoalMode.TEAM : g.mode();
        c.goal = new GoalConfig(Texts.round2(g.target()), start, g.deadline(), counting, mode);
    }

    private static void challenge(Cohabit c, CohabitInput in, boolean existing, LocalDate today) {
        ChallengeConfig ch = in.challenge();
        if (ch == null || ch.start() == null || ch.end() == null) {
            throw Errors.badRequest("Die Challenge braucht Start und Ende.");
        }
        if (ch.end().isBefore(ch.start())) {
            throw Errors.badRequest("Das Ende darf nicht vor dem Start liegen.");
        }
        if (ch.scoring() == null) {
            throw Errors.badRequest("Wie soll gewertet werden?");
        }
        Double target = null;
        if (ch.scoring() == Scoring.FIRST_TO_TARGET) {
            if (ch.target() == null || !(ch.target() > 0)) {
                throw Errors.badRequest("„Wer zuerst …“ braucht einen Zielwert.");
            }
            target = Texts.round2(ch.target());
        }
        String stake = ch.stake() == null || ch.stake().isBlank() ? null : ch.stake().trim();
        if (stake != null && stake.length() > MAX_STAKE) {
            throw Errors.badRequest("Der Einsatz darf höchstens 80 Zeichen haben.");
        }
        Recurrence recurrence = ch.recurrence() == null ? Recurrence.NONE : ch.recurrence();
        if (existing && c.challenge != null) {
            ChallengeConfig old = c.challenge;
            boolean running = c.challengeState != null && c.challengeState.roundEndedAt == null
                    && !today.isBefore(old.start()) && !today.isAfter(old.end());
            if (running) {
                if (!ch.start().equals(old.start())) {
                    throw Errors.badRequest("Der Start einer laufenden Runde lässt sich nicht ändern.");
                }
                if (ch.end().isBefore(old.end())) {
                    throw Errors.badRequest("Eine laufende Runde lässt sich nur verlängern.");
                }
                if (ch.scoring() != old.scoring() || !java.util.Objects.equals(target, old.target())) {
                    throw Errors.badRequest("Die Wertung einer laufenden Runde lässt sich nicht ändern.");
                }
            } else if (ch.end().isBefore(today)) {
                throw Errors.badRequest("Das Ende darf nicht in der Vergangenheit liegen.");
            }
        } else if (ch.end().isBefore(today)) {
            throw Errors.badRequest("Das Ende darf nicht in der Vergangenheit liegen.");
        }
        c.challenge = new ChallengeConfig(ch.start(), ch.end(), ch.scoring(), target, stake, recurrence);
    }
}
