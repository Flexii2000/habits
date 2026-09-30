package com.fherrmann.habits.cohabit.support;

import com.fherrmann.habits.cohabit.service.AutoSources;
import com.fherrmann.habits.cohabit.store.CohabitStore;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * Grundlage der API-Tests: der ganze Dienst mit eigenem Datenverzeichnis, einer
 * stellbaren Uhr, Quellen ohne Netz und Push-Wegen, die nur mitschreiben. Vor
 * jedem Test ist das Verzeichnis leer.
 */
@SpringBootTest(properties = {
        "habits.security.token=" + ApiTestBase.PRIVATE,
        "health.owner=felix",
        "health.tokens=torben:" + ApiTestBase.TORBEN,
        "cohabit.scheduler.enabled=false",
        "cohabit.public-url=https://fherrmann.com/cohabit",
        "habits.weight.token=test-weight"
})
@Import(TestBeans.class)
public abstract class ApiTestBase {

    public static final String PRIVATE = "test-private-token-0000";
    public static final String TORBEN = "0123456789abcdef0123456789abcdef";

    protected static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final Path DATA;

    static {
        try {
            DATA = Files.createTempDirectory("cohabit-api-test");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) {
        registry.add("habits.data-dir", DATA::toString);
    }

    @Autowired
    protected WebApplicationContext context;
    @Autowired
    protected CohabitStore store;
    @Autowired
    protected MutableClock clock;
    @Autowired
    protected FakeSources.Food food;
    @Autowired
    protected FakeSources.Steps steps;
    @Autowired
    protected AutoSources autoSources;
    @Autowired
    protected RecordingTransport iosTransport;
    @Autowired
    protected RecordingTransport androidTransport;

    protected MockMvc mvc;

    @BeforeEach
    void resetAll() throws IOException {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        Path cohabitDir = DATA.resolve("cohabit");
        if (Files.exists(cohabitDir)) {
            try (Stream<Path> files = Files.walk(cohabitDir)) {
                files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        Files.deleteIfExists(DATA.resolve("focus.json"));
        store.reload();
        clock.set(TestBeans.START);
        iosTransport.sent.clear();
        iosTransport.dead.clear();
        androidTransport.sent.clear();
        androidTransport.dead.clear();
        food.days = (person, day) -> new com.fherrmann.habits.client.FoodClient.Day(0, 0, java.util.Set.of());
        steps.perDay = (person, day) -> 0;
        autoSources.forgetHistory();
    }

    protected static Path dataDir() {
        return DATA;
    }

    // MARK: - Anfragen

    /** Ein Aufrufer: Bearer-Token oder Cookies. */
    public record Who(String bearer, Cookie... cookies) {

        public static Who bearer(String token) {
            return new Who(token);
        }

        public static Who cookies(Cookie... cookies) {
            return new Who(null, cookies);
        }

        public static Who nobody() {
            return new Who(null);
        }
    }

    protected static final Who FELIX = Who.bearer(PRIVATE);
    protected static final Who TORBEN_APP = Who.bearer(TORBEN);

    protected MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder builder, Who who) {
        if (who.bearer() != null) {
            builder.header("Authorization", "Bearer " + who.bearer());
        }
        if (who.cookies() != null && who.cookies().length > 0) {
            builder.cookie(who.cookies());
        }
        return builder;
    }

    protected Response call(MockHttpServletRequestBuilder builder, Who who) {
        try {
            MvcResult result = mvc.perform(as(builder, who)).andReturn();
            return new Response(result.getResponse());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected Response get(String path, Who who) {
        return call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path), who);
    }

    protected Response delete(String path, Who who) {
        return call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(path), who);
    }

    protected Response delete(String path, Who who, Object body) {
        return call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(path)
                .contentType(MediaType.APPLICATION_JSON).content(json(body)), who);
    }

    protected Response post(String path, Who who, Object body) {
        return call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .contentType(MediaType.APPLICATION_JSON).content(json(body)), who);
    }

    protected Response put(String path, Who who, Object body) {
        return call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path)
                .contentType(MediaType.APPLICATION_JSON).content(json(body)), who);
    }

    protected static String json(Object body) {
        if (body == null) {
            return "{}";
        }
        if (body instanceof String s) {
            return s;
        }
        return JSON.writeValueAsString(body);
    }

    /** Eine Antwort mit bequemem Zugriff auf Status und JSON. */
    public static final class Response {
        public final MockHttpServletResponse raw;

        Response(MockHttpServletResponse raw) {
            this.raw = raw;
        }

        public int status() {
            return raw.getStatus();
        }

        public String body() {
            try {
                return raw.getContentAsString(StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        public JsonNode json() {
            return JSON.readTree(body());
        }

        public Response expect(int status) {
            assertEquals(status, status(), () -> "Status " + status() + ": " + body());
            return this;
        }

        public String message() {
            return json().path("message").asString();
        }

        public String header(String name) {
            return raw.getHeader(name);
        }
    }

    /** Eine neue Person ueber den Freundes-Link von {@code inviter} - danach befreundet. */
    protected Registered register(String displayName, String username, Who inviter) {
        String code = post("/cohabit/api/me/friend-link", inviter, null).expect(200).json().path("code").asString();
        JsonNode r = post("/cohabit/api/invite-links/" + code + "/accept", Who.nobody(),
                map("displayName", displayName, "username", username, "acceptTerms", true)).expect(200).json();
        return new Registered(r.path("me").path("person").path("id").asString(), Who.bearer(r.path("token").asString()));
    }

    public record Registered(String id, Who who) {
    }

    protected static Map<String, Object> streak(String name, Object rhythm) {
        return map("type", "STREAK", "name", name, "color", "peach", "timezone", "Europe/Berlin",
                "tracking", map("mode", "CHECK"), "photoRequired", false, "backfillHours", 48,
                "reminderTime", null, "membersCanInvite", false,
                "streak", map("rhythm", rhythm, "groupStreak", false));
    }

    protected static Map<String, Object> daily() {
        return map("kind", "DAILY");
    }

    protected JsonNode create(Who who, Map<String, Object> config) {
        return post("/cohabit/api/cohabits", who, config).expect(201).json();
    }

    protected static Map<String, Object> map(Object... keyValues) {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }
}
