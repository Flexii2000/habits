package com.fherrmann.habits.cohabit.api;

import java.util.List;

/**
 * @param limit nur bei einer Abstinenz mit Tageslimit: heute gegen das Limit (seit 12.10.)
 */
public record CohabitSummary(CohabitRef ref, boolean archived, Headline headline, String typeLine, String subline,
                             String listLine, String status, String section, String unavailableText,
                             boolean canCheckIn, boolean photoRequired, String valueUnit, String checkInLabel,
                             boolean runEntry, List<PersonView> members, int memberCount, List<String> doneTodayBy,
                             ProgressView progress, RankView rank, int unreadMessages, LimitView limit) {

    public CohabitSummary(CohabitRef ref, boolean archived, Headline headline, String typeLine, String subline,
                          String listLine, String status, String section, String unavailableText,
                          boolean canCheckIn, boolean photoRequired, String valueUnit, String checkInLabel,
                          boolean runEntry, List<PersonView> members, int memberCount, List<String> doneTodayBy,
                          ProgressView progress, RankView rank, int unreadMessages) {
        this(ref, archived, headline, typeLine, subline, listLine, status, section, unavailableText, canCheckIn,
                photoRequired, valueUnit, checkInLabel, runEntry, members, memberCount, doneTodayBy, progress, rank,
                unreadMessages, null);
    }
}
