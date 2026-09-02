package com.fherrmann.habits.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Der geteilte Privat-Modus-Cookie von fherrmann.com.
 *
 * <p>Diese App stellt ihn weder aus noch erneuert sie ihn: gesetzt wird er
 * einmal je Geraet ueber {@code https://fherrmann.com/setup?token=…} auf
 * {@code Domain=.fherrmann.com} - und weil dieser Dienst unter
 * {@code fherrmann.com/habits} liegt, kommt er hier ohne Weiteres an. Der
 * Token selbst steht in {@code /etc/nginx/conf.d/private-mode.conf} und
 * erreicht die App ueber {@code FH_PRIVATE_TOKEN}.
 */
final class PrivateCookie {

    static final String NAME = "fh_private";

    private PrivateCookie() {
    }

    static boolean matches(String supplied, String expected) {
        if (supplied == null || expected == null) {
            return false;
        }
        return MessageDigest.isEqual(
                supplied.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }
}
