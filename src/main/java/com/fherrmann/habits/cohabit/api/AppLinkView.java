package com.fherrmann.habits.cohabit.api;

import java.time.Instant;

public record AppLinkView(String id, String label, Instant createdAt, Instant lastUsedAt) {
}
