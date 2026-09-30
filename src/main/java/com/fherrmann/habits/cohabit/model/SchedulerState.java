package com.fherrmann.habits.cohabit.model;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Was der Scheduler schon verschickt hat ({@code reminder:<cohabit>:<person>:<tag>} -> wann),
 * damit ein Neustart keine Erinnerung doppelt schickt.
 */
public class SchedulerState {
    public Map<String, Instant> sent = new LinkedHashMap<>();
}
