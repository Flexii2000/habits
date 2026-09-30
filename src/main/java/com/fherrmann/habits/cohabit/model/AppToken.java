package com.fherrmann.habits.cohabit.model;

import java.time.Instant;

/**
 * Ein App-Link. Gespeichert wird nur der SHA-256 des Tokens - wer die Datei
 * liest, kann sich damit nicht anmelden.
 */
public class AppToken {
    public String id;
    public String label;
    public String hash;
    public Instant createdAt;
    public Instant lastUsedAt;
}
