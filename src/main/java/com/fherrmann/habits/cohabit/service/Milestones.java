package com.fherrmann.habits.cohabit.service;

import com.fherrmann.habits.cohabit.model.Checkin;
import com.fherrmann.habits.cohabit.model.Cohabit;
import com.fherrmann.habits.cohabit.model.CohabitType;
import com.fherrmann.habits.cohabit.model.Member;
import com.fherrmann.habits.cohabit.rules.AbstinenceCalc;
import com.fherrmann.habits.cohabit.rules.AutoFacts;
import com.fherrmann.habits.cohabit.rules.Evaluations;
import com.fherrmann.habits.cohabit.rules.StreakCalc;
import com.fherrmann.habits.cohabit.rules.StreakModel;
import com.fherrmann.habits.cohabit.rules.StreakUnit;

import java.time.LocalDate;
import java.util.ArrayList;
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
