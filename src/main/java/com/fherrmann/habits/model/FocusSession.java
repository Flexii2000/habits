package com.fherrmann.habits.model;

import java.time.Duration;
import java.time.Instant;

/**
 * Eine abgeschlossene Fokus-Session aus dem Wald der Fokus-App: ein Baum.
 *
 * @param id    vergibt die App, damit ein Nachsenden aus dem Postausgang
 *              denselben Baum nicht zweimal pflanzt
 * @param start wann die Session begann
 * @param end   wann sie endete - Sessions lassen sich nicht abbrechen, jede
 *              gemeldete ist also voll durchgestanden
 */
public record FocusSession(String id, Instant start, Instant end) {

    public int minutes() {
        return (int) Duration.between(start, end).toMinutes();
    }
}
