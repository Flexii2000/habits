package com.fherrmann.habits.cohabit.api;

import java.time.LocalDate;
import java.util.List;

/** {@code GET /me/days}: jedes eigene Co-Habit als Tagesreihe - fuer das Logbook in Healthy. */
public record DaysView(LocalDate from, LocalDate to, List<DaySeriesView> series) {
}
