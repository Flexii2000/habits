package com.fherrmann.habits.cohabit.rules;

import com.fherrmann.habits.cohabit.model.AutoConfig;
import com.fherrmann.habits.cohabit.model.ChallengeConfig;
import com.fherrmann.habits.cohabit.model.CheckinKind;
import com.fherrmann.habits.cohabit.model.CohabitType;
import com.fherrmann.habits.cohabit.model.GoalConfig;
import com.fherrmann.habits.cohabit.model.GoalCounting;
import com.fherrmann.habits.cohabit.model.HealthMetric;
import com.fherrmann.habits.cohabit.model.Scoring;
import com.fherrmann.habits.cohabit.model.Tracking;
import com.fherrmann.habits.cohabit.model.ValueUnit;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Ein Co-Habit als Tagesreihe einer Person - fuer das Logbook in Healthy, das jede
 * Gewohnheit gegen die Recovery des naechsten Morgens rechnet ({@code GET /me/days}).
 *
 * <p>Je Tag eine Zahl, und nur an Tagen, an denen das Co-Habit fuer die Person galt:
 * ab ihrem Beitritt bzw. dem Start, bis heute bzw. zum Archivieren, ohne Pausen, bei
 * Zielen und Challenges nur in deren Zeitraum. Ein fehlender Tag heisst "unbekannt",
 * eine 0 heisst "nicht getan" - das Logbook rechnet mit beidem verschieden.
 *
 * <ul>
 *   <li>STREAK ohne Wert, Ziel nach Eintraegen, Challenge "meiste Eintraege": 1 mit
 *       Eintrag, sonst 0 ({@link Kind#BINARY}).</li>
 *   <li>STREAK mit Wert, Ziel nach Menge, Challenge nach Summe: die Summe der Werte,
 *       ohne Eintrag 0. Ein Eintrag ohne Wert zaehlt wie in coHabit: bei Challenges 1,
 *       bei Zielen 0; bei einer Serie mit Wert laesst er den Tag offen. Laufpunkte: die
 *       gelaufenen km ({@link Kind#AMOUNT}).</li>
 *   <li>ABSTINENCE: 1 ohne, 0 mit Unterbrechung.</li>
 *   <li>Health-Metrik: die Summe der Werte. Zwischen erstem Eintrag und letztem Abgleich
 *       fehlt ein Tag nur, wenn es "kcal aus Healthy" ist - dort heisst ein fehlender
 *       Eintrag "nichts getrackt", bei Laufen oder Trainings dagegen 0.</li>
 *   <li>Automatische Quellen liefern ihre Werte selbst ({@code AutoSources.dayValues}).</li>
 * </ul>
 */
public final class DaySeriesCalc {

    private DaySeriesCalc() {
    }

    public enum Kind {
        BINARY, AMOUNT
    }

    /** Ein eigener Eintrag, unter dem Lock kopiert. */
    public record CheckinFact(LocalDate date, CheckinKind kind, Double value, Double distanceKm) {
    }

    /** Ein Zeitraum, beide Tage einschliesslich. */
    public record Span(LocalDate from, LocalDate to) {

        public boolean covers(LocalDate day) {
            return !day.isBefore(from) && !day.isAfter(to);
        }
    }

    /**
     * Was die Rechnung ueber ein Co-Habit und die fragende Person wissen muss - eine
     * Kopie, damit nach dem Lock nichts mehr am Store haengt.
     *
     * @param start         erster Tag der Person (Beitritt bzw. Start, was spaeter ist)
     * @param last          letzter Tag: heute in der Zone des Co-Habits bzw. der Tag des Archivierens
     * @param rounds        CHALLENGE: laufende und fruehere Runden; sonst leer
     * @param lastHealthDay Tag des letzten Health-Abgleichs der Person, oder {@code null}
     */
    public record Snapshot(CohabitType type, Tracking tracking, GoalConfig goal, ChallengeConfig challenge,
                           List<Span> rounds, HealthMetric health, AutoConfig auto, LocalDate start,
                           LocalDate last, List<Span> pauses, List<CheckinFact> checkins,
                           LocalDate lastHealthDay) {
    }

    /** Die Reihe: Art, Einheit (nur bei Mengen) und die Werte je Tag, aufsteigend. */
    public record Series(Kind kind, ValueUnit unit, SortedMap<LocalDate, Double> days) {
    }

    public static Kind kind(Snapshot s) {
        if (s.auto() != null) {
            return switch (s.auto().source()) {
                case FOOD, FOOD_TARGET_WEEKLY -> Kind.BINARY;
                case STEPS_WEEKLY, FOCUS -> Kind.AMOUNT;
            };
        }
        if (s.health() != null) {
            return Kind.AMOUNT;
        }
        return switch (s.type()) {
            case STREAK -> s.tracking() != null && s.tracking().withValue() ? Kind.AMOUNT : Kind.BINARY;
            case ABSTINENCE -> Kind.BINARY;
            case GOAL -> s.goal() != null && s.goal().counting() == GoalCounting.AMOUNT ? Kind.AMOUNT : Kind.BINARY;
            case CHALLENGE -> s.challenge() != null && s.challenge().scoring() != Scoring.MOST_ENTRIES
                    ? Kind.AMOUNT : Kind.BINARY;
        };
    }

    /** Die Einheit einer Mengenreihe; bei Ja/Nein {@code null}. */
    public static ValueUnit unit(Snapshot s) {
        if (kind(s) == Kind.BINARY) {
            return null;
        }
        if (s.auto() != null) {
            return s.auto().source() == com.fherrmann.habits.cohabit.model.AutoSource.STEPS_WEEKLY
                    ? ValueUnit.STEPS : ValueUnit.MINUTES;
        }
        if (s.health() != null) {
            return switch (s.health()) {
                case STEPS -> ValueUnit.STEPS;
                case RUNNING_DISTANCE -> ValueUnit.KM;
                case WORKOUTS -> ValueUnit.COUNT;
                case WORKOUT_MINUTES -> ValueUnit.MINUTES;
                case KCAL -> ValueUnit.KCAL;
            };
        }
        if (s.challenge() != null && s.challenge().runPoints()) {
            return ValueUnit.KM;
        }
        return s.tracking() != null && s.tracking().unit() != null ? s.tracking().unit() : ValueUnit.COUNT;
    }

    /**
     * Die Reihe fuer {@code from} bis {@code to}.
     *
     * @param auto bei automatischen Co-Habits die Werte der Quelle je Tag; sonst leer
     */
    public static Series series(Snapshot s, Map<LocalDate, Double> auto, LocalDate from, LocalDate to) {
        Kind kind = kind(s);
        TreeMap<LocalDate, Double> days = new TreeMap<>();
        LocalDate lo = s.start() == null || s.start().isBefore(from) ? from : s.start();
        LocalDate hi = s.last() == null || s.last().isAfter(to) ? to : s.last();
        Series series = new Series(kind, unit(s), days);
        if (lo.isAfter(hi)) {
            return series;
        }
        if (s.auto() != null) {
            auto.forEach((day, value) -> {
                if (!day.isBefore(lo) && !day.isAfter(hi) && counts(s, day)) {
                    days.put(day, value);
                }
            });
            return series;
        }

        Set<LocalDate> done = new HashSet<>();
        Set<LocalDate> breaks = new HashSet<>();
        Set<LocalDate> withoutValue = new HashSet<>();
        Map<LocalDate, Double> sums = new HashMap<>();
        boolean runPoints = s.challenge() != null && s.challenge().runPoints();
        for (CheckinFact f : s.checkins()) {
            if (f.date() == null) {
                continue;
            }
            if (f.kind() == CheckinKind.BREAK) {
                breaks.add(f.date());
                continue;
            }
            done.add(f.date());
            // Wie coHabit selbst zaehlt (CohabitEval): bei Challenges ein Eintrag ohne Wert 1
            // ("Wer zuerst ..." darf ohne Wert gefuehrt werden), bei Zielen nach Menge 0;
            // Laufpunkte zaehlen die gelaufenen km.
            Double amount;
            if (runPoints) {
                amount = f.distanceKm();
            } else if (s.type() == CohabitType.CHALLENGE) {
                amount = f.value() == null ? 1.0 : f.value();
            } else if (s.type() == CohabitType.GOAL) {
                amount = f.value() == null ? 0.0 : f.value();
            } else {
                amount = f.value();
            }
            if (amount == null) {
                withoutValue.add(f.date());
            } else {
                sums.merge(f.date(), amount, Double::sum);
            }
        }

        if (s.health() != null) {
            healthDays(s, done, sums, lo, hi, days);
            return series;
        }
        for (LocalDate d = lo; !d.isAfter(hi); d = d.plusDays(1)) {
            if (!counts(s, d)) {
                continue;
            }
            // Kein Bedingungsausdruck aus double und Double: der packte ein null aus.
            Double value;
            if (s.type() == CohabitType.ABSTINENCE) {
                value = breaks.contains(d) ? 0.0 : 1.0;
            } else if (kind == Kind.BINARY) {
                value = done.contains(d) ? 1.0 : 0.0;
            } else {
                value = amountOn(d, done, sums, withoutValue);
            }
            if (value != null) {
                days.put(d, value);
            }
        }
        return series;
    }

    /** Ohne Eintrag 0; ein Eintrag ohne Wert sagt "getan", aber nicht wie viel - dann offen. */
    private static Double amountOn(LocalDate d, Set<LocalDate> done, Map<LocalDate, Double> sums,
                                   Set<LocalDate> withoutValue) {
        if (!done.contains(d)) {
            return 0.0;
        }
        if (sums.containsKey(d)) {
            return sums.get(d);
        }
        return withoutValue.contains(d) ? null : 0.0;
    }

    /**
     * Health-Werte kommen je Tag als ein Eintrag. Zwischen dem ersten Eintrag und dem
     * letzten Abgleich ist ein fehlender Tag eine 0 (keine Trainings, kein Lauf) - nur
     * bei "kcal aus Healthy" nicht: dort schreibt der Dienst fuer 0 nichts, und 0 kcal
     * heisst "nichts getrackt", nicht "nichts gegessen".
     */
    private static void healthDays(Snapshot s, Set<LocalDate> done, Map<LocalDate, Double> sums,
                                   LocalDate lo, LocalDate hi, TreeMap<LocalDate, Double> days) {
        if (s.health() == HealthMetric.KCAL) {
            sums.forEach((day, value) -> {
                if (!day.isBefore(lo) && !day.isAfter(hi) && counts(s, day)) {
                    days.put(day, value);
                }
            });
            return;
        }
        if (done.isEmpty()) {
            return;
        }
        LocalDate first = done.stream().min(LocalDate::compareTo).orElseThrow();
        LocalDate lastEntry = done.stream().max(LocalDate::compareTo).orElseThrow();
        LocalDate lastKnown = s.lastHealthDay() != null && s.lastHealthDay().isAfter(lastEntry)
                ? s.lastHealthDay() : lastEntry;
        LocalDate a = first.isAfter(lo) ? first : lo;
        LocalDate b = lastKnown.isBefore(hi) ? lastKnown : hi;
        for (LocalDate d = a; !d.isAfter(b); d = d.plusDays(1)) {
            if (counts(s, d)) {
                days.put(d, sums.getOrDefault(d, 0.0));
            }
        }
    }

    /** Ob der Tag fuer die Person zaehlt: nicht pausiert, bei Zielen und Challenges in deren Zeitraum. */
    static boolean counts(Snapshot s, LocalDate day) {
        for (Span pause : s.pauses()) {
            if (pause.covers(day)) {
                return false;
            }
        }
        if (s.type() == CohabitType.GOAL && s.goal() != null) {
            GoalConfig g = s.goal();
            if ((g.start() != null && day.isBefore(g.start())) || (g.deadline() != null && day.isAfter(g.deadline()))) {
                return false;
            }
        }
        if (s.type() == CohabitType.CHALLENGE) {
            for (Span round : s.rounds()) {
                if (round.covers(day)) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }
}
