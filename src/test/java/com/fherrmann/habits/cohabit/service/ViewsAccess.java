package com.fherrmann.habits.cohabit.service;

/** Zugang zu paketinternen Hilfen fuer Tests in anderen Paketen. */
public final class ViewsAccess {

    private ViewsAccess() {
    }

    public static int goalPercent(double total, double target) {
        return Views.goalPercent(total, target);
    }
}
