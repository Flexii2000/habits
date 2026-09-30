package com.fherrmann.habits.cohabit.api;

import java.util.List;

/**
 * Der Abschlussdialog einer Challenge-Runde oder eines Ziels. {@code reactionTarget}
 * ist eine Ergaenzung zum Vertrag: die Systemmeldung zum Ende, auf die "Gratulieren"
 * seine Reaktion setzt.
 */
public record FinishedDialog(String id, String kind, String title, List<PodiumEntry> podium, String stakeText,
                             String nextText, String reactionTarget) {

    public record PodiumEntry(int rank, PersonView person, Number score, String scoreText) {
    }
}
