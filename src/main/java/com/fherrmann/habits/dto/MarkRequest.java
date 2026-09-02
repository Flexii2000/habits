package com.fherrmann.habits.dto;

import java.time.LocalDate;

/** Ein Haken (BUILD) oder Rueckfall (QUIT). Ohne Datum: heute. */
public record MarkRequest(LocalDate date) {
}
