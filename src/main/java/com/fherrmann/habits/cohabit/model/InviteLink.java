package com.fherrmann.habits.cohabit.model;

import java.time.Instant;

/** Ein teilbarer Link {@code /cohabit/join/<code>} - fuer ein Co-Habit oder als Freundes-Link. */
public class InviteLink {
    public String code;
    public InviteLinkKind kind;
    public String cohabitId;
    public String createdBy;
    public Instant createdAt;
    public Instant expiresAt;
    public int uses;
}
