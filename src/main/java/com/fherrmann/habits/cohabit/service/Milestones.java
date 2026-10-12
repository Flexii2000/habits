package com.fherrmann.habits.cohabit.service;

import com.fherrmann.habits.cohabit.model.Checkin;
import com.fherrmann.habits.cohabit.model.Cohabit;
import com.fherrmann.habits.cohabit.model.CohabitType;
import com.fherrmann.habits.cohabit.model.Member;
import com.fherrmann.habits.cohabit.rules.AbstinenceCalc;
import com.fherrmann.habits.cohabit.rules.AutoFacts;
import com.fherrmann.habits.cohabit.rules.CohabitEval;
import com.fherrmann.habits.cohabit.rules.Evaluations;
import com.fherrmann.habits.cohabit.rules.Limits;
import com.fherrmann.habits.cohabit.rules.StreakCalc;
import com.fherrmann.habits.cohabit.rules.StreakModel;
import com.fherrmann.habits.cohabit.rules.StreakUnit;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Welche Serienlaengen gefeiert werden - und das stille Erfassen dessen, was
 * schon erreicht war.
 *
 * <p>STREAK: 7, 30, 100, 365 Tage - 4, 12, 26, 52 Wochen - 3, 6, 12 Monate.
 * ABSTINENCE: 7, 14, 30, 50, 100, 200, 365 Tage, dann jaehrlich.
 */
public final class Milestones {

    private static final List<Integer> DAYS = List.of(7, 30, 100, 365);
    private static final List<Integer> WEEKS = List.of(4, 12, 26, 52);
    private static final List<Integer> MONTHS = List.of(3, 6, 12);
    private static final List<Integer> ABSTINENCE = List.of(7, 14, 30, 50, 100, 200, 365);

    private Milestones() {
    }

    /** Alle Meilensteine bis einschliesslich {@code value}. */
    public static List<Integer> reached(CohabitType type, StreakUnit unit, int value) {
        List<Integer> result = new ArrayList<>();
        if (type == CohabitType.ABSTINENCE) {
            for (int m : ABSTINENCE) {
                if (m <= value) {
                    result.add(m);
                }
            }
            for (int year = 2; 365 * year <= value; year++) {
                result.add(365 * year);
            }
            return result;
        }
        List<Integer> list = switch (unit) {
            case DAYS -> DAYS;
            case WEEKS -> WEEKS;
            case MONTHS -> MONTHS;
            case WINDOWS -> List.of();
        };
        for (int m : list) {
            if (m <= value) {
                result.add(m);
            }
        }
        return result;
    }

    /**
     * Was eine Serie ausmacht: Rhythmus und Einheit, bei automatischen Quellen dazu die
     * Vorgaben, an denen ein Tag oder eine Woche gemessen wird. Aendert sich das, misst der
     * gespeicherte Rekord etwas anderes - nach dem Wechsel von taeglich auf 4x pro Woche
     * stuenden sonst 40 Tage als „Rekord 40 Wochen" da (12.10.).
     */
    public static List<Object> seriesRule(Cohabit c) {
        if (Limits.isLogbookLimit(c)) {
            // Ein anderes Tageslimit macht andere Tage zu Unterbrechungen.
            return List.of(String.valueOf(c.abstinence.limit().perDay()));
        }
        if (c.type != CohabitType.STREAK) {
            return List.of();
        }
        List<Object> rule = new ArrayList<>();
        rule.add(StreakModel.rhythm(c));
        rule.add(StreakModel.scheme(c).unit());
        if (c.auto != null) {
            // Ohne den Namen der Fokus-Kategorie: der wird beim Speichern aufgefrischt und
            // aendert an der Wertung nichts.
            rule.add(Arrays.asList(c.auto.source(), c.auto.weeklyStepGoal(), c.auto.focusMinutesGoal(),
                    c.auto.focusCategoryId(), c.auto.focusPeriod()));
        }
        return rule;
    }

    /**
     * Die Serien-Regel hat sich geaendert: Bestserie und Meilensteine gelten fuer die alte
     * und werden unter der neuen still neu erfasst - Rekord ist dann die laengste Serie nach
     * der neuen Regel, gemeldet wird nichts. Ohne Quelle sofort aus den Eintraegen; mit
     * Quelle beim naechsten Lauf, der ihre Werte hat (Achievements). Bis dahin zeigt die
     * Ansicht die laengste Serie, die sich berechnen laesst.
     */
    public static void resetSeries(Cohabit c, List<Checkin> checkins, LocalDate today) {
        for (Member m : c.members) {
            m.bestStreak = 0;
            m.bestAnnouncedRun = null;
            m.milestones.clear();
            if (c.auto == null && !Limits.isLogbookLimit(c)) {
                silentBaseline(c, m, checkins, today);
            } else {
                m.baselinePending = true;
            }
        }
    }

    /**
     * Aus einer fertigen Rechnung - fuer Quellen und das Tageslimit, deren Zahlen nur die
     * Rechnung kennt (die Eintraege allein ergaeben dort eine Serie ohne Unterbrechung).
     */
    public static void silentBaseline(Cohabit c, Member m, CohabitEval eval, CohabitEval.MemberEval me) {
        if (Limits.isLogbookLimit(c) && me.abstinence != null) {
            AbstinenceCalc.Outcome o = me.abstinence;
            m.bestStreak = Math.max(m.bestStreak, o.record());
            m.milestones.clear();
            if (o.runStart() != null) {
                m.milestones.put(o.runStart().toString(), reached(c.type, StreakUnit.DAYS, o.current()));
                if (o.current() >= o.record()) {
                    m.bestAnnouncedRun = o.runStart();
                }
            }
            m.baselinePending = false;
            return;
        }
        silentBaseline(c, m, eval.checkins, me.facts, eval.today);
    }

    /** Fuer Co-Habits ohne Quelle: alles bis jetzt Erreichte vermerken, ohne etwas zu melden. */
    public static void silentBaseline(Cohabit c, Member m, List<Checkin> checkins, LocalDate today) {
        silentBaseline(c, m, checkins, null, today);
    }

    public static void silentBaseline(Cohabit c, Member m, List<Checkin> checkins, AutoFacts facts, LocalDate today) {
        if (c.type == CohabitType.ABSTINENCE) {
            AbstinenceCalc.Outcome o = Evaluations.abstinence(c, m, checkins, today);
            m.bestStreak = Math.max(m.bestStreak, o.record());
            if (o.runStart() != null) {
                m.milestones.clear();
                m.milestones.put(o.runStart().toString(), reached(c.type, StreakUnit.DAYS, o.current()));
                if (o.current() >= o.record()) {
                    m.bestAnnouncedRun = o.runStart();
                }
            }
        } else if (c.type == CohabitType.STREAK) {
            if (facts != null && facts.isUnavailable()) {
                return;
            }
            StreakCalc.Outcome o = Evaluations.streak(c, m, checkins, facts, today);
            int longest = Evaluations.longest(c, m, checkins, facts, today, o.current());
            m.bestStreak = Math.max(m.bestStreak, longest);
            if (o.runStart() != null) {
                m.milestones.clear();
                m.milestones.put(o.runStart().toString(),
                        reached(c.type, StreakModel.scheme(c).unit(), o.current()));
                if (o.current() >= m.bestStreak) {
                    m.bestAnnouncedRun = o.runStart();
                }
            }
        }
        m.baselinePending = false;
    }
}
