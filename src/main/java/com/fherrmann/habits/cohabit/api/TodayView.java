package com.fherrmann.habits.cohabit.api;

import java.time.LocalDate;
import java.util.List;

public record TodayView(LocalDate date, int openCount, String headline, List<NudgeView> nudges, NewPhotos newPhotos,
                        List<InvitationView> invitations, List<CohabitSummary> cohabits) {

    public record NewPhotos(int count, List<String> photoIds) {
    }
}
