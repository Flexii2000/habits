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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Das Logbook von Healthy im Weight Tracker ({@code /api/logbook}) - fuer eine bestimmte
 * Person: lesen fuer die Abstinenz mit Tageslimit, schreiben, wenn in coHabit gezaehlt wird.
 *
 * <p>Zugang wie beim {@link StepsClient}: die Eigentuemerin mit {@code WEIGHT_APP_TOKEN} als
 * Cookie, jede weitere Healthy-Person per Bearer mit ihrem Token.
 */
@Component
public class LogbookClient {

    /** Eine Verhaltensweise, wie das Logbook sie liefert. */
    public record Behavior(String id, String name, String unit, boolean archived) {
    }

    /** Der Weight Tracker hat eine Aenderung abgelehnt - mit seinem Grund. */
    public static class Rejected extends RuntimeException {
        public final int status;

        public Rejected(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String ownerToken;
    private final HealthUsers users;

    public LogbookClient(
            ObjectMapper objectMapper,
            @Value("${habits.weight.url}") String baseUrl,
            @Value("${habits.weight.token}") String ownerToken,
            HealthUsers users) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.ownerToken = ownerToken;
        this.users = users;
    }

    /** Alle Verhaltensweisen der Person, archivierte eingeschlossen. */
    public List<Behavior> behaviors(String personId) {
        JsonNode root = send(personId, request("/api/logbook").GET());
        List<Behavior> list = new ArrayList<>();
        for (JsonNode b : root.path("behaviors")) {
            JsonNode unit = b.path("unit");
            list.add(new Behavior(b.path("id").asString(), b.path("name").asString(),
                    unit.isNull() || unit.isMissingNode() ? null : unit.asString(), b.path("archived").asBoolean(false)));
        }
        return list;
    }

    /** Die Werte einer Verhaltensweise je Tag im Zeitraum; Tage ohne Wert fehlen. */
    public Map<LocalDate, Double> values(String personId, String behaviorId, LocalDate from, LocalDate to) {
        JsonNode root = send(personId, request("/api/logbook/days?from=" + from + "&to=" + to).GET());
        Map<LocalDate, Double> values = new HashMap<>();
        for (JsonNode day : root) {
            JsonNode value = day.path("values").path(behaviorId);
            if (value.isNumber()) {
                values.put(LocalDate.parse(day.path("date").asString()), value.asDouble());
            }
        }
        return values;
    }

    /** Setzt den Wert eines Tages - dieselbe Anfrage, die die Healthy-Apps schicken. */
    public void setValue(String personId, String behaviorId, LocalDate date, double value) {
        String body = "{\"value\":" + (value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value)) + "}";
        send(personId, request("/api/logbook/days/" + date + "/values/" + behaviorId)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)));
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(5));
    }

    private JsonNode send(String personId, HttpRequest.Builder request) {
        if (users.isOwner(personId)) {
            if (ownerToken == null || ownerToken.isBlank()) {
                throw new SourceUnavailableException("Logbook (kein WEIGHT_APP_TOKEN)", 0);
            }
            request.header("Cookie", "weight_app_token=" + ownerToken);
        } else {
            String token = users.tokenOf(personId).orElseThrow(
                    () -> new SourceUnavailableException("Logbook (kein Healthy-Zugang)", 0));
            request.header("Authorization", "Bearer " + token);
        }
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 400 || status == 404) {
                throw new Rejected(status, message(response.body()));
            }
            if (status != 200) {
                throw new SourceUnavailableException("Logbook", status);
            }
            return objectMapper.readTree(response.body());
        } catch (IOException e) {
            throw new SourceUnavailableException("Logbook", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SourceUnavailableException("Logbook", e);
        }
    }

    /** Der Weight Tracker schickt seinen Grund als JSON ({@code message}). */
    private String message(String body) {
        try {
            JsonNode node = objectMapper.readTree(body).path("message");
            String message = node.isMissingNode() || node.isNull() ? "" : node.asString();
            return message.isBlank() ? null : message;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
