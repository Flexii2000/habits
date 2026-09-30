package com.fherrmann.habits.cohabit.model;

import java.util.ArrayList;
import java.util.List;

/** Alles in {@code cohabits.json}. */
public class CohabitsData {
    public List<Cohabit> cohabits = new ArrayList<>();
    public List<Invitation> invitations = new ArrayList<>();
    public List<InviteLink> inviteLinks = new ArrayList<>();
}
