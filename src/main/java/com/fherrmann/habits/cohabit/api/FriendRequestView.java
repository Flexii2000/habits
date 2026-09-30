package com.fherrmann.habits.cohabit.api;

import java.time.Instant;

public record FriendRequestView(String id, PersonView from, PersonView to, Instant createdAt) {
}
