package com.fherrmann.habits.cohabit.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Eine beendete Runde - bleibt fuer "Fruehere Runden", Siege und den Abschlussdialog. */
public class ChallengeRound {
    public int number;
    public LocalDate start;
    public LocalDate end;
    public Instant endedAt;
    public String label;
    public List<Standing> standings = new ArrayList<>();
    public List<String> winners = new ArrayList<>();
    public List<String> losers = new ArrayList<>();
    public String stake;
    /** Die Systemmeldung zum Ende - darauf setzt "Gratulieren" seine Reaktion. */
    public String messageId;
    /** Wann die naechste Runde beginnt, falls es eine gibt. */
    public LocalDate nextStart;
}
