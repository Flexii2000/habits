package com.fherrmann.habits.client;

import com.fherrmann.habits.security.HealthUsers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * Liest Tagesschritte aus dem Weight Tracker ({@code /api/steps}) - fuer eine
 * bestimmte Person.
 *
 * <p>Die Eigentuemerin mit dem alten Einzeltoken ({@code WEIGHT_APP_TOKEN}) als
 * Cookie, wie bisher; jede weitere Healthy-Person per Bearer mit ihrem Token.
 * Jede Anfrage fragt frisch nach, ein Zeitraum ist ein Aufruf. Tage ohne Messung
 * fehlen in der Antwort - und zaehlen hier als null.
 */
@Component
public class StepsClient {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String ownerToken;
    private final HealthUsers users;

    public StepsClient(
            ObjectMapper objectMapper,
            @Value("${habits.weight.url}") String baseUrl,
            @Value("${habits.weight.token}") String ownerToken,
            HealthUsers users) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.ownerToken = ownerToken;
        this.users = users;
    }

    /** Schritte je Tag im Zeitraum (beide Enden einschliesslich). */
    public Map<LocalDate, Integer> steps(String personId, LocalDate from, LocalDate to) {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/steps?from=" + from + "&to=" + to))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(5))
                .GET();
        if (users.isOwner(personId)) {
            if (ownerToken == null || ownerToken.isBlank()) {
                throw new SourceUnavailableException("Weight Tracker (kein WEIGHT_APP_TOKEN)", 0);
            }
            request.header("Cookie", "weight_app_token=" + ownerToken);
        } else {
            String token = users.tokenOf(personId).orElseThrow(
                    () -> new SourceUnavailableException("Weight Tracker (kein Healthy-Zugang)", 0));
            request.header("Authorization", "Bearer " + token);
        }
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new SourceUnavailableException("Weight Tracker", response.statusCode());
            }
            return parse(objectMapper.readTree(response.body()));
        } catch (IOException e) {
            throw new SourceUnavailableException("Weight Tracker", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SourceUnavailableException("Weight Tracker", e);
        } catch (RuntimeException e) {
            if (e instanceof SourceUnavailableException) {
                throw e;
            }
            throw new SourceUnavailableException("Weight Tracker", e);
        }
    }

    static Map<LocalDate, Integer> parse(JsonNode root) {
        Map<LocalDate, Integer> steps = new HashMap<>();
        for (JsonNode day : root) {
            steps.put(LocalDate.parse(day.path("date").asString()), day.path("steps").asInt(0));
        }
        return steps;
    }
}
