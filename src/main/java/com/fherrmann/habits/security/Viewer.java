package com.fherrmann.habits.security;

/**
 * Wer die Anfrage stellt.
 *
 * @param tokenId bei einem App-Token dessen ID - damit {@code DELETE /me/app-links/current}
 *                genau diesen widerrufen kann
 */
public record Viewer(String personId, AuthVia via, String tokenId, boolean owner) {
}
