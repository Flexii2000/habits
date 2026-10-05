package com.fherrmann.habits.cohabit.api;

import java.util.List;

/** {@code reaction} und {@code label} sind das Emoji; {@code people} in der Reihenfolge der Reaktionen. */
public record ReactionView(String reaction, String label, int count, boolean mine, List<PersonView> people) {
}
