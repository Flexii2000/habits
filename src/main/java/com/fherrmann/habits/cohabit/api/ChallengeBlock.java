package com.fherrmann.habits.cohabit.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record ChallengeBlock(int round, LocalDate start, LocalDate end, Instant endsAt, String endsInText,
                             String periodLabel, String scoring, String scoringText, Number target, String stake,
                             String recurrence, String recurrenceText, int myRank, List<LeaderRow> leaderboard,
                             List<PastRound> pastRounds, boolean finished) {

    public record LeaderRow(int rank, PersonView person, Number score, String scoreText, double fraction) {
    }

    public record PastRound(String label, List<PersonView> winners) {
    }
}
