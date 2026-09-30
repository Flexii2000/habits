package com.fherrmann.habits.security;

/** Womit sich eine Anfrage angemeldet hat - wichtig fuer Abmelden und "den eigenen Token widerrufen". */
public enum AuthVia {
    BEARER_APP, BEARER_HEALTH, BEARER_PRIVATE, COOKIE_PRIVATE, COOKIE_APP, COOKIE_HEALTH;

    public boolean isAppToken() {
        return this == BEARER_APP || this == COOKIE_APP;
    }
}
