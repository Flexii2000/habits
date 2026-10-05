package com.fherrmann.habits.cohabit.api;

import java.time.Instant;
import java.util.List;

/**
 * kind: TEXT | PHOTO | GIF | CHECKIN | SYSTEM. {@code photoAnimated}: das Foto ist ein
 * eigenes GIF; {@code gif}: ein GIF aus der Suche (kind GIF).
 */
public record MessageView(String id, String cohabitId, String kind, PersonView author, boolean mine, Instant createdAt,
                          String text, String photoId, CheckinView checkin, String systemText, String reactionTarget,
                          List<ReactionView> reactions, boolean deleted, List<String> photoIds, boolean photoAnimated,
                          GifView gif) {
}
