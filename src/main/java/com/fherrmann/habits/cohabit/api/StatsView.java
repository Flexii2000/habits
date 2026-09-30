package com.fherrmann.habits.cohabit.api;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;
import java.util.List;

public record StatsView(String range, String label, int fulfillmentRate, LongestStreak longestStreak,
                        Heatmap heatmap, List<CohabitProgress> cohabits) {

    public record LongestStreak(@JsonProperty("short") String shortText, CohabitRef cohabit) {
    }

    public record Heatmap(LocalDate from, LocalDate to, List<Day> days) {
    }

    public record Day(LocalDate date, int count, int level) {
    }

    public record CohabitProgress(CohabitRef ref, String progressText, double fraction) {
    }
}
