package com.fherrmann.habits.cohabit.api;

/** {@code kind}: COHABIT | FRIEND; {@code cohabit} nur bei COHABIT. */
public record InviteLinkPreview(String kind, PersonView from, InviteCohabit cohabit, boolean full) {
}
