package com.fherrmann.habits.model;

import java.time.Instant;

/**
 * Eine Kategorie fuer die Baeume im Wald ("Bachelorarbeit", "Uni") - Felix vergibt
 * die Namen selbst. Geloescht wird nur markiert: alte Baeume und Co-Habits, die nach
 * der Kategorie zaehlen, behalten so ihren Namen; in der Auswahl erscheint sie nicht mehr.
 *
 * @param id vergibt die App (Postausgang ohne Doppelte) oder der Dienst
 */
public record FocusCategory(String id, String name, Instant createdAt, boolean deleted) {
}
