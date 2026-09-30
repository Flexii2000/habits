package com.fherrmann.habits.cohabit.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/** {@code {"value":"6","unit":"Wochen","short":"6 Wo."}}. */
public record Headline(String value, String unit, @JsonProperty("short") String shortText) {
}
