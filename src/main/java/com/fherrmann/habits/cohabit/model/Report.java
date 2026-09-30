package com.fherrmann.habits.cohabit.model;

import java.time.Instant;

/** Eine Meldung - mit einer Kopie des Gemeldeten, falls die Nachricht spaeter geloescht wird. */
public class Report {
    public String id;
    public String reporterId;
    public String cohabitId;
    public String messageId;
    public String authorId;
    public String text;
    public String photoId;
    public String reason;
    public Instant createdAt;
}
