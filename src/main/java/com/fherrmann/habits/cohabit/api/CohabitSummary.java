package com.fherrmann.habits.cohabit.api;

import java.util.List;

public record CohabitSummary(CohabitRef ref, boolean archived, Headline headline, String typeLine, String subline,
                             String listLine, String status, String section, String unavailableText,
                             boolean canCheckIn, boolean photoRequired, String valueUnit, String checkInLabel,
                             List<PersonView> members, int memberCount, List<String> doneTodayBy,
                             ProgressView progress, RankView rank, int unreadMessages) {
}
