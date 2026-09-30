package com.fherrmann.habits.cohabit.rules;

import com.fherrmann.habits.cohabit.model.Checkin;
import com.fherrmann.habits.cohabit.model.CheckinKind;
import com.fherrmann.habits.cohabit.model.Cohabit;
import com.fherrmann.habits.cohabit.model.Member;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Die Rechenregeln fuer ein Mitglied - reine Funktionen ueber Eintraege und Quellen. */
public final class Evaluations {

    private Evaluations() {
    }

    /** Ab wann eine Person zaehlt: Start des Co-Habits bzw. ihr Beitritt, was spaeter ist. */
    public static LocalDate memberStart(Cohabit c, Member m) {
        if (m.startDate == null) {
            return c.startDate;
        }
        return c.startDate == null || m.startDate.isAfter(c.startDate) ? m.startDate : c.startDate;
    }

    public static Set<LocalDate> doneDays(List<Checkin> checkins, String personId) {
        Set<LocalDate> days = new HashSet<>();
        for (Checkin checkin : checkins) {
            if (checkin.kind == CheckinKind.DONE && checkin.personId.equals(personId)) {
                days.add(checkin.date);
            }
        }
        return days;
    }

    public static Set<LocalDate> breakDays(List<Checkin> checkins, String personId) {
        Set<LocalDate> days = new HashSet<>();
        for (Checkin checkin : checkins) {
            if (checkin.kind == CheckinKind.BREAK && checkin.personId.equals(personId)) {
                days.add(checkin.date);
            }
        }
        return days;
    }

    public static Judge judge(Cohabit c, Member m, List<Checkin> checkins, AutoFacts auto) {
        return StreakModel.judge(c, doneDays(checkins, m.personId), m.pauses, auto);
    }

    /**
     * Die laufende Serie. Manuelle Co-Habits zaehlen ab dem Beitritt; automatische
     * schauen wie bisher bis zu 730 Tage zurueck, auch vor den Beitritt - die Quelle
     * weiss, was die Person vorher schon geschafft hat.
     */
    public static StreakCalc.Outcome streak(Cohabit c, Member m, List<Checkin> checkins, AutoFacts auto,
                                            LocalDate today) {
        LocalDate lower = c.auto == null ? memberStart(c, m) : null;
        return StreakCalc.evaluate(StreakModel.scheme(c), judge(c, m, checkins, auto), today, lower,
                StreakModel.maxStreak(c));
    }

    /** Die laengste Serie seit dem Beitritt (bei automatischen Quellen mindestens die laufende). */
    public static int longest(Cohabit c, Member m, List<Checkin> checkins, AutoFacts auto, LocalDate today,
                              int current) {
        int longest = StreakCalc.longest(StreakModel.scheme(c), judge(c, m, checkins, auto), memberStart(c, m),
                today);
        return Math.max(longest, current);
    }

    public static StreakCalc.Rate rate(Cohabit c, Member m, List<Checkin> checkins, AutoFacts auto, LocalDate today,
                                       LocalDate rangeFrom, LocalDate rangeTo) {
        return StreakCalc.rate(StreakModel.scheme(c), judge(c, m, checkins, auto), memberStart(c, m), today,
                rangeFrom, rangeTo);
    }

    public static AbstinenceCalc.Outcome abstinence(Cohabit c, Member m, List<Checkin> checkins, LocalDate today) {
        return AbstinenceCalc.evaluate(memberStart(c, m), breakDays(checkins, m.personId), today);
    }
}
