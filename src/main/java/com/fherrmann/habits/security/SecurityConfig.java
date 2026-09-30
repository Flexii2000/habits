package com.fherrmann.habits.security;

import com.fherrmann.habits.cohabit.service.AppTokens;
import com.fherrmann.habits.cohabit.service.PeopleService;
import com.fherrmann.habits.controller.ErrorAdvice;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;
import java.time.Clock;

/**
 * Wer was darf.
 *
 * <p>{@code /cohabit/} liegt <b>ohne</b> Privat-Gate hinter nginx - Torben und alle
 * Eingeladenen haben kein {@code fh_private}. Dieser Dienst ist also die einzige
 * Schranke: die API verlangt eine Anmeldung (401 sonst), nur die Vorschau und das
 * Annehmen eines Einladungslinks sind oeffentlich, dazu die Weboberflaeche selbst
 * und {@code /cohabit/setup}.
 *
 * <p>{@code /habits/api/focus/**} (der Wald der Fokus-App) bleibt der Eigentuemerin
 * vorbehalten und antwortet wie bisher mit 403. nginx gated {@code /habits/}
 * weiterhin mit dem Privat-Cookie.
 *
 * <p>Kein CSRF-Schutz obendrauf: angemeldet wird mit einem Token im
 * SameSite=Lax-Cookie oder im Bearer-Kopf, nicht mit Formularen und Sitzungen.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Die Migration laeuft vor dem Start des Connectors (SmartInitializingSingleton) -
     * der Filter sieht also nie einen halb migrierten Stand.
     */
    @Bean
    public CohabitAuthFilter cohabitAuthFilter(@Value("${habits.security.token}") String token, HealthUsers users,
                                               AppTokens appTokens, PeopleService people, Clock clock) {
        return new CohabitAuthFilter(token, users, appTokens, people, clock);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, CohabitAuthFilter authFilter) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/cohabit/api/invite-links/**").permitAll()
                        .requestMatchers("/cohabit/api/**").authenticated()
                        .requestMatchers("/habits/api/focus/**").hasRole("OWNER")
                        .requestMatchers("/habits/api/habits", "/habits/api/habits/**").permitAll()
                        .requestMatchers("/cohabit", "/cohabit/**").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(authFilter, UsernamePasswordAuthenticationFilter.class)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint((request, response, ex) ->
                                deny(request, response, HttpServletResponse.SC_UNAUTHORIZED, "Nicht angemeldet."))
                        .accessDeniedHandler((request, response, ex) ->
                                deny(request, response, HttpServletResponse.SC_FORBIDDEN, "Keine Berechtigung.")))
                .csrf(csrf -> csrf.disable());
        return http.build();
    }

    /** coHabit bekommt JSON mit 401/403, alles andere wie bisher schlicht 403. */
    private static void deny(HttpServletRequest request, HttpServletResponse response, int status, String message)
            throws IOException {
        if (ErrorAdvice.isCohabitPath(request)) {
            response.setStatus(status);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"message\":\"" + message + "\"}");
        } else {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
        }
    }
}
