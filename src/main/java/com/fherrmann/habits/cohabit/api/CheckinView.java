package com.fherrmann.habits.cohabit.api;

import java.time.Instant;
import java.time.LocalDate;

public record CheckinView(String id, String cohabitId, PersonView person, String kind, LocalDate date,
                          Instant createdAt, Number value, String valueText, String note, String photoId,
                          String caption, String source, boolean editable, RunView run) {
}
