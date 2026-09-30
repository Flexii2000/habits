package com.fherrmann.habits.cohabit.api;

import java.util.List;

public record AbstinenceBlock(int currentDays, int record, String toRecordText, List<MemberDays> members,
                              List<SeriesItem> series, Group group) {

    public record MemberDays(PersonView person, int days, boolean newPersonalRecord) {
    }

    public record SeriesItem(String label, int days, boolean current) {
    }

    public record Group(int days) {
    }
}
