package com.fherrmann.habits.cohabit.api;

/** {@code relation}: FRIEND | REQUEST_SENT | REQUEST_RECEIVED | NONE | SELF. */
public record SearchResult(PersonView person, String relation) {
}
