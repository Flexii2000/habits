package com.fherrmann.habits.cohabit.api;

import java.time.Instant;

public record NudgeView(String id, PersonView from, CohabitRef cohabit, String text, Instant createdAt) {
}
