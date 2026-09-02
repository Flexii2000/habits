package com.fherrmann.habits.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/** Laesst Anfragen mit gueltigem {@link PrivateCookie#NAME} durch, alles andere bleibt unangemeldet. */
public class PrivateCookieAuthFilter extends OncePerRequestFilter {

    private final String token;

    public PrivateCookieAuthFilter(String token) {
        this.token = token;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (hasValidCookie(request)) {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    "device", null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        }
        chain.doFilter(request, response);
    }

    /**
     * Auch beim ERROR-Dispatch pruefen.
     *
     * <p>Sonst ist der Sicherheitskontext leer, wenn der Container eine
     * Ausnahme intern nach {@code /error} weiterreicht - und Spring Security
     * ersetzt jeden echten Fehlerstatus durch ein 403. Aus "Name fehlt" wuerde
     * "nicht autorisiert". Dieselbe Falle wie beim Kalorienzaehler und beim
     * Weight Tracker; MockMvc fuehrt den zweiten Durchlauf nicht aus und sieht
     * davon nichts.
     */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    private boolean hasValidCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return false;
        }
        for (Cookie cookie : cookies) {
            if (PrivateCookie.NAME.equals(cookie.getName()) && PrivateCookie.matches(cookie.getValue(), token)) {
                return true;
            }
        }
        return false;
    }
}
