package com.fherrmann.habits.cohabit.api;

import java.util.List;

public record FriendsView(List<PersonView> friends, List<FriendRequestView> incoming,
                          List<FriendRequestView> outgoing) {
}
