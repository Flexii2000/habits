package com.fherrmann.habits.cohabit.model;

import java.time.LocalDate;

/** Urlaub oder Krankheit: {@code from} bis {@code to}, beide einschliesslich. */
public class Pause {
    public String id;
    public LocalDate from;
    public LocalDate to;

    public boolean covers(LocalDate day) {
        return !day.isBefore(from) && !day.isAfter(to);
    }
}
