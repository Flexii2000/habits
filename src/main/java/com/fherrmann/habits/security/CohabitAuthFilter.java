package com.fherrmann.habits.security;

import com.fherrmann.habits.cohabit.service.AppTokens;
import com.fherrmann.habits.cohabit.service.PeopleService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Meldet an, wer einen gueltigen Zugang mitbringt. Der erste gueltige Treffer
 * gewinnt, ungueltige fallen zur naechsten Stufe durch:
 * <ol>
 *   <li>{@code Authorization: Bearer} - App-Token, Healthy-Token oder FH_PRIVATE_TOKEN</li>
 *   <li>Cookie {@code fh_private} - die Eigentuemerin. <b>Geht allen anderen Cookies
 *       vor:</b> ein fremder {@code health_token} darf Felix' Browser nie uebernehmen
 *       (beim Kalorienzaehler am 2026-09-26 passiert)</li>
 *   <li>Cookie {@code cohabit_token} - vom Dienst selbst gesetzt</li>
 *   <li>Cookie {@code health_token} - der Healthy-Zugang einer Person</li>
 * </ol>
 * Healthy-Personen, die coHabit noch nicht kennt, entstehen beim ersten Zugriff.
 */
public class CohabitAuthFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final String privateToken;
    private final HealthUsers users;
    private final AppTokens appTokens;
    private final PeopleService people;
    private final Clock clock;

    public CohabitAuthFilter(String privateToken, HealthUsers users, AppTokens appTokens, PeopleService people,
                             Clock clock) {
        this.privateToken = privateToken;
        this.users = users;
        this.appTokens = appTokens;
        this.people = people;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        resolve(request).ifPresent(viewer -> {
            List<SimpleGrantedAuthority> roles = new ArrayList<>();
            roles.add(new SimpleGrantedAuthority("ROLE_USER"));
            if (viewer.owner()) {
                roles.add(new SimpleGrantedAuthority("ROLE_OWNER"));
            }
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(viewer, null, roles));
        });
        chain.doFilter(request, response);
    }

    /**
     * Auch beim ERROR-Dispatch pruefen: sonst ist der Sicherheitskontext beim
     * internen Weiterreichen nach {@code /error} leer, und aus jedem echten
     * Fehlerstatus wird ein 401/403. Dieselbe Falle wie beim Kalorienzaehler.
     */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    Optional<Viewer> resolve(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER)) {
            String token = header.substring(BEARER.length()).trim();
            Optional<Viewer> viewer = fromAppToken(token, AuthVia.BEARER_APP)
                    .or(() -> users.nameFor(token).map(name -> health(name, AuthVia.BEARER_HEALTH)))
                    .or(() -> matchesPrivate(token)
                            ? Optional.of(health(users.owner(), AuthVia.BEARER_PRIVATE))
                            : Optional.empty());
            if (viewer.isPresent()) {
                return viewer;
            }
        }
        for (String value : AuthCookies.values(request, AuthCookies.PRIVATE)) {
            if (matchesPrivate(value)) {
                return Optional.of(health(users.owner(), AuthVia.COOKIE_PRIVATE));
            }
        }
        for (String value : AuthCookies.values(request, AuthCookies.COHABIT)) {
            Optional<Viewer> viewer = fromAppToken(value, AuthVia.COOKIE_APP);
            if (viewer.isPresent()) {
                return viewer;
            }
        }
        for (String value : AuthCookies.values(request, AuthCookies.HEALTH)) {
            Optional<String> name = users.nameFor(value);
            if (name.isPresent()) {
                return Optional.of(health(name.get(), AuthVia.COOKIE_HEALTH));
            }
        }
        return Optional.empty();
    }

    private Optional<Viewer> fromAppToken(String token, AuthVia via) {
        return appTokens.lookup(token).map(match -> {
            appTokens.touch(match, Instant.now(clock));
            return new Viewer(match.personId(), via, match.tokenId(), users.isOwner(match.personId()));
        });
    }

    private Viewer health(String name, AuthVia via) {
        people.ensureHealthPerson(name);
        return new Viewer(name, via, null, users.isOwner(name));
    }

    private boolean matchesPrivate(String supplied) {
        if (supplied == null || privateToken == null || privateToken.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(supplied.getBytes(StandardCharsets.UTF_8),
                privateToken.getBytes(StandardCharsets.UTF_8));
    }
}
