package com.fherrmann.habits.cohabit.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class Message {
    public String id;
    public String cohabitId;
    public MessageKind kind;
    public String authorId;
    public Instant createdAt;
    public String text;
    public String photoId;
    public String checkinId;
    public String systemText;
    /** Bei einem Check-in-Post: das Ereignis, dessen Reaktionen der Post zeigt. */
    public String eventId;
    public List<Reaction> reactions = new ArrayList<>();
    public boolean deleted;
}
