package com.fherrmann.habits.cohabit.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Member {
    public String personId;
    public Role role;
    public Instant joinedAt;
    /** Ab diesem Tag (Zone des Co-Habits) zaehlt die Person - der Tag des Beitritts. */
    public LocalDate startDate;
    public MemberSettings settings = new MemberSettings();
    public List<Pause> pauses = new ArrayList<>();
    /** Abschlussdialoge, die die Person schon gesehen hat ({@code challenge-3}, {@code goal}). */
    public List<String> dialogsSeen = new ArrayList<>();
    public Instant lastHealthSyncAt;
    /** Laengste je erreichte Serie in diesem Co-Habit - Massstab fuer eine neue Bestserie. */
    public int bestStreak;
    /** Beginn der Serie, fuer die die Bestserie schon gemeldet wurde - eine Meldung je Serie. */
    public LocalDate bestAnnouncedRun;
    /** Erreichte Meilensteine je Serie: Beginn der Serie -> Werte. */
    public Map<String, List<Integer>> milestones = new LinkedHashMap<>();
    /**
     * Bestserie und Meilensteine muessen erst still erfasst werden (aus der Migration
     * uebernommene automatische Co-Habits) - sonst meldete der erste Lauf alte Erfolge als neu.
     */
    public boolean baselinePending;
}
