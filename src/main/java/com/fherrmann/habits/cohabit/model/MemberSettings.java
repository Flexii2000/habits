package com.fherrmann.habits.cohabit.model;

/**
 * Einstellungen eines Mitglieds fuer ein Co-Habit. {@code checkins} und
 * {@code chat} sind {@code null}, solange die globalen Schalter gelten.
 */
public class MemberSettings {
    public boolean muted;
    public Boolean checkins;
    public Boolean chat;
    public boolean shareBreaks;
    public boolean healthConsent;
}
