package com.fherrmann.habits.cohabit.api;

import java.util.List;

/**
 * @param limitDays nur bei einer Abstinenz mit Tageslimit: die Tage der Nachtragsfrist, neueste
 *                  zuerst, mit der Zahl aus dem Logbook (seit 12.10.)
 */
public record AbstinenceBlock(int currentDays, int record, String toRecordText, List<MemberDays> members,
                              List<SeriesItem> series, Group group, List<LimitDayView> limitDays) {

    public AbstinenceBlock(int currentDays, int record, String toRecordText, List<MemberDays> members,
                           List<SeriesItem> series, Group group) {
        this(currentDays, record, toRecordText, members, series, group, null);
    }

    public record MemberDays(PersonView person, int days, boolean newPersonalRecord) {
    }

    public record SeriesItem(String label, int days, boolean current) {
    }

    public record Group(int days) {
    }
}
