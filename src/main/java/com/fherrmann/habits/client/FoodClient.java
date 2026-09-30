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
import java.util.HashSet;
import java.util.Set;

/**
 * Liest einen Tag aus dem Kalorienzaehler ({@code /api/food/day}) - fuer die
 * Person, deren Tag gemeint ist.
 *
 * <p>Ueber localhost. Die Eigentuemerin meldet sich wie bisher mit dem
 * Privat-Cookie an (derselbe Token, den dieser Dienst prueft), jede weitere
 * Healthy-Person per Bearer mit ihrem eigenen Healthy-Token - so sieht der
 * Kalorienzaehler genau ihr Tagebuch. Nur das Noetige wird aus der Antwort
 * geholt (kcal gegessen, kcal-Ziel, welche Mahlzeiten einen Eintrag haben).
 */
@Component
public class FoodClient {

    /** Ein Tag, so weit er fuer "Track food" zaehlt. */
    public record Day(double kcal, double targetKcal, Set<String> meals) {
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String privateToken;
    private final HealthUsers users;

    public FoodClient(
            ObjectMapper objectMapper,
            @Value("${habits.food.url}") String baseUrl,
            @Value("${habits.security.token}") String privateToken,
            HealthUsers users) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.privateToken = privateToken;
        this.users = users;
    }

    public Day day(String personId, LocalDate date) {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/food/day?date=" + date))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(5))
                .GET();
        if (users.isOwner(personId)) {
            request.header("Cookie", "fh_private=" + privateToken);
        } else {
            String token = users.tokenOf(personId).orElseThrow(
                    () -> new SourceUnavailableException("Kalorienzähler (kein Healthy-Zugang)", 0));
            request.header("Authorization", "Bearer " + token);
        }
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new SourceUnavailableException("Kalorienzähler", response.statusCode());
            }
            return parse(objectMapper.readTree(response.body()));
        } catch (IOException e) {
            throw new SourceUnavailableException("Kalorienzähler", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SourceUnavailableException("Kalorienzähler", e);
        } catch (RuntimeException e) {
            if (e instanceof SourceUnavailableException) {
                throw e;
            }
            throw new SourceUnavailableException("Kalorienzähler", e);
        }
    }

    static Day parse(JsonNode root) {
        Set<String> meals = new HashSet<>();
        for (JsonNode entry : root.path("entries")) {
            JsonNode meal = entry.get("meal");
            if (meal != null && !meal.isNull()) {
                meals.add(meal.asString());
            }
        }
        return new Day(
                root.path("consumed").path("kcal").asDouble(0),
                root.path("targets").path("kcal").asDouble(0),
                meals);
    }
}
