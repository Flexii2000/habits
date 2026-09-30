package com.fherrmann.habits.cohabit.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Ein Co-Habit samt Mitgliedern, Pausen und Challenge-Runden. */
public class Cohabit {
    public String id;
    public CohabitType type;
    public String name;
    public String color;
    public String timezone;
    public Tracking tracking = Tracking.CHECK;
    public boolean photoRequired;
    public int backfillHours;
    public String reminderTime;
    public boolean membersCanInvite;
    public StreakConfig streak;
    public AbstinenceConfig abstinence;
    public GoalConfig goal;
    public ChallengeConfig challenge;
    public HealthConfig health;
    public AutoConfig auto;
    public String createdBy;
    public Instant createdAt;
    /** Erster Tag des Co-Habits (Zone des Co-Habits). */
    public LocalDate startDate;
    public boolean archived;
    public Instant archivedAt;
    public List<Member> members = new ArrayList<>();
    public ChallengeState challengeState;
    public GoalState goalState;

    public Optional<Member> member(String personId) {
        return members.stream().filter(m -> m.personId.equals(personId)).findFirst();
    }

    public boolean isMember(String personId) {
        return member(personId).isPresent();
    }

    public Optional<Member> admin() {
        return members.stream().filter(m -> m.role == Role.ADMIN).findFirst();
    }
}
