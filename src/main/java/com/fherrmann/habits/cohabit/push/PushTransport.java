package com.fherrmann.habits.cohabit.push;

/** Ein Zustellweg: APNs fuer iOS, Firebase fuer Android. */
public interface PushTransport {

    /** {@code ios} oder {@code android} - wie die Apps sich anmelden. */
    String platform();

    boolean isConfigured();

    /**
     * @return {@code false}, wenn der Dienst die Kennung abgelehnt hat und sie weg soll;
     *         sonst {@code true}, auch bei voruebergehenden Fehlern
     */
    boolean send(String deviceToken, PushMessage message);
}
