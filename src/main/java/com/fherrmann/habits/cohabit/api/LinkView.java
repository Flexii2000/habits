package com.fherrmann.habits.cohabit.api;

import java.time.Instant;

/** Ein Einladungs- oder Freundes-Link. */
public record LinkView(String url, String code, Instant expiresAt) {
}
