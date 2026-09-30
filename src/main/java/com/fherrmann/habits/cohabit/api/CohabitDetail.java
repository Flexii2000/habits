package com.fherrmann.habits.cohabit.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record CohabitDetail(CohabitSummary summary, CohabitConfigView config, String createdBy, Instant createdAt,
                            String myRole, boolean canInvite, Seats seats, List<MemberView> members,
                            List<String> rules, StreakBlock streak, AbstinenceBlock abstinence, GoalBlock goal,
                            ChallengeBlock challenge, HealthBlock health, List<CheckinView> myCheckins,
                            LocalDate backfillFrom, List<PauseView> myPauses, MySettings mySettings,
                            int unreadMessages, FinishedDialog dialog) {
}
