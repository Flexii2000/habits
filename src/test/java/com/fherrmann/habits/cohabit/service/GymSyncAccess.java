package com.fherrmann.habits.cohabit.service;

/** Fuer die API-Tests: der Abgleich beim Oeffnen merkt sich je Person eine Minute - zwischen Tests vergessen. */
public final class GymSyncAccess {

    private GymSyncAccess() {
    }

    public static void forget(GymSync sync) {
        sync.forgetRefreshes();
    }
}
