package com.fherrmann.habits.cohabit.model;

import java.time.Instant;
import java.time.LocalDate;

public class Nudge {
    public String id;
    public String cohabitId;
    public String fromId;
    public String toId;
    public LocalDate day;
    public String text;
    public Instant createdAt;
    public Instant seenAt;
}
