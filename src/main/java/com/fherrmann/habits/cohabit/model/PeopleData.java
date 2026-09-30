package com.fherrmann.habits.cohabit.model;

import java.util.ArrayList;
import java.util.List;

/** Alles in {@code people.json}. */
public class PeopleData {
    public List<Person> persons = new ArrayList<>();
    public List<Friendship> friendships = new ArrayList<>();
    public List<FriendRequest> friendRequests = new ArrayList<>();
}
