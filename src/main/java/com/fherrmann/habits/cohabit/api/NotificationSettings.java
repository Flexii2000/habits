package com.fherrmann.habits.cohabit.api;

import com.fherrmann.habits.cohabit.model.NotificationPrefs;

/** Beim Speichern darf jedes Feld fehlen ({@code null}) - dann bleibt es, wie es ist. */
public record NotificationSettings(Boolean checkins, Boolean photos, Boolean chat, Boolean nudges, Boolean invites,
                                   Boolean reminders, Boolean streakAtRisk, Boolean challengeEnd) {

    public static NotificationSettings of(NotificationPrefs p) {
        return new NotificationSettings(p.checkins, p.photos, p.chat, p.nudges, p.invites, p.reminders,
                p.streakAtRisk, p.challengeEnd);
    }

    public void applyTo(NotificationPrefs p) {
        if (checkins != null) {
            p.checkins = checkins;
        }
        if (photos != null) {
            p.photos = photos;
        }
        if (chat != null) {
            p.chat = chat;
        }
        if (nudges != null) {
            p.nudges = nudges;
        }
        if (invites != null) {
            p.invites = invites;
        }
        if (reminders != null) {
            p.reminders = reminders;
        }
        if (streakAtRisk != null) {
            p.streakAtRisk = streakAtRisk;
        }
        if (challengeEnd != null) {
            p.challengeEnd = challengeEnd;
        }
    }
}
