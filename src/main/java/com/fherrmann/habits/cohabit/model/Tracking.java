package com.fherrmann.habits.cohabit.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * {@code {"mode":"CHECK"}} oder {@code {"mode":"VALUE","unit":"KM"}}. {@code timer: true}
 * (nur bei Minuten pro Woche): Start und Stopp in der Liste, die Minuten misst der Dienst.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Tracking(TrackingMode mode, ValueUnit unit, Boolean timer) {

    public static final Tracking CHECK = new Tracking(TrackingMode.CHECK, null);

    public Tracking(TrackingMode mode, ValueUnit unit) {
        this(mode, unit, null);
    }

    public boolean withValue() {
        return mode == TrackingMode.VALUE;
    }

    public boolean withTimer() {
        return Boolean.TRUE.equals(timer);
    }
}
