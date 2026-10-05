package com.fherrmann.habits.cohabit.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** {@code typeColors} wie in {@code MeView} - die Widgets faerben nach Typ, ohne die App zu fragen. */
public record WidgetData(Instant generatedAt, int openCount, List<Item> cohabits, Challenge challenge,
                         TeamGoal teamGoal, OpenStreak openStreak, Map<String, String> typeColors) {

    public record Item(CohabitRef ref, String value, String unit, String sub, String status, String statusText,
                       boolean photoRequired, boolean quickCheckIn) {
    }

    public record Challenge(CohabitRef ref, String endsText, int myRank, List<Row> leaderboard) {
    }

    public record Row(int rank, String name, Number score, boolean me) {
    }

    public record TeamGoal(CohabitRef ref, int percent) {
    }

    public record OpenStreak(CohabitRef ref, String text) {
    }
}
