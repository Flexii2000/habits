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
    /** Das Foto ist ein eigenes, animiertes GIF. */
    public boolean photoAnimated;
    /** Bei kind GIF: das GIF aus der Suche. */
    public Gif gif;
    /** Bei einem Check-in-Post alle Fotos des Eintrags; fehlt bei Posts von vorher. */
    public List<String> photoIds;
    public String checkinId;
    public String systemText;
    /** Bei Systemmeldungen: wer sie ausgeloest hat - fuer die ist sie nicht ungelesen. */
    public String actorId;
    /** Bei einem Check-in-Post: das Ereignis, dessen Reaktionen der Post zeigt. */
    public String eventId;
    public List<Reaction> reactions = new ArrayList<>();
    public boolean deleted;
}
