package com.fherrmann.habits.cohabit.api;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;
import java.util.List;

public record StreakBlock(int current, String unit, String unitLabel, boolean atRisk, String remainingText,
                          Week week, int fulfillmentRate, Record record, Group group) {

    /** cells: DONE | MISSED | OPEN | PAUSED | FUTURE | NOT_DUE | BEFORE_JOIN. */
    public record Week(List<LocalDate> days, int todayIndex, List<Row> rows) {
    }

    public record Row(PersonView person, List<String> cells) {
    }

    public record Record(int value, @JsonProperty("short") String shortText, PersonView person) {
    }

    public record Group(int current, String unitLabel) {
    }
}
