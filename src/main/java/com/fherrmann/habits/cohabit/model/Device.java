package com.fherrmann.habits.cohabit.model;

import java.time.Instant;

/** Eine Push-Kennung: {@code ios} (APNs) oder {@code android} (Firebase). */
public class Device {
    public String token;
    public String platform;
    public Instant registeredAt;
}
