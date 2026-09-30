package com.fherrmann.habits.cohabit.model;

import java.time.LocalDate;

/** {@code start} ist der Anlagetag und laesst sich nicht aendern. */
public record GoalConfig(Double target, LocalDate start, LocalDate deadline, GoalCounting counting, GoalMode mode) {
}
