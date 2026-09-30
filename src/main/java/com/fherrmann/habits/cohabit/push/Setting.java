package com.fherrmann.habits.cohabit.push;

/** Welcher Schalter ueber eine Benachrichtigung entscheidet. */
public enum Setting {
    CHECKINS, PHOTOS, CHAT, NUDGES, INVITES, REMINDERS, STREAK_AT_RISK, CHALLENGE_END,
    /** Meilensteine und Bestserien anderer - folgen dem Check-in-Schalter. */
    MILESTONES,
    /** Meldungen an Felix, App-Updates: immer. */
    ALWAYS
}
