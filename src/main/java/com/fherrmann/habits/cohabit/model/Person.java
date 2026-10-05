package com.fherrmann.habits.cohabit.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Eine Person. Die ID aendert sich nie: fuer Healthy-Personen ihr Name aus
 * HEALTH_TOKENS, fuer eingeladene {@code u-} plus zwoelf Hexzeichen.
 */
public class Person {
    public String id;
    public String displayName;
    public String username;
    public String color;
    public String avatarPhotoId;
    public Instant createdAt;
    public NotificationPrefs notifications = new NotificationPrefs();
    public List<AppToken> appTokens = new ArrayList<>();
    public List<Device> devices = new ArrayList<>();
    /** Wen diese Person blockiert hat. */
    public List<String> blocked = new ArrayList<>();
    /** Zufaellige Kennung fuer KLIPYs {@code customer_id} - nicht die Personen-ID. */
    public String gifCustomerId;
}
