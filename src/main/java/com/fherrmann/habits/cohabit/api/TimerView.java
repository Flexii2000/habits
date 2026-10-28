package com.fherrmann.habits.cohabit.api;

import java.time.Instant;

/**
 * Der Timer eines Co-Habits mit {@code tracking.timer}: {@code startedAt} ist der Start des
 * laufenden Timers der fragenden Person, {@code null}, wenn er steht. Hochzaehlen tun die Apps.
 */
public record TimerView(Instant startedAt) {
}
