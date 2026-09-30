package com.fherrmann.habits.cohabit.api;

import java.util.List;

public record InviteCandidates(Seats seats, boolean canInvite, List<Candidate> people) {

    /** status: INVITE | INVITED | MEMBER. */
    public record Candidate(PersonView person, String status) {
    }
}
