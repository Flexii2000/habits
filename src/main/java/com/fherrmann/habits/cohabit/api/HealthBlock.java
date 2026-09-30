package com.fherrmann.habits.cohabit.api;

import java.time.Instant;

public record HealthBlock(String metric, String label, boolean consent, Instant lastSyncAt, String shareText) {
}
