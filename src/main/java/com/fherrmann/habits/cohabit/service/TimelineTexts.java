package com.fherrmann.habits.cohabit.service;

import com.fherrmann.habits.cohabit.model.Cohabit;
import com.fherrmann.habits.cohabit.model.CohabitType;
import com.fherrmann.habits.cohabit.rules.Texts;

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
}
