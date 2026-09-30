package com.fherrmann.habits.cohabit.api;

import com.fherrmann.habits.cohabit.model.Cohabit;
import com.fherrmann.habits.cohabit.model.CohabitType;

public record CohabitRef(String id, String name, String color, CohabitType type) {

    public static CohabitRef of(Cohabit c) {
        return new CohabitRef(c.id, c.name, c.color, c.type);
    }
}
