package com.fherrmann.habits.cohabit.api;

import java.time.Instant;
import java.util.List;

public record MeView(PersonView person, boolean isOwner, List<String> sources, Counts counts,
                     int pendingInvitations, int incomingFriendRequests, boolean canLogout, Instant createdAt) {

    public record Counts(int cohabits, int friends, int wins) {
    }
}
