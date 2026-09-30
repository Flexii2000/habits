package com.fherrmann.habits.cohabit.push;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Firebase Cloud Messaging (HTTP v1) ohne SDK, wie beim Kalorienzaehler: ein POST
 * mit Bearer-Token, das Token aus einem RS256-signierten JWT des Dienstkontos.
 *
 * <p>Reine Datennachrichten, Prioritaet high: die App baut die Benachrichtigung
 * selbst und springt an die richtige Stelle. Ohne Dienstkonto passiert nichts.
 */
@Component
public class FcmClient implements PushTransport {

    private static final Logger log = LoggerFactory.getLogger(FcmClient.class);

    private static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
    private static final Duration RENEW_BEFORE_EXPIRY = Duration.ofMinutes(5);
    private static final String TTL = "86400s";

    private record ServiceAccount(String projectId, String clientEmail, String keyId, PrivateKey privateKey,
                                  String tokenUri) {
    }

    private final String serviceAccountFile;
    private final String endpoint;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private volatile ServiceAccount account;
    private volatile String accessToken;
    private volatile Instant accessTokenUntil = Instant.EPOCH;

    public FcmClient(
            @Value("${cohabit.fcm.service-account-file:}") String serviceAccountFile,
            @Value("${cohabit.fcm.endpoint:https://fcm.googleapis.com}") String endpoint) {
        this.serviceAccountFile = serviceAccountFile == null ? "" : serviceAccountFile.trim();
        this.endpoint = endpoint;
    }

    @Override
    public String platform() {
        return "android";
    }

    @Override
    public boolean isConfigured() {
        return !serviceAccountFile.isEmpty();
    }

    /** Der Rumpf fuer {@code messages:send}. */
    public static Map<String, Object> body(String deviceToken, PushMessage message) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("token", deviceToken);
        msg.put("data", message.data());
        msg.put("android", Map.of("priority", "high", "ttl", TTL));
        return Map.of("message", msg);
    }

    @Override
    public boolean send(String deviceToken, PushMessage message) {
        if (!isConfigured()) {
            return true;
        }
        try {
            ServiceAccount sa = account();
            String body = objectMapper.writeValueAsString(body(deviceToken, message));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint + "/v1/projects/" + sa.projectId() + "/messages:send"))
                    .header("Authorization", "Bearer " + accessToken(sa))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return true;
            }
            if (response.statusCode() == 401) {
                accessToken = null;
            }
            log.warn("FCM antwortete {}: {}", response.statusCode(), response.body());
            return !isGone(response.statusCode(), response.body());
        } catch (Exception e) {
            log.warn("FCM-Nachricht konnte nicht zugestellt werden", e);
            return true;
        }
    }

    /** Tot ist, was Firebase ausdruecklich so nennt - ein 404 allein koennte ein falsches Projekt sein. */
    static boolean isGone(int status, String body) {
        String text = body == null ? "" : body;
        return text.contains("UNREGISTERED") || text.contains("SENDER_ID_MISMATCH")
                || (status == 400 && text.contains("registration token"));
    }

    private synchronized String accessToken(ServiceAccount sa) throws Exception {
        if (accessToken != null && Instant.now().isBefore(accessTokenUntil)) {
            return accessToken;
        }
        long now = Instant.now().getEpochSecond();
        String header = base64Json(Map.of("alg", "RS256", "typ", "JWT", "kid", sa.keyId()));
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", sa.clientEmail());
        claims.put("scope", SCOPE);
        claims.put("aud", sa.tokenUri());
        claims.put("iat", now);
        claims.put("exp", now + 3600);
        String unsigned = header + "." + base64Json(claims);
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(sa.privateKey());
        signature.update(unsigned.getBytes(StandardCharsets.UTF_8));
        String jwt = unsigned + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
        String form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer",
                StandardCharsets.UTF_8) + "&assertion=" + URLEncoder.encode(jwt, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(sa.tokenUri()))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Token-Abruf bei Google: HTTP " + response.statusCode());
        }
        JsonNode json = objectMapper.readTree(response.body());
        String token = json.path("access_token").asString("");
        if (token.isEmpty()) {
            throw new IllegalStateException("Token-Abruf bei Google: kein access_token in der Antwort");
        }
        accessToken = token;
        accessTokenUntil = Instant.now().plusSeconds(json.path("expires_in").asLong(3600)).minus(RENEW_BEFORE_EXPIRY);
        return token;
    }

    /** Beim ersten Gebrauch gelesen: eine kaputte Datei kostet nur die Benachrichtigungen. */
    private ServiceAccount account() throws Exception {
        ServiceAccount current = account;
        if (current != null) {
            return current;
        }
        JsonNode json = objectMapper.readTree(Files.readString(Path.of(serviceAccountFile)));
        String pem = json.path("private_key").asString("")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        PrivateKey key = KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
        ServiceAccount loaded = new ServiceAccount(required(json, "project_id"), required(json, "client_email"),
                json.path("private_key_id").asString(""), key,
                json.path("token_uri").asString("https://oauth2.googleapis.com/token"));
        account = loaded;
        return loaded;
    }

    private static String required(JsonNode json, String field) {
        String value = json.path(field).asString("");
        if (value.isEmpty()) {
            throw new IllegalStateException("Dienstkonto-Datei ohne " + field);
        }
        return value;
    }

    private String base64Json(Map<String, ?> value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(objectMapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8));
    }
}
