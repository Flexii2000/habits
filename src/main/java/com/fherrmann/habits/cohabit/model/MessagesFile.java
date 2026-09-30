package com.fherrmann.habits.cohabit.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code messages/<cohabitId>.json}: der Chat samt Lesestand je Person. */
public class MessagesFile {
    public List<Message> messages = new ArrayList<>();
    public Map<String, String> readState = new LinkedHashMap<>();
}
