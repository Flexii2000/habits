package com.fherrmann.habits.cohabit.api;

import java.time.LocalDate;
import java.util.List;

public record GoalBlock(Number target, String targetText, String unitLabel, LocalDate deadline, String counting,
                        String mode, String typeLine, Number total, String totalText, int percent,
                        Number planDelta, String planDeltaText, int remainingDays, String remainingText,
                        List<Contribution> contributions, Finished finished) {

    public record Contribution(PersonView person, Number value, String valueText, double fraction) {
    }

    public record Finished(boolean reached, String text) {
    }
}
