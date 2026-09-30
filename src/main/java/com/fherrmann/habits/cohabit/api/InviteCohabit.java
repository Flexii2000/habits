package com.fherrmann.habits.cohabit.api;

import java.util.List;

/** Was eine eingeladene Person vorab sieht. */
public record InviteCohabit(CohabitRef ref, String typeLine, List<String> rules, List<PersonView> members,
                            Seats seats) {
}
