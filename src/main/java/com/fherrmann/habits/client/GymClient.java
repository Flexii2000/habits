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
 * Das Gym von Healthy im Weight Tracker ({@code GET /api/gym/days}): an welchen Tagen eine
 * Person ein Training beendet hat - fuer Co-Habits mit der Messgroesse
 * {@link com.fherrmann.habits.cohabit.model.HealthMetric#GYM}.
 *
 * <p>Zugang wie beim {@link LogbookClient}: die Eigentuemerin mit {@code WEIGHT_APP_TOKEN} als
 * Cookie, jede weitere Healthy-Person per Bearer mit ihrem Token.
 */
@Component
public class GymClient {

    private static final String SOURCE = "Gym";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String ownerToken;
    private final HealthUsers users;

    public GymClient(
            ObjectMapper objectMapper,
            @Value("${habits.weight.url}") String baseUrl,
            @Value("${habits.weight.token}") String ownerToken,
            HealthUsers users) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.ownerToken = ownerToken;
        this.users = users;
    }

    /**
     * Je Tag mit beendetem Training die Zahl der Trainings; Tage ohne fehlen.
     *
     * @param timeout beim Oeffnen der App kurz - eine langsame Quelle darf die Ansicht nicht aufhalten
     */
    public Map<LocalDate, Integer> trainingDays(String personId, LocalDate from, LocalDate to, Duration timeout) {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/gym/days?from=" + from + "&to=" + to))
                .header("Accept", "application/json")
                .timeout(timeout)
                .GET();
        if (users.isOwner(personId)) {
            if (ownerToken == null || ownerToken.isBlank()) {
                throw new SourceUnavailableException(SOURCE + " (kein WEIGHT_APP_TOKEN)", 0);
            }
            request.header("Cookie", "weight_app_token=" + ownerToken);
        } else {
            String token = users.tokenOf(personId).orElseThrow(
                    () -> new SourceUnavailableException(SOURCE + " (kein Healthy-Zugang)", 0));
            request.header("Authorization", "Bearer " + token);
        }
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new SourceUnavailableException(SOURCE, response.statusCode());
            }
            Map<LocalDate, Integer> days = new HashMap<>();
            for (JsonNode day : objectMapper.readTree(response.body())) {
                days.put(LocalDate.parse(day.path("date").asString()), day.path("trainings").asInt(1));
            }
            return days;
        } catch (IOException | RuntimeException e) {
            if (e instanceof SourceUnavailableException unavailable) {
                throw unavailable;
            }
            throw new SourceUnavailableException(SOURCE, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SourceUnavailableException(SOURCE, e);
        }
    }
}
