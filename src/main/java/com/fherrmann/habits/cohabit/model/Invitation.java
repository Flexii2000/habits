package com.fherrmann.habits.cohabit.model;

import java.time.Instant;

/** Eine offene Einladung - belegt einen Platz, bis sie angenommen oder abgelehnt ist. */
public class Invitation {
    public String id;
    public String cohabitId;
    public String fromId;
    public String toId;
    public Instant createdAt;
}
