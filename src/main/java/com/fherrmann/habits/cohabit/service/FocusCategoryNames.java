package com.fherrmann.habits.cohabit.service;

import com.fherrmann.habits.cohabit.model.Cohabit;
import com.fherrmann.habits.cohabit.store.CohabitStore;
import com.fherrmann.habits.service.FocusService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Zieht den Namen einer umbenannten Wald-Kategorie in den Fokus-Habits nach, die nach
 * ihr zaehlen - die Typzeile ("60 Min. Bachelorarbeit täglich") soll den neuen Namen
 * tragen, ohne dass coHabit bei jeder Anzeige im Wald nachschlagen muss.
 */
@Component
public class FocusCategoryNames {

    private final CohabitStore store;

    public FocusCategoryNames(CohabitStore store) {
        this.store = store;
    }

    @EventListener
    public void renamed(FocusService.CategoryRenamed event) {
        store.update(tx -> {
            for (Cohabit c : tx.cohabits().cohabits) {
                if (c.auto != null && event.id().equals(c.auto.focusCategoryId())
                        && !event.name().equals(c.auto.focusCategoryName())) {
                    tx.cohabitsW();
                    c.auto = c.auto.withFocusCategoryName(event.name());
                }
            }
        });
    }
}
