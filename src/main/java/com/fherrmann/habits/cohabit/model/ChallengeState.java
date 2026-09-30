package com.fherrmann.habits.cohabit.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Wo eine Challenge gerade steht. Die Tage der aktuellen Runde stehen in der Konfiguration. */
public class ChallengeState {
    public int currentRound = 1;
    /** Gesetzt, sobald die aktuelle Runde ausgewertet ist; bei Wiederholung faengt die naechste wieder mit null an. */
    public Instant roundEndedAt;
    public boolean roundStartAnnounced;
    public boolean endingNotified;
    public List<ChallengeRound> rounds = new ArrayList<>();
}
