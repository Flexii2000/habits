package com.fherrmann.habits.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Die Cookies, die hier gelesen werden - und das eine, das der Dienst selbst setzt. */
public final class AuthCookies {

    public static final String PRIVATE = "fh_private";
    public static final String COHABIT = "cohabit_token";
    public static final String HEALTH = "health_token";
    public static final Duration COHABIT_MAX_AGE = Duration.ofDays(5 * 365);

    private AuthCookies() {
    }

    /**
     * {@code cohabit_token}: nur fuer {@code /cohabit}, HttpOnly, SameSite=Lax, fuenf Jahre.
     * Secure, sobald die Anfrage ueber HTTPS kam (hinter nginx immer) - lokal ueber
     * http waere ein Secure-Cookie in manchen Browsern gleich wieder weg.
     */
    public static ResponseCookie cohabit(String token, HttpServletRequest request) {
        return ResponseCookie.from(COHABIT, token)
                .httpOnly(true)
                .secure(request.isSecure())
                .sameSite("Lax")
                .path("/cohabit")
                .maxAge(COHABIT_MAX_AGE)
                .build();
    }

    public static ResponseCookie clearCohabit(HttpServletRequest request) {
        return ResponseCookie.from(COHABIT, "")
                .httpOnly(true)
                .secure(request.isSecure())
                .sameSite("Lax")
                .path("/cohabit")
                .maxAge(Duration.ZERO)
                .build();
    }

    /** Alle Werte eines Cookies - es kann mehrere gleichnamige geben (Host und Domain). */
    public static List<String> values(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isEmpty()) {
                values.add(cookie.getValue());
            }
        }
        return values;
    }
}
