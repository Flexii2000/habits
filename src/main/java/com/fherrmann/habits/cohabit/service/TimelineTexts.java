package com.fherrmann.habits.cohabit.service;

import com.fherrmann.habits.cohabit.model.Checkin;
import com.fherrmann.habits.cohabit.model.Cohabit;
import com.fherrmann.habits.cohabit.model.CohabitType;
import com.fherrmann.habits.cohabit.rules.RunPoints;
import com.fherrmann.habits.cohabit.rules.Texts;

import java.util.List;

/**
 * Titel fuer Eintraege in Timeline und Push. Ein Verb laesst sich aus dem Namen
 * nicht ableiten ("hat gekocht"), daher schlicht: STREAK "hat Laufen abgehakt",
 * sonst "hat eingetragen" bzw. "hat 5 km eingetragen".
 */
public final class TimelineTexts {

    private TimelineTexts() {
    }

    public static String checkinTitle(Cohabit c, String name, Double value) {
        if (c.type == CohabitType.STREAK) {
            return name + " hat " + c.name + " abgehakt";
        }
        if (value != null && c.tracking.withValue()) {
            return name + " hat " + Texts.valueText(value, c.tracking.unit()) + " eingetragen";
        }
        return name + " hat eingetragen";
    }

    /**
     * Ein Lauf einer Lauf-Challenge: "Felix ist 5,8 km in 35 Min. gelaufen · +20 P" -
     * oder {@code null}, wenn {@code ch} keiner ist. Die Punkte haengen an den anderen
     * Laeufen des Tages, daher immer ueber alle Eintraege gerechnet.
     */
    public static String runTitle(Cohabit c, String name, Checkin ch, List<Checkin> all) {
        if (ch == null || c.type != CohabitType.CHALLENGE || c.challenge == null || !c.challenge.runPoints()
                || !RunPoints.isRun(ch)) {
            return null;
        }
        RunPoints.Points p = RunPoints.score(RunPoints.scoringOf(c), all).get(ch.id);
        return name + " ist " + Texts.number(ch.distanceKm) + " km in " + ch.durationMinutes + " Min. gelaufen · "
                + RunPoints.pointsText(p.total());
    }
}
