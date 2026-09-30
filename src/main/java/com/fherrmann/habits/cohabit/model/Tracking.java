package com.fherrmann.habits.cohabit.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/** {@code {"mode":"CHECK"}} oder {@code {"mode":"VALUE","unit":"KM"}}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Tracking(TrackingMode mode, ValueUnit unit) {

    public static final Tracking CHECK = new Tracking(TrackingMode.CHECK, null);

    public boolean withValue() {
        return mode == TrackingMode.VALUE;
    }
}
