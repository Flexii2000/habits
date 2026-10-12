package com.fherrmann.habits.cohabit.model;

/**
 * @param limit nur bei einer Abstinenz mit Tageslimit aus dem Logbook, sonst {@code null}
 */
public record AbstinenceConfig(boolean groupMode, LimitConfig limit) {

    public AbstinenceConfig(boolean groupMode) {
        this(groupMode, null);
    }
}
