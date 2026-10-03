package com.fherrmann.habits.cohabit.api;

import java.time.Instant;
import java.util.List;

/** kind: TEXT | PHOTO | CHECKIN | SYSTEM. */
public record MessageView(String id, String cohabitId, String kind, PersonView author, boolean mine, Instant createdAt,
                          String text, String photoId, CheckinView checkin, String systemText, String reactionTarget,
                          List<ReactionView> reactions, boolean deleted, List<String> photoIds) {
}
