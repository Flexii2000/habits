package com.fherrmann.habits.cohabit.push;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
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
 * Schickt Benachrichtigungen an Apples Push-Dienst - ohne Bibliothek, wie beim
 * Kalorienzaehler und beim To-Do: ein HTTP/2-POST mit einem ES256-signierten Token.
 *
 * <p>Topic {@code com.fherrmann.cohabit}, Host die Sandbox: Felix installiert die
 * App aus Xcode, und eine Entwicklungssignatur liefert Kennungen, die nur die
 * Sandbox kennt (am Produktionshost kaeme {@code BadDeviceToken}).
 */
@Component
public class ApnsClient implements PushTransport {

    private static final Logger log = LoggerFactory.getLogger(ApnsClient.class);

    /** Apple laesst ein Token eine Stunde gelten und lehnt oefter als alle 20 Minuten ab. */
    private static final Duration TOKEN_LIFETIME = Duration.ofMinutes(45);

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final String keyFile;
    private final String keyId;
    private final String teamId;
    private final String topic;
    private final String host;

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private volatile String cachedToken;
    private volatile Instant cachedUntil = Instant.EPOCH;

    public ApnsClient(
            @Value("${cohabit.push.key-file:}") String keyFile,
            @Value("${cohabit.push.key-id:}") String keyId,
            @Value("${cohabit.push.team-id:}") String teamId,
            @Value("${cohabit.push.topic:com.fherrmann.cohabit}") String topic,
            @Value("${cohabit.push.host:https://api.sandbox.push.apple.com}") String host) {
        this.keyFile = keyFile == null ? "" : keyFile.trim();
        this.keyId = keyId == null ? "" : keyId.trim();
        this.teamId = teamId == null ? "" : teamId.trim();
        this.topic = topic;
        this.host = host;
    }

    @Override
    public String platform() {
        return "ios";
    }

    /** Ohne Schluessel gibt es keine Benachrichtigungen - und das ist in Ordnung. */
    @Override
    public boolean isConfigured() {
        return !keyFile.isBlank() && !keyId.isBlank() && !teamId.isBlank();
    }

    /**
     * Alert und Daten zusammen: {@code aps.alert} fuer die Anzeige, {@code thread-id}
     * buendelt die Benachrichtigungen eines Co-Habits, die Datenfelder daneben sagen
     * der App, wohin der Tipp fuehrt.
     */
    public static String payload(PushMessage message) {
        Map<String, Object> aps = new LinkedHashMap<>();
        aps.put("alert", Map.of("title", message.title() == null ? "" : message.title(),
                "body", message.body() == null ? "" : message.body()));
        aps.put("sound", "default");
        if (message.cohabitId() != null) {
            aps.put("thread-id", message.cohabitId());
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("aps", aps);
        root.putAll(message.data());
        return JSON.writeValueAsString(root);
    }

    @Override
    public boolean send(String deviceToken, PushMessage message) {
        if (!isConfigured()) {
            return true;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(host + "/3/device/" + deviceToken))
                    .header("authorization", "bearer " + authenticationToken())
                    .header("apns-topic", topic)
                    .header("apns-push-type", "alert")
                    .header("apns-priority", "10")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(payload(message), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return true;
            }
            // 410: die App ist weg. BadDeviceToken: meist falsche Umgebung oder alte Kennung.
            boolean gone = response.statusCode() == 410 || response.body().contains("BadDeviceToken");
            log.warn("APNs antwortete {}: {}", response.statusCode(), response.body());
            return !gone;
        } catch (Exception e) {
            log.warn("Benachrichtigung konnte nicht zugestellt werden", e);
            return true;
        }
    }

    private synchronized String authenticationToken() throws Exception {
        if (cachedToken != null && Instant.now().isBefore(cachedUntil)) {
            return cachedToken;
        }
        String header = base64("{\"alg\":\"ES256\",\"kid\":\"" + keyId + "\"}");
        String claims = base64("{\"iss\":\"" + teamId + "\",\"iat\":" + Instant.now().getEpochSecond() + "}");
        String signed = header + "." + claims;
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(privateKey());
        signature.update(signed.getBytes(StandardCharsets.UTF_8));
        String token = signed + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(toJose(signature.sign()));
        cachedToken = token;
        cachedUntil = Instant.now().plus(TOKEN_LIFETIME);
        return token;
    }

    private PrivateKey privateKey() throws Exception {
        String pem = Files.readString(Path.of(keyFile))
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(pem);
        return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private static String base64(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * DER nach JOSE: die JCA liefert eine DER-Struktur, JWT erwartet 64 Byte (R und S
     * je 32, rechtsbuendig). Wer die DER-Bytes einsetzt, bekommt InvalidProviderToken.
     */
    static byte[] toJose(byte[] der) {
        int offset = (der[1] & 0xFF) > 0x80 ? 3 : 2;
        int rLength = der[offset + 1];
        int rStart = offset + 2;
        int sLength = der[rStart + rLength + 1];
        int sStart = rStart + rLength + 2;
        byte[] jose = new byte[64];
        copyRightAligned(der, rStart, rLength, jose, 0);
        copyRightAligned(der, sStart, sLength, jose, 32);
        return jose;
    }

    private static void copyRightAligned(byte[] source, int start, int length, byte[] target, int targetOffset) {
        int from = start;
        int count = length;
        if (count > 32) {
            from += count - 32;
            count = 32;
        }
        System.arraycopy(source, from, target, targetOffset + 32 - count, count);
    }
}
