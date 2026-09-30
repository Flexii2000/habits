package com.fherrmann.habits.cohabit.api;

import java.time.Instant;

/** {@code state}: ACTIVE | PAUSED (heute in einer Pause) | INVITED (Einladung offen). */
public record MemberView(PersonView person, String role, String state, Instant joinedAt) {
}
