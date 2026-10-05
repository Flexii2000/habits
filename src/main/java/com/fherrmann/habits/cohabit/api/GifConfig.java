package com.fherrmann.habits.cohabit.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Was die Apps fuer die GIF-Suche brauchen; ohne Schluessel nur {@code {"enabled":false}}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GifConfig(boolean enabled, String apiKey, String customerId, String locale, String contentFilter) {

    public static GifConfig disabled() {
        return new GifConfig(false, null, null, null, null);
    }
}
