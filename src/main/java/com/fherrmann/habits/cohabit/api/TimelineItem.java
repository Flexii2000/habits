package com.fherrmann.habits.cohabit.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record TimelineItem(String id, LocalDate day, Instant at, CohabitRef cohabit, String kind, PersonView person,
                           String title, String subtitle, String photoId, String caption, String reactionTarget,
                           List<ReactionView> reactions, boolean canReply, List<String> photoIds) {
}
