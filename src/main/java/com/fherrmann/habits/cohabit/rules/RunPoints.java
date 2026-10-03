package com.fherrmann.habits.cohabit.rules;

import com.fherrmann.habits.cohabit.model.Checkin;
import com.fherrmann.habits.cohabit.model.CheckinKind;
import com.fherrmann.habits.cohabit.model.Cohabit;
import com.fherrmann.habits.cohabit.model.RunScoring;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Die Punkte der Laeufe einer Lauf-Challenge. Die Basis haengt von den anderen
 * Laeufen desselben Tages ab (nur der erste, der lang genug ist, bekommt sie),
 * deshalb wird immer ueber alle Eintraege gerechnet und nie etwas gespeichert.
 */
public final class RunPoints {

    /** Warum ein Lauf keine Basis bekam. */
    public enum NoBase {
        NONE, TOO_SHORT, ALREADY_TODAY
    }

    /** Die Punkte eines Laufs; {@code tooSlow}: zaehlt nicht (Grenze wurde nachtraeglich strenger). */
    public record Points(int base, int distance, int duration, NoBase noBase, boolean tooSlow) {

        public int total() {
            return base + distance + duration;
        }
    }

    private RunPoints() {
    }

    public static RunScoring scoringOf(Cohabit c) {
        return c.challenge == null || c.challenge.run() == null ? RunScoring.DEFAULT : c.challenge.run();
    }

    public static boolean isRun(Checkin ch) {
        return ch.kind == CheckinKind.DONE && ch.durationMinutes != null && ch.distanceKm != null
                && ch.durationMinutes > 0 && ch.distanceKm > 0;
    }

    /** Sekunden je km, gerundet. */
    public static int paceSeconds(int durationMinutes, double distanceKm) {
        return (int) Math.round(durationMinutes * 60.0 / distanceKm);
    }

    /** Schneller als die Grenze - genau auf der Grenze zaehlt nicht. Ungerundet verglichen. */
    public static boolean fastEnough(RunScoring r, int durationMinutes, double distanceKm) {
        return durationMinutes * 60.0 < r.paceLimit() * distanceKm;
    }

    /**
     * Punkte je Eintrag (ID) fuer alle Laeufe in {@code checkins}. Die Basis geht je
     * Person und Tag an den ersten Lauf (nach Eintragszeit), der schnell und lang genug ist.
     */
    public static Map<String, Points> score(RunScoring r, List<Checkin> checkins) {
        List<Checkin> runs = checkins.stream()
                .filter(RunPoints::isRun)
                .sorted(Comparator.comparing((Checkin x) -> x.createdAt).thenComparing(x -> x.id))
                .toList();
        Set<String> based = new HashSet<>();
        Map<String, Points> points = new HashMap<>();
        for (Checkin ch : runs) {
            if (!fastEnough(r, ch.durationMinutes, ch.distanceKm)) {
                points.put(ch.id, new Points(0, 0, 0, NoBase.NONE, true));
                continue;
            }
            // 4,999999 km aus einer Rechnung sollen 5 volle km sein.
            int distance = (int) Math.floor(ch.distanceKm + 1e-9) * r.perKm();
            int duration = ch.durationMinutes / r.perMinutes();
            String day = ch.personId + "|" + ch.date;
            NoBase noBase = NoBase.NONE;
            int base = 0;
            if (ch.durationMinutes < r.baseMinutes()) {
                noBase = NoBase.TOO_SHORT;
            } else if (based.contains(day)) {
                noBase = NoBase.ALREADY_TODAY;
            } else {
                based.add(day);
                base = r.base();
            }
            points.put(ch.id, new Points(base, distance, duration, noBase, false));
        }
        return points;
    }

    /** "6:02 min/km" */
    public static String paceText(int seconds) {
        return seconds / 60 + ":" + String.format("%02d", seconds % 60) + " min/km";
    }

    /** "8:00" - die Grenze ohne Einheit. */
    public static String paceLimitText(int seconds) {
        return seconds / 60 + ":" + String.format("%02d", seconds % 60);
    }

    /** "Basis 10 · Distanz 5 · Dauer 5", ohne Basis mit dem Grund. */
    public static String breakdownText(RunScoring r, Points p) {
        if (p.tooSlow()) {
            return "Pace nicht unter " + paceLimitText(r.paceLimit()) + " min/km";
        }
        String text = (p.base() > 0 ? "Basis " + p.base() + " · " : "")
                + "Distanz " + p.distance() + " · Dauer " + p.duration();
        return switch (p.noBase()) {
            case NONE -> text;
            case TOO_SHORT -> r.base() > 0 ? text + " · Basis erst ab " + r.baseMinutes() + " Min." : text;
            case ALREADY_TODAY -> r.base() > 0 ? text + " · Basis heute schon vergeben" : text;
        };
    }

    public static String pointsText(int points) {
        return "+" + points + " P";
    }
}
