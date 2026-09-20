package com.fherrmann.habits.dto;

import java.time.Instant;

/** Was die App meldet, wenn eine Session durch ist. Die Id vergibt die App. */
public record FocusSessionRequest(String id, Instant start, Instant end) {
}
