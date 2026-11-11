package eu.glazelab.api.support;

import eu.glazelab.api.dto.AuthDtos.RegistrationView;
import eu.glazelab.api.dto.AuthDtos.UserView;
import eu.glazelab.api.dto.GlazeDtos.GlazeView;
import eu.glazelab.api.dto.CombinationDtos.CombinationView;
import eu.glazelab.api.dto.ClubDtos.ClubView;
import eu.glazelab.api.dto.ClubDtos.InviteView;
import eu.glazelab.api.model.AccountToken;
import eu.glazelab.api.model.Club;
import eu.glazelab.api.repository.ClubRepository;
import eu.glazelab.api.security.SecureTokens;
import eu.glazelab.api.service.AccountService;
import eu.glazelab.api.storage.ImageStorage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.NoSuchFileException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.LockModeType;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.test.web.servlet.client.RestTestClient.ResponseSpec;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.doAnswer;

/**
 * Shared running API with in-memory data and email disabled; subclasses reuse one cached context.
 * Every client is a separate browser session over real HTTP, so cookies, sessions and CSRF behave as in production.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:api;DB_CLOSE_DELAY=-1",
        "spring.mail.host=",
        "glazelab.images.path=target/test-images",
        "glazelab.rate-limit.enabled=false" })
@Import(ApiTestBase.ServletSessions.class)
public abstract class ApiTestBase {
    public static final String PASSWORD = "secretpass123";
    public static final String CSRF_HEADER = "X-XSRF-TOKEN";
    /** A small valid PNG for uploads that need no particular size. */
    protected static final byte[] PNG = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAABgAAAAYCAIAAABvFaqvAAAAIklEQV"
            + "R4nGPMnrOYgRqAiSqmjBo0atCoQaMGjRo0ahBFAAALsAHaE05GcQAAAABJRU5ErkJggg==");

    public record Created(UUID id) {}
    record Csrf(String token) {}
    /** A call's result with the JDBC statements it prepared; needs {@code hibernate.generate_statistics=true}. */
    public record Measured<T>(T result, long statements) {}

    @LocalServerPort protected int port;
    @Autowired EntityManagerFactory factory;
    @Autowired protected JdbcTemplate jdbc;

    /**
     * Records every servlet session the API creates, with the stack that created it. Sign-ins live in
     * {@code LoginSessions}, so none may ever exist: one would bring back a JSESSIONID cookie and state in API memory.
     */
    @TestConfiguration(proxyBeanMethods = false)
    public static class ServletSessions {
        static final List<String> CREATED = Collections.synchronizedList(new ArrayList<>());
        @Bean HttpSessionListener servletSessionRecorder() {
            return new HttpSessionListener() {
                @Override public void sessionCreated(HttpSessionEvent event) {
                    CREATED.add(String.join("\n  at ", Arrays.stream(new Throwable().getStackTrace()).limit(40)
                            .map(StackTraceElement::toString).toList()));
                }
            };
        }
    }
    @AfterEach void noServletSessionWasCreated() {
        if (ServletSessions.CREATED.isEmpty()) return;
        List<String> created;
        synchronized (ServletSessions.CREATED) {
            created = List.copyOf(ServletSessions.CREATED);
            ServletSessions.CREATED.clear();
        }
        fail(created.size() + " servlet session(s) created, first by:\n  at " + created.getFirst());
    }

    /**
     * Runs {@code change} like a commit by another session while a request waits for the lock of {@code club}, then
     * grants the lock. {@code clubs} must be the test class's {@code @MockitoSpyBean} (reset it in a finally block).
     * JDBC on the request thread joins its transaction, so a refusal also rolls the change back.
     */
    protected static void beforeTheClubLock(ClubRepository clubs, EntityManager entities, UUID club, Runnable change) {
        doAnswer(call -> {
            change.run();
            return Optional.of(entities.find(Club.class, club, LockModeType.PESSIMISTIC_WRITE));
        }).when(clubs).lockById(club);
    }

    /**
     * PostgreSQL lock queues: waits until {@code expected} sessions whose query contains {@code queryPart} wait for a
     * lock this transaction holds, directly or queued behind another such waiter. Call it inside the transaction that
     * took the lock; a session of another test class (another JVM or a stray request) never counts.
     */
    protected void awaitWaitersBehindMe(String queryPart, int expected) {
        // The holder's session, read on this thread: the timeout below polls from another thread and connection.
        int holder = jdbc.queryForObject("select pg_backend_pid()", Integer.class);
        assertTimeoutPreemptively(Duration.ofSeconds(8), () -> {
            while (jdbc.queryForObject("""
                    with recursive behind(pid) as (
                        select pid from pg_stat_activity where ? = any(pg_blocking_pids(pid))
                        union
                        select a.pid from pg_stat_activity a join behind b on b.pid = any(pg_blocking_pids(a.pid)))
                    select count(*) from pg_stat_activity
                    where pid in (select pid from behind) and wait_event_type = 'Lock' and query like ?""",
                    Integer.class, holder, "%" + queryPart + "%") < expected)
                Thread.sleep(30);
        });
    }

    protected <T> Measured<T> measured(Supplier<T> call) {
        var statistics = factory.unwrap(SessionFactory.class).getStatistics();
        // Without statistics every count is 0 and a query-count test would pass vacuously.
        if (!statistics.isStatisticsEnabled())
            throw new IllegalStateException("Set spring.jpa.properties.hibernate.generate_statistics=true to measure statements");
        statistics.clear();
        T result = call.get();
        return new Measured<>(result, statistics.getPrepareStatementCount());
    }

    /** A new anonymous session that adds a CSRF token to unsafe requests unless one is set explicitly. */
    protected RestTestClient guest() {
        return client("http://localhost:" + port, true);
    }

    /** A new anonymous session that never adds CSRF tokens. */
    protected RestTestClient guestWithoutCsrf() {
        return client("http://localhost:" + port, false);
    }

    public static RestTestClient client(String origin, boolean csrf) {
        var http = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        var requests = new JdkClientHttpRequestFactory(http);
        var builder = RestTestClient.bindToServer(requests).baseUrl(origin);
        if (csrf) {
            // Like the SPA, read the token from the JSON endpoint; it shares this session's cookie jar.
            RestClient tokens = RestClient.builder().requestFactory(requests).baseUrl(origin).build();
            builder.requestInterceptor((request, body, execution) -> {
                if (request.getMethod() != HttpMethod.GET && request.getHeaders().getFirst(CSRF_HEADER) == null)
                    request.getHeaders().set(CSRF_HEADER, tokens.get().uri("/api/auth/csrf").retrieve().body(Csrf.class).token());
                return execution.execute(request, body);
            });
        }
        return builder.build();
    }

    public static String csrf(RestTestClient client) {
        return client.get().uri("/api/auth/csrf").exchange().expectStatus().isOk()
                .expectBody(Csrf.class).returnResult().getResponseBody().token();
    }

    public static ResponseSpec login(RestTestClient client, String email, String password) {
        return client.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(loginForm(email, password)).exchange();
    }

    /** Logs in with an explicit CSRF token, for clients that do not add one. */
    public static ResponseSpec login(RestTestClient client, String token, String email, String password) {
        return client.post().uri("/api/auth/login").header(CSRF_HEADER, token)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(loginForm(email, password)).exchange();
    }

    private static LinkedMultiValueMap<String, String> loginForm(String email, String password) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("email", email);
        form.add("password", password);
        return form;
    }

    /** Registers an account without signing in; registration takes no password and no consent. */
    public static ResponseSpec register(RestTestClient client, String email, String displayName) {
        return client.post().uri("/api/auth/register").body(Map.of("email", email, "displayName", displayName)).exchange();
    }

    /** The setup token of a mail-free registration (null for an address that already had an account). */
    public static String setupToken(ResponseSpec registration) {
        return body(registration.expectStatus().isOk(), RegistrationView.class).setupToken();
    }

    /** Answers an account link's activation challenge with the first password, display name and the current terms. */
    public static ResponseSpec activate(RestTestClient client, String token, String password, String displayName) {
        return client.post().uri("/api/auth/verification/confirm").body(activation(token, password, displayName)).exchange();
    }

    /**
     * The body that activates a pending account through any account link: password, display name, the current terms
     * accepted and error reports off (the activation's own choice).
     */
    public static Map<String, Object> activation(String token, String password, String displayName) {
        return Map.of("token", token, "password", password, "displayName", displayName,
                "termsAccepted", true, "termsVersion", AccountService.TERMS_VERSION, "diagnosticsOptIn", false);
    }

    /** Registers (mail-free), sets {@link #PASSWORD} with the setup token and signs in, in its own session. */
    protected RestTestClient signUp(String email) {
        RestTestClient client = guest();
        String name = email.split("@")[0];
        activate(client, setupToken(register(client, email, name)), PASSWORD, name).expectStatus().isOk();
        login(client, email, PASSWORD).expectStatus().isOk();
        return client;
    }

    /** A signed-in site admin; the flag is granted with SQL only, as in production. */
    protected RestTestClient siteAdmin() {
        String email = email("Admin");
        RestTestClient client = signUp(email);
        assertEquals(1, jdbc.update("UPDATE app_user SET site_admin = TRUE WHERE email = ?", email));
        return client;
    }

    /** A valid raw account token of {@code purpose} for the account, stored hashed as the mail flows store it. */
    protected String accountToken(UUID userId, AccountToken.Purpose purpose) {
        String raw = SecureTokens.generate();
        var now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.update("DELETE FROM account_token WHERE user_id = ? AND purpose = ?", userId, purpose.name());
        jdbc.update("INSERT INTO account_token (id, user_id, purpose, token_hash, issued_at, expires_at) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), userId, purpose.name(), SecureTokens.hash(raw), now, now.plusMinutes(15));
        return raw;
    }

    protected RestTestClient user(String name) {
        return signUp(email(name));
    }

    /** The lines {@code source} logs while {@code action} runs (retention purges log counts only). */
    protected static List<String> logged(Class<?> source, Runnable action) {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(source);
        var captured = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        captured.start();
        logger.addAppender(captured);
        try { action.run(); } finally { logger.detachAppender(captured); }
        return captured.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList();
    }

    public static UserView me(RestTestClient client) {
        return body(client.get().uri("/api/auth/me").exchange().expectStatus().isOk(), UserView.class);
    }

    public static <T> T body(ResponseSpec response, Class<T> type) {
        return response.expectBody(type).returnResult().getResponseBody();
    }

    public static <T> T body(ResponseSpec response, ParameterizedTypeReference<T> type) {
        return response.expectBody(type).returnResult().getResponseBody();
    }

    protected static void assertError(ResponseSpec response, int status, String message) {
        response.expectStatus().isEqualTo(status).expectBody().jsonPath("$.error").isEqualTo(message);
    }

    /** Status and raw body of an answer, to show that two requests are answered byte for byte alike. */
    protected static String statusAndBody(ResponseSpec response) {
        var result = response.expectBody(String.class).returnResult();
        return result.getStatus().value() + " " + result.getResponseBody();
    }

    /** Both stored objects of the picture under {@code key} (full size and card copy) exist, or neither does. */
    protected static void assertStored(ImageStorage storage, String key, boolean stored) {
        for (String object : List.of(key, ImageStorage.cardKey(key))) {
            try {
                assertTrue(storage.get(object).length > 0, object);
                assertTrue(stored, object + " should be gone");
            } catch (NoSuchFileException gone) {
                assertFalse(stored, object + " should be stored");
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }
    }

    protected static UUID created(ResponseSpec response) {
        return body(response.expectStatus().isOk(), Created.class).id();
    }

    protected static List<GlazeView> glazes(RestTestClient client) {
        return body(client.get().uri("/api/glazes").exchange().expectStatus().isOk(),
                new ParameterizedTypeReference<List<GlazeView>>() {});
    }

    /** Adds a glaze, reusing the manufacturer of that name or creating it with a unique short ID. */
    protected static UUID addGlaze(RestTestClient client, String manufacturer, String name, String code) {
        var body = new HashMap<String, Object>(Map.of("name", name, "code", code));
        glazes(client).stream().map(GlazeView::manufacturer).filter(m -> m.name().equals(manufacturer.trim()))
                .findFirst().ifPresentOrElse(m -> body.put("manufacturerId", m.id()),
                        () -> body.put("newManufacturer", Map.of("name", manufacturer, "shortId", shortId())));
        return created(client.post().uri("/api/glazes").body(body).exchange());
    }

    /** A manufacturer short ID no other test uses: a letter and nine upper-case hex digits. */
    protected static String shortId() {
        return "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 9).toUpperCase(Locale.ROOT);
    }

    protected static UUID addCombination(RestTestClient client, String title, UUID... glazeIds) {
        var steps = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < glazeIds.length; i++) {
            steps.add(Map.of("glazeId", glazeIds[i], "coats", glazeIds.length - i, "method", i == 0 ? "Brush" : "Dip"));
        }
        return created(client.post().uri("/api/combinations").body(Map.of("title", title, "steps", steps)).exchange());
    }

    protected static UUID createClub(RestTestClient client, String name) {
        return created(client.post().uri("/api/clubs").body(Map.of("name", name)).exchange());
    }

    /** A new invite link of {@code club}; only an admin may create one, and it replaces the previous link. */
    protected static String invite(RestTestClient admin, UUID club) {
        return body(admin.post().uri("/api/clubs/{id}/invite", club).exchange().expectStatus().isOk(), InviteView.class).token();
    }

    /** Membership comes only from an invite link: the admin creates one (which turns invites on), {@code member} joins with it. */
    protected static void joinClub(RestTestClient admin, UUID club, RestTestClient member) {
        member.post().uri("/api/clubs/join").body(Map.of("token", invite(admin, club))).exchange().expectStatus().isOk();
    }

    protected static ResponseSpec upload(RestTestClient client, UUID combination, Map<String, String> fields, byte[] file) {
        return upload(client, "/api/combinations/{id}/images", combination, fields, file);
    }

    /** Uploads {@link #PNG} as a test tile of a combination and returns the new image's ID; {@code clubId} may be null. */
    protected static UUID uploadTile(RestTestClient client, UUID combination, String visibility, UUID clubId) {
        var fields = new HashMap<>(Map.of("visibility", visibility, "kind", "TEST_TILE"));
        if (clubId != null) fields.put("clubId", clubId.toString());
        return created(upload(client, combination, fields, PNG));
    }

    protected static ResponseSpec uploadGlazeImage(RestTestClient client, UUID glaze, Map<String, String> fields, byte[] file) {
        return upload(client, "/api/glazes/{id}/images", glaze, fields, file);
    }

    private static ResponseSpec upload(RestTestClient client, String path, UUID target, Map<String, String> fields, byte[] file) {
        return client.post().uri(path, target).contentType(MediaType.MULTIPART_FORM_DATA).body(parts(fields, file)).exchange();
    }

    /** The multipart body of an upload: {@code file} as the {@code file} part, plus {@code fields}. */
    public static LinkedMultiValueMap<String, Object> parts(Map<String, String> fields, byte[] file) {
        var fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.IMAGE_PNG);
        var parts = new LinkedMultiValueMap<String, Object>();
        parts.add("file", new HttpEntity<>(new ByteArrayResource(file) {
            @Override public String getFilename() { return "tile.png"; }
        }, fileHeaders));
        fields.forEach(parts::add);
        return parts;
    }

    /** Sends a feedback with {@code fields} (context) and up to three {@code photos} as the {@code files} parts. */
    protected static ResponseSpec sendFeedback(RestTestClient client, String message, Map<String, String> fields, byte[]... photos) {
        var parts = new LinkedMultiValueMap<String, Object>();
        parts.add("message", message);
        fields.forEach(parts::add);
        for (byte[] photo : photos) {
            var fileHeaders = new HttpHeaders();
            fileHeaders.setContentType(MediaType.IMAGE_PNG);
            parts.add("files", new HttpEntity<>(new ByteArrayResource(photo) {
                @Override public String getFilename() { return "photo.png"; }
            }, fileHeaders));
        }
        return client.post().uri("/api/feedback").contentType(MediaType.MULTIPART_FORM_DATA).body(parts).exchange();
    }

    /** Sets or replaces the caller's profile picture with {@code file}. */
    protected static ResponseSpec uploadAvatar(RestTestClient client, byte[] file) {
        return client.post().uri("/api/avatars").contentType(MediaType.MULTIPART_FORM_DATA).body(parts(Map.of(), file)).exchange();
    }

    /**
     * Uploads a picture nobody else uploads ({@link TestImages#unique}) as the caller's profile picture and answers its
     * ID: a site admin's removal blocks a picture for every later upload in the JVM, so tests never share one.
     */
    protected static UUID avatar(RestTestClient client) {
        return created(uploadAvatar(client, TestImages.unique()));
    }

    protected static List<CombinationView> combinations(RestTestClient client) {
        return body(client.get().uri("/api/combinations").exchange().expectStatus().isOk(),
                new ParameterizedTypeReference<List<CombinationView>>() {});
    }

    protected static Optional<CombinationView> combination(RestTestClient client, UUID id) {
        return combinations(client).stream().filter(c -> c.id().equals(id)).findFirst();
    }

    protected static Optional<ClubView> club(RestTestClient client, UUID id) {
        return body(client.get().uri("/api/clubs").exchange().expectStatus().isOk(),
                new ParameterizedTypeReference<List<ClubView>>() {}).stream().filter(c -> c.id().equals(id)).findFirst();
    }

    /** Tests share one database, so every account and catalog entry gets a unique suffix. */
    protected static String unique(String text) {
        return text + " " + UUID.randomUUID().toString().substring(0, 8);
    }

    public static String email(String name) {
        return name.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
    }
}
