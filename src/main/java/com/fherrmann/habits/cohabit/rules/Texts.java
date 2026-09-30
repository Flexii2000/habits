package com.fherrmann.habits.cohabit.rules;

import com.fherrmann.habits.cohabit.model.AutoConfig;
import com.fherrmann.habits.cohabit.model.AutoSource;
import com.fherrmann.habits.cohabit.model.HealthMetric;
import com.fherrmann.habits.cohabit.model.Rhythm;
import com.fherrmann.habits.cohabit.model.RhythmKind;
import com.fherrmann.habits.cohabit.model.ValueUnit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** Deutsche Texte und Zahlen - alle Clients zeigen, was hier entsteht. */
public final class Texts {

    private static final List<String> MONTHS = List.of("Januar", "Februar", "März", "April", "Mai", "Juni", "Juli",
            "August", "September", "Oktober", "November", "Dezember");
    private static final List<String> WEEKDAYS = List.of("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private Texts() {
    }

    /** 68400 -> "68.400", 5.25 -> "5,25", 5.0 -> "5". */
    public static String number(double value) {
        DecimalFormat format = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.GERMANY));
        format.setRoundingMode(java.math.RoundingMode.HALF_UP);
        return format.format(value);
    }

    public static String signed(double value) {
        if (value > 0) {
            return "+" + number(value);
        }
        if (value < 0) {
            return "−" + number(-value);
        }
        return "0";
    }

    /** Auf zwei Nachkommastellen, damit 0,1 + 0,2 nicht zu 0,30000000000000004 wird. */
    public static double round2(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    public static String month(LocalDate day) {
        return MONTHS.get(day.getMonthValue() - 1);
    }

    public static String weekday(int isoDay) {
        return WEEKDAYS.get(isoDay - 1);
    }

    /** "31.10." */
    public static String dateShort(LocalDate day) {
        return String.format("%02d.%02d.", day.getDayOfMonth(), day.getMonthValue());
    }

    /** "1. Oktober" */
    public static String dateLong(LocalDate day) {
        return day.getDayOfMonth() + ". " + month(day);
    }

    public static String time(Instant instant, ZoneId zone) {
        return TIME.format(instant.atZone(zone));
    }

    public static String plural(long n, String singular, String plural) {
        return n == 1 ? singular : plural;
    }

    /** "Lena", "Lena & Max", "Lena, Max & Sara". */
    public static String names(List<String> names) {
        if (names.isEmpty()) {
            return "";
        }
        if (names.size() == 1) {
            return names.getFirst();
        }
        return names.subList(0, names.size() - 1).stream().collect(Collectors.joining(", "))
                + " & " + names.getLast();
    }

    /** "Lena", "Lena und Max", "Lena, Max und Sara" - fuer ganze Saetze. */
    public static String namesAnd(List<String> names) {
        if (names.size() <= 1) {
            return names.isEmpty() ? "" : names.getFirst();
        }
        return names.subList(0, names.size() - 1).stream().collect(Collectors.joining(", "))
                + " und " + names.getLast();
    }

    public static String unitLabel(ValueUnit unit) {
        if (unit == null) {
            return "Einträge";
        }
        return switch (unit) {
            case COUNT -> "Anzahl";
            case MINUTES -> "Minuten";
            case KM -> "Kilometer";
            case STEPS -> "Schritte";
        };
    }

    /** Ein Wert mit Einheit: "8.200 Schritte", "5,2 km", "30 Min.", "12". */
    public static String valueText(double value, ValueUnit unit) {
        if (unit == null) {
            return number(value);
        }
        return switch (unit) {
            case COUNT -> number(value);
            case MINUTES -> number(value) + " Min.";
            case KM -> number(value) + " km";
            case STEPS -> number(value) + " " + plural(Math.round(value), "Schritt", "Schritte");
        };
    }

    public static ValueUnit unitOf(HealthMetric metric) {
        return switch (metric) {
            case STEPS -> ValueUnit.STEPS;
            case RUNNING_DISTANCE -> ValueUnit.KM;
            case WORKOUTS -> ValueUnit.COUNT;
            case WORKOUT_MINUTES -> ValueUnit.MINUTES;
        };
    }

    public static String healthLabel(HealthMetric metric) {
        return switch (metric) {
            case STEPS -> "Schritte";
            case RUNNING_DISTANCE -> "Laufdistanz";
            case WORKOUTS -> "Trainings";
            case WORKOUT_MINUTES -> "Trainingsminuten";
        };
    }

    public static String healthShareText(HealthMetric metric) {
        return switch (metric) {
            case STEPS -> "nur die Schrittzahl wird geteilt";
            case RUNNING_DISTANCE -> "nur die Laufdistanz wird geteilt";
            case WORKOUTS -> "nur die Zahl der Trainings wird geteilt";
            case WORKOUT_MINUTES -> "nur die Trainingsminuten werden geteilt";
        };
    }

    /** "täglich", "Mo, Mi, Fr", "3× pro Woche", "2× pro Monat", "alle 2 Tage". */
    public static String rhythm(Rhythm r) {
        return switch (r.kind()) {
            case DAILY -> "täglich";
            case WEEKDAYS -> r.weekdays() == null ? "Wochentage" : r.weekdays().stream().sorted()
                    .map(Texts::weekday).collect(Collectors.joining(", "));
            case TIMES_PER_WEEK -> StreakModel.times(r) + "× pro Woche";
            case TIMES_PER_MONTH -> StreakModel.times(r) + "× pro Monat";
            case INTERVAL -> "alle " + StreakModel.intervalDays(r) + " Tage";
        };
    }

    public static String autoRhythm(AutoConfig auto) {
        return switch (auto.source()) {
            case FOOD -> "täglich · Kalorienzähler";
            case STEPS_WEEKLY -> number(auto.stepGoal()) + " Schritte pro Woche";
            case FOCUS -> auto.focusGoal() + " Min. Fokus täglich";
        };
    }

    public static String autoRule(AutoConfig auto) {
        return switch (auto.source()) {
            case FOOD -> "Automatisch: Kalorienzähler";
            case STEPS_WEEKLY -> "Automatisch: " + number(auto.stepGoal()) + " Schritte/Woche";
            case FOCUS -> "Automatisch: " + auto.focusGoal() + " Min. Fokus/Tag";
        };
    }

    public static boolean isWeekly(Rhythm r) {
        return r.kind() == RhythmKind.TIMES_PER_WEEK;
    }

    public static boolean isSteps(AutoConfig auto) {
        return auto != null && auto.source() == AutoSource.STEPS_WEEKLY;
    }

    public static String backfillRule(int hours) {
        return switch (hours) {
            case 0 -> "Kein Nachtragen";
            case 168 -> "Nachtragen bis 7 Tage";
            case 336 -> "Nachtragen bis 14 Tage";
            default -> "Nachtragen bis " + hours + " h";
        };
    }
}
