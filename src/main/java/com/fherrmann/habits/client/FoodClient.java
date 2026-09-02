package com.fherrmann.habits.client;

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
 * Liest einen Tag aus dem Kalorienzaehler ({@code /api/food/day}).
 *
 * <p>Ueber localhost, mit demselben Privat-Token, den auch diese App prueft -
 * es ist ein und dasselbe Geheimnis. Nur das Noetige wird aus der Antwort
 * geholt (kcal gegessen, kcal-Ziel, welche Mahlzeiten einen Eintrag haben);
 * alles andere darf sich dort aendern, ohne dass es hier jemand merkt.
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
    private final String token;

    public FoodClient(
            ObjectMapper objectMapper,
            @Value("${habits.food.url}") String baseUrl,
            @Value("${habits.security.token}") String token) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.token = token;
    }

    public Day day(LocalDate date) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/food/day?date=" + date))
                .header("Cookie", "fh_private=" + token)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new SourceUnavailableException("Kalorienzähler", response.statusCode());
            }
            return parse(objectMapper.readTree(response.body()));
        } catch (IOException e) {
            throw new SourceUnavailableException("Kalorienzähler", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
