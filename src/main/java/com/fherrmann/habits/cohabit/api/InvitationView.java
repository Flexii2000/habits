package com.fherrmann.habits.cohabit.api;

import java.time.Instant;

public record InvitationView(String id, PersonView from, Instant createdAt, InviteCohabit cohabit) {
}
