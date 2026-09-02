package com.fherrmann.habits.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Die ganze Anwendung haengt hinter dem Privat-Modus-Cookie - siehe {@link PrivateCookie}.
 *
 * <p>nginx weist Anfragen ohne den Cookie schon ab, bevor sie hier ankommen
 * ({@code deploy/nginx-habits.conf}). Die zweite Pruefung hier ist trotzdem
 * keine Doppelung: sie haelt die App vor allem dicht, was auf dem Rechner
 * {@code 127.0.0.1:48190} direkt erreicht, und ein Fehler im nginx-Block
 * legt so nicht stillschweigend die Habits offen.
 *
 * <p>Kein Login, keine Registrierung, kein {@code /setup}: der Cookie wird
 * zentral auf fherrmann.com ausgestellt, und eine zweite Stelle, die Zugang
 * ausgeben kann, ist eine zu viel. Und kein CORS: es gibt keine
 * Weboberflaeche, die von einer anderen Origin aus liest - der einzige Client
 * ist die iOS-App.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PrivateCookieAuthFilter privateCookieAuthFilter(@Value("${habits.security.token}") String token) {
        return new PrivateCookieAuthFilter(token);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http, PrivateCookieAuthFilter privateCookieAuthFilter) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .addFilterBefore(privateCookieAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Schlicht 403, kein HTML mit Umleitung: es gibt keinen Browser,
                // den man irgendwohin schicken muesste.
                .exceptionHandling(eh -> eh.authenticationEntryPoint(
                        (request, response, ex) -> response.sendError(HttpServletResponse.SC_FORBIDDEN)))
                // Ein geteiltes Geheimnis in einem SameSite=Lax-Cookie, keine
                // Formulare, keine Sitzungen. Lax haelt den Cookie von
                // fremden Seiten fern - damit braucht es kein CSRF obendrauf.
                .csrf(csrf -> csrf.disable());
        return http.build();
    }
}
