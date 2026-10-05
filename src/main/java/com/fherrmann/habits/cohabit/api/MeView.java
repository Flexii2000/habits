package com.fherrmann.habits.cohabit.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** {@code typeColors}: alle fuenf Plaetze (STREAK, ABSTINENCE, GOAL, CHALLENGE, AUTOMATIC), Vorgaben eingesetzt. */
public record MeView(PersonView person, boolean isOwner, List<String> sources, Counts counts,
                     int pendingInvitations, int incomingFriendRequests, boolean canLogout, Instant createdAt,
                     Map<String, String> typeColors) {

    public record Counts(int cohabits, int friends, int wins) {
    }
}
