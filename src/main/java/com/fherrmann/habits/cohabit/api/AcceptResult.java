package com.fherrmann.habits.cohabit.api;

/** Antwort auf das Annehmen eines Einladungslinks; {@code token} nur, wenn eine Person neu entstand. */
public record AcceptResult(MeView me, String token, String setupUrl, String cohabitId) {
}
