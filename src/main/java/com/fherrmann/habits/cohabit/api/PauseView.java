package com.fherrmann.habits.cohabit.api;

import java.time.LocalDate;

public record PauseView(String id, LocalDate from, LocalDate to) {
}
