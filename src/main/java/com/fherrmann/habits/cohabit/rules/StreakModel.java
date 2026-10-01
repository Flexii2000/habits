package com.fherrmann.habits.cohabit.rules;

import com.fherrmann.habits.cohabit.model.AutoConfig;
import com.fherrmann.habits.cohabit.model.AutoSource;
import com.fherrmann.habits.cohabit.model.Cohabit;
import com.fherrmann.habits.cohabit.model.Pause;
import com.fherrmann.habits.cohabit.model.Rhythm;
import com.fherrmann.habits.cohabit.model.RhythmKind;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Uebersetzt die Einstellungen eines STREAK in Zeitraeume und Anforderungen.
 *
 * <p>Pausen: Pausentage sind weder faellig noch brechen sie die Serie. Ein
 * Zeitraum mit Pausentagen verlangt anteilig weniger,
 * {@code ceil(times x unpausierte Tage / Tage im Zeitraum)}; ein ganz pausierter
 * verlangt nichts und wird uebersprungen.
 */
public final class StreakModel {

    /** Deckel der automatischen Quellen, wie in der alten App (habits.max-lookback-days). */
    public static final int AUTO_LOOKBACK_DAYS = 730;

    private StreakModel() {
    }

    public static Rhythm rhythm(Cohabit c) {
        if (c.auto != null) {
            return weeklyAuto(c.auto) ? Rhythm.timesPerWeek(1) : Rhythm.daily();
        }
        return c.streak == null || c.streak.rhythm() == null ? Rhythm.daily() : c.streak.rhythm();
    }

    public static PeriodScheme scheme(Cohabit c) {
        if (c.auto != null) {
            return weeklyAuto(c.auto) ? PeriodScheme.weeks() : PeriodScheme.days();
        }
        Rhythm r = rhythm(c);
        return switch (r.kind()) {
            case DAILY, WEEKDAYS -> PeriodScheme.days();
            case TIMES_PER_WEEK -> PeriodScheme.weeks();
            case TIMES_PER_MONTH -> PeriodScheme.months();
            case INTERVAL -> PeriodScheme.windows(c.startDate, intervalDays(r));
        };
    }

    public static int intervalDays(Rhythm r) {
        return r.days() == null || r.days() < 1 ? 1 : r.days();
    }

    /** Deckel fuer die Serie: bei automatischen Quellen der alte, sonst keiner. */
    public static int maxStreak(Cohabit c) {
        if (c.auto == null) {
            return Integer.MAX_VALUE;
        }
        return weeklyAuto(c.auto) ? AUTO_LOOKBACK_DAYS / 7 : AUTO_LOOKBACK_DAYS;
    }

    /** Schritte je Woche und Fokus-Minuten je Woche: gezaehlt wird die Summe Mo-So. */
    public static boolean weeklyAuto(AutoConfig auto) {
        return auto.source() == AutoSource.STEPS_WEEKLY || auto.focusWeekly();
    }

    public static Predicate<LocalDate> paused(List<Pause> pauses) {
        if (pauses == null || pauses.isEmpty()) {
            return day -> false;
        }
        List<Pause> copy = List.copyOf(pauses);
        return day -> copy.stream().anyMatch(p -> p.covers(day));
    }

    /** Ob die Person an diesem Tag (ohne Pause gedacht) einen Eintrag schuldet - nur bei Tagesrhythmen. */
    public static boolean dueByRhythm(Cohabit c, LocalDate day) {
        Rhythm r = rhythm(c);
        if (r.kind() == RhythmKind.WEEKDAYS) {
            return r.weekdays() != null && r.weekdays().contains(day.getDayOfWeek().getValue());
        }
        return true;
    }

    /**
     * Die Anforderung fuer eine Person.
     *
     * @param doneDays Tage mit Eintrag (manuelle Co-Habits)
     * @param auto     die Quelle (automatische Co-Habits), sonst null
     */
    public static Judge judge(Cohabit c, Set<LocalDate> doneDays, List<Pause> pauses, AutoFacts auto) {
        Predicate<LocalDate> paused = paused(pauses);
        if (c.auto != null) {
            return autoJudge(c.auto, auto, paused);
        }
        Set<LocalDate> done = doneDays == null ? Set.of() : new HashSet<>(doneDays);
        Rhythm r = rhythm(c);
        return switch (r.kind()) {
            case DAILY, WEEKDAYS -> new Judge() {
                public int required(LocalDate start, LocalDate end) {
                    return !paused.test(start) && dueByRhythm(c, start) ? 1 : 0;
                }

                public int achieved(LocalDate start, LocalDate end) {
                    return done.contains(start) ? 1 : 0;
                }
            };
            case TIMES_PER_WEEK, TIMES_PER_MONTH -> countingJudge(times(r), done, paused);
            case INTERVAL -> countingJudge(1, done, paused);
        };
    }

    public static int times(Rhythm r) {
        return r.times() == null || r.times() < 1 ? 1 : r.times();
    }

    private static Judge countingJudge(int times, Set<LocalDate> done, Predicate<LocalDate> paused) {
        return new Judge() {
            public int required(LocalDate start, LocalDate end) {
                return proRated(times, start, end, paused);
            }

            public int achieved(LocalDate start, LocalDate end) {
                int count = 0;
                for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                    if (done.contains(d)) {
                        count++;
                    }
                }
                return count;
            }
        };
    }

    private static Judge autoJudge(AutoConfig cfg, AutoFacts facts, Predicate<LocalDate> paused) {
        if (weeklyAuto(cfg)) {
            int goal = cfg.source() == AutoSource.STEPS_WEEKLY ? cfg.stepGoal() : cfg.focusGoal();
            return new Judge() {
                public int required(LocalDate start, LocalDate end) {
                    return proRated(goal, start, end, paused);
                }

                public int achieved(LocalDate start, LocalDate end) {
                    if (facts == null) {
                        return 0;
                    }
                    int total = 0;
                    for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                        total += facts.amount(d);
                    }
                    return total;
                }
            };
        }
        return new Judge() {
            public int required(LocalDate start, LocalDate end) {
                return paused.test(start) ? 0 : 1;
            }

            public int achieved(LocalDate start, LocalDate end) {
                return facts != null && facts.done(start) ? 1 : 0;
            }
        };
    }

    /** {@code ceil(times x unpausierte Tage / Tage im Zeitraum)}, 0 wenn alles pausiert ist. */
    public static int proRated(int times, LocalDate start, LocalDate end, Predicate<LocalDate> paused) {
        int total = (int) ChronoUnit.DAYS.between(start, end) + 1;
        int unpaused = 0;
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            if (!paused.test(d)) {
                unpaused++;
            }
        }
        if (unpaused == 0 || times <= 0) {
            return 0;
        }
        return (int) Math.ceilDiv((long) times * unpaused, total);
    }
}
