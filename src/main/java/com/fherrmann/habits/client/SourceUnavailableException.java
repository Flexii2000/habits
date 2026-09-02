package com.fherrmann.habits.client;

/**
 * Eine Quelle (Kalorienzaehler, Weight Tracker) hat nicht geantwortet.
 *
 * <p>Kein HTTP-Fehler fuer die App: die uebrigen Habits gehen weiter, nur
 * dieses eine traegt dann einen Hinweis statt einer Straehne.
 */
public class SourceUnavailableException extends RuntimeException {

    public SourceUnavailableException(String source, Throwable cause) {
        super(source + " nicht erreichbar", cause);
    }

    public SourceUnavailableException(String source, int status) {
        super(source + " antwortete mit HTTP " + status);
    }
}
