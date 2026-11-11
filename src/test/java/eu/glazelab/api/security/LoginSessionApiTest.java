package eu.glazelab.api.security;

import eu.glazelab.api.GlazelabApplication;
import eu.glazelab.api.model.AccountToken;
import eu.glazelab.api.repository.LoginSessionRepository;
import eu.glazelab.api.support.ApiTestBase;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.ExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/**
 * Persistent sign-ins (V29, {@link LoginSessions}): stored in the database behind a persistent cookie, sliding with
 * use, capped in age, and ended by sign-out, password change or reset, account deletion and the purge. The cookie is
 * set only by a sign-in (for the absolute lifetime) and cleared by a sign-out; the sliding happens in the row alone.
 */
class LoginSessionApiTest extends ApiTestBase {
    /** ApiTestBase's database: the one the second application must share. */
    private static final String DATABASE = "jdbc:h2:mem:api;DB_CLOSE_DELAY=-1";
    private static final long ABSOLUTE_SECONDS = Duration.ofDays(365).toSeconds();
    @Autowired LoginSessions loginSessions;
    @MockitoSpyBean LoginSessionRepository repository;

    /** A browser that sends nothing but the given session cookie (no cookie jar, no CSRF). */
    private RestTestClient browserWith(String token) { return browserWith(port, token); }
    private static RestTestClient browserWith(int port, String token) {
        return RestTestClient.bindToServer().baseUrl("http://localhost:" + port).defaultCookie(LoginSessions.COOKIE, token).build();
    }
    /** Signs the address in from a new browser and answers that browser's raw session token. */
    private String signIn(String email) {
        return token(login(guest(), email, PASSWORD).expectStatus().isOk().returnResult());
    }
    private static String token(ExchangeResult result) {
        return result.getResponseCookies().getFirst(LoginSessions.COOKIE).getValue();
    }
    private static String setCookie(ExchangeResult result) {
        return result.getResponseHeaders().getOrEmpty(HttpHeaders.SET_COOKIE).stream()
                .filter(header -> header.startsWith(LoginSessions.COOKIE + "=")).findFirst().orElse(null);
    }
    private static EntityExchangeResult<byte[]> me(RestTestClient browser, int status) {
        return browser.get().uri("/api/auth/me").exchange().expectStatus().isEqualTo(status).expectBody().returnResult();
    }
    private int rows(UUID userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM login_session WHERE user_id = ?", Integer.class, userId);
    }
    private Instant renewedAt(String token) {
        return jdbc.queryForObject("SELECT renewed_at FROM login_session WHERE token_hash = ?", Timestamp.class,
                SecureTokens.hash(token)).toInstant();
    }
    private void backdate(String token, Duration renewedAgo, Duration createdAgo) {
        Instant now = Instant.now();
        jdbc.update("UPDATE login_session SET renewed_at = ?, created_at = ? WHERE token_hash = ?",
                Timestamp.from(now.minus(renewedAgo)), Timestamp.from(now.minus(createdAgo)), SecureTokens.hash(token));
    }
    /**
     * A second application on this database, as after a deployment. Its settings are command-line arguments, which
     * outrank application-dev.properties (default properties would not: it would open the developer's file database).
     */
    private static ConfigurableApplicationContext secondApplication(String... settings) {
        var arguments = new ArrayList<>(List.of("--server.port=0", "--spring.datasource.url=" + DATABASE, "--spring.mail.host=",
                "--glazelab.images.path=target/test-images", "--glazelab.rate-limit.enabled=false"));
        arguments.addAll(List.of(settings));
        var application = new SpringApplicationBuilder(GlazelabApplication.class).run(arguments.toArray(String[]::new));
        assertEquals(DATABASE, application.getEnvironment().getProperty("spring.datasource.url"));
        return application;
    }
    private static int portOf(ConfigurableApplicationContext application) {
        return Integer.parseInt(application.getEnvironment().getProperty("local.server.port"));
    }
    /**
     * {@link LoginSessionRepository#renew}'s statement for a stub (the spy cannot call the interface's real method), on a
     * thread of its own: committed at once and outside any transaction of the calling request, so it holds no lock.
     */
    private int renewCommitted(String hash, Instant now, Instant due) throws Exception {
        return CompletableFuture.supplyAsync(() -> jdbc.update("UPDATE login_session SET renewed_at = ? WHERE token_hash = ? AND renewed_at < ?",
                Timestamp.from(now), hash, Timestamp.from(due))).get(10, TimeUnit.SECONDS);
    }
    private static void assertNoSetCookie(ExchangeResult result) {
        assertEquals(List.of(), result.getResponseHeaders().getOrEmpty(HttpHeaders.SET_COOKIE), result.getUrl().toString());
    }
    private static long maxAge(String setCookie) {
        var match = java.util.regex.Pattern.compile("Max-Age=(\\d+)").matcher(setCookie);
        assertTrue(match.find(), setCookie);
        return Long.parseLong(match.group(1));
    }

    @Test void theCookieIsPersistentHttpOnlyLaxAndOnlyItsHashIsStored() {
        String email = email("Cookie");
        signUp(email);
        var result = login(guest(), email, PASSWORD).expectStatus().isOk()
                .expectCookie().doesNotExist("JSESSIONID")
                .expectCookie().httpOnly(LoginSessions.COOKIE, true)
                .expectCookie().sameSite(LoginSessions.COOKIE, "Lax")
                .expectCookie().path(LoginSessions.COOKIE, "/")
                .expectCookie().secure(LoginSessions.COOKIE, false)
                .returnResult();
        String header = setCookie(result);
        // The browser keeps it for the absolute lifetime; the 30 idle days are enforced by the server alone.
        assertEquals(ABSOLUTE_SECONDS, maxAge(header));
        assertTrue(header.contains("Expires="), header);
        String token = token(result);
        assertTrue(token.matches("[A-Za-z0-9_-]{43}"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM login_session WHERE token_hash = ?", Integer.class, SecureTokens.hash(token)));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM login_session WHERE token_hash = ?", Integer.class, token));
        me(browserWith(token), 200);
    }

    @Test void prodCookiesAreSecure() {
        // The flag follows server.servlet.session.cookie.secure (GLAZELAB_SECURE_COOKIES, true in prod).
        String header = LoginSessions.cookie("x", Duration.ofDays(365), true);
        assertTrue(header.contains("; Secure"), header);
        assertTrue(header.contains("; HttpOnly"), header);
        assertTrue(header.contains("SameSite=Lax"), header);
        assertTrue(header.contains("Max-Age=" + ABSOLUTE_SECONDS), header);
    }

    /**
     * Nothing about a sign-in lives in the server's memory: a second application, started fresh (new beans, new
     * Tomcat, empty caches) on the same database, accepts the cookie the first one issued, as after a deployment.
     */
    @Test void aSignInSurvivesARestartOfTheApi() {
        String email = email("Restart");
        UUID id = ApiTestBase.me(signUp(email)).id();
        String token = signIn(email);
        try (var restarted = secondApplication()) {
            int port = portOf(restarted);
            assertNotEquals(this.port, port);
            String body = new String(me(browserWith(port, token), 200).getResponseBody());
            assertTrue(body.contains(id.toString()), body);
            browserWith(port, SecureTokens.generate()).get().uri("/api/auth/me").exchange().expectStatus().isUnauthorized();
        }
    }

    @Test void useRenewsTheStoredTimeAtMostOnceADayAndNeverSendsTheCookie() {
        String email = email("Sliding");
        signUp(email);
        String token = signIn(email);
        var browser = browserWith(token);
        // Fresh sessions are not renewed: no write and no cookie per request.
        Instant issued = renewedAt(token);
        assertNoSetCookie(me(browser, 200));
        assertEquals(issued, renewedAt(token));

        backdate(token, Duration.ofDays(2), Duration.ofDays(10));
        Instant before = Instant.now();
        assertNoSetCookie(me(browser, 200));
        Instant moved = renewedAt(token);
        assertFalse(moved.isBefore(before.minusSeconds(1)), "stored time moved to now");
        // The next request within the day writes nothing.
        assertNoSetCookie(me(browser, 200));
        assertEquals(moved, renewedAt(token));
    }

    @Test void aSessionEndsThirtyDaysAfterItsLastUse() {
        String email = email("Idle");
        signUp(email);
        String token = signIn(email);
        backdate(token, Duration.ofDays(30).plusMinutes(1), Duration.ofDays(40));
        assertNoSetCookie(me(browserWith(token), 401));
        // Just inside the window it still works, and is renewed (in the row only).
        String other = signIn(email);
        backdate(other, Duration.ofDays(30).minusMinutes(5), Duration.ofDays(40));
        Instant before = Instant.now();
        assertNoSetCookie(me(browserWith(other), 200));
        assertFalse(renewedAt(other).isBefore(before.minusSeconds(1)));
    }

    @Test void aSessionEndsAYearAfterTheSignInHoweverActive() {
        String email = email("Absolute");
        signUp(email);
        String token = signIn(email);
        backdate(token, Duration.ZERO, Duration.ofDays(365).plusMinutes(1));
        assertNoSetCookie(me(browserWith(token), 401));
        // Renewed near the cap, the row moves but the session still ends at the cap, renewals notwithstanding.
        String late = signIn(email);
        backdate(late, Duration.ofDays(2), Duration.ofDays(364));
        assertNoSetCookie(me(browserWith(late), 200));
        assertTrue(renewedAt(late).isAfter(Instant.now().minus(Duration.ofMinutes(1))));
        jdbc.update("UPDATE login_session SET created_at = ? WHERE token_hash = ?",
                Timestamp.from(Instant.now().minus(Duration.ofDays(365).plusMinutes(1))), SecureTokens.hash(late));
        assertNoSetCookie(me(browserWith(late), 401));
    }

    /**
     * After the sign-in no answer sets the session cookie, so none can carry the token into a shared cache (a PUBLIC
     * image is {@code Cache-Control: public, immutable}) or hand an older token back to the browser: renewal-due reads
     * of every kind, a PUBLIC image, a conditional request, a 404, a write and a 401 all answer without Set-Cookie,
     * while the stored renewal time still moves.
     */
    @Test void noAnswerAfterTheSignInSetsTheCookieWhileTheRowStillRenews() {
        String email = email("NoRenewalCookie");
        var owner = signUp(email);
        UUID glaze = addGlaze(owner, "No Cookie Maker", "No cookie glaze", "NC-1");
        UUID image = uploadTile(owner, addCombination(owner, "No cookie combination", glaze), "PUBLIC", null);
        String token = signIn(email);
        var browser = browserWith(token);
        String csrf = browser.get().uri("/api/auth/csrf").exchange().returnResult().getResponseCookies().getFirst("XSRF-TOKEN").getValue();

        backdate(token, Duration.ofDays(2), Duration.ofDays(3));
        Instant before = Instant.now();
        var picture = browser.get().uri("/api/images/{id}", image).exchange().expectStatus().isOk().expectBody().returnResult();
        assertTrue(picture.getResponseHeaders().getCacheControl().contains("public"), picture.getResponseHeaders().getCacheControl());
        assertTrue(picture.getResponseHeaders().getOrEmpty(HttpHeaders.SET_COOKIE).isEmpty(), "a cacheable answer sets no cookie");
        assertFalse(renewedAt(token).isBefore(before.minusSeconds(1)), "the PUBLIC image request renewed the row");

        for (String path : List.of("/api/auth/me", "/api/glazes", "/api/combinations", "/api/stock", "/api/sync/glazes",
                "/api/images/" + image, "/api/images/" + UUID.randomUUID(), "/api/unknown")) {
            backdate(token, Duration.ofDays(2), Duration.ofDays(3));
            before = Instant.now();
            assertNoSetCookie(browser.get().uri(path).header(HttpHeaders.IF_NONE_MATCH, "\"x\"")
                    .header(HttpHeaders.IF_MODIFIED_SINCE, "Thu, 01 Jan 2026 00:00:00 GMT").exchange().returnResult());
            assertFalse(renewedAt(token).isBefore(before.minusSeconds(1)), path + " renewed the row");
        }
        backdate(token, Duration.ofDays(2), Duration.ofDays(3));
        var written = browser.post().uri("/api/clubs").cookie("XSRF-TOKEN", csrf).header(CSRF_HEADER, csrf)
                .body(Map.of("name", unique("Renewal club"))).exchange().expectStatus().is2xxSuccessful().returnResult();
        assertNull(setCookie(written), "a write sets no session cookie");
        assertTrue(renewedAt(token).isAfter(Instant.now().minus(Duration.ofMinutes(1))));

        backdate(token, Duration.ofDays(31), Duration.ofDays(40));
        assertNoSetCookie(me(browser, 401));
        assertNoSetCookie(browser.get().uri("/api/images/{id}", image).exchange().expectStatus().isOk().returnResult());
    }

    /**
     * The 23-70 race: a renewal-due request renews its row, then the same browser changes its password (every row of
     * the account goes, the browser gets a new token), and only then the first request answers. That late answer
     * must not hand the old, deleted token back to the browser, which would sign out the person who just changed it:
     * it carries no Set-Cookie at all, and the new token stays the browser's.
     */
    @Test void aLateAnswerOfARequestFromBeforeAPasswordChangeCannotRestoreTheOldToken() throws Exception {
        String email = email("ChangeRace");
        signUp(email);
        String token = signIn(email);
        String hash = SecureTokens.hash(token);
        String csrf = browserWith(token).get().uri("/api/auth/csrf").exchange().returnResult().getResponseCookies().getFirst("XSRF-TOKEN").getValue();
        backdate(token, Duration.ofDays(2), Duration.ofDays(3));
        var renewed = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var armed = new AtomicBoolean(true);
        doAnswer(call -> {
            // The renewal commits (the same statement, without the spy's abstract method, on a thread of its own so it
            // is not part of a transaction of this request's thread and holds no lock), then waits for the change.
            int count = renewCommitted(call.getArgument(0), call.getArgument(1), call.getArgument(2));
            if (armed.getAndSet(false)) {
                renewed.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
            }
            return count;
        }).when(repository).renew(eq(hash), any(), any());
        String changed;
        ExchangeResult late;
        try (var executor = Executors.newSingleThreadExecutor()) {
            var answer = executor.submit(() -> browserWith(token).get().uri("/api/auth/me").exchange().returnResult());
            assertTrue(renewed.await(10, TimeUnit.SECONDS));
            changed = token(browserWith(token).post().uri("/api/auth/password/change").cookie("XSRF-TOKEN", csrf).header(CSRF_HEADER, csrf)
                    .body(Map.of("currentPassword", PASSWORD, "newPassword", "changedpass456")).exchange()
                    .expectStatus().isOk().returnResult());
            release.countDown();
            late = answer.get(15, TimeUnit.SECONDS);
        } finally { release.countDown(); reset(repository); }
        assertEquals(200, late.getStatus().value(), "validated before the change");
        assertNoSetCookie(late);
        me(browserWith(token), 401);
        me(browserWith(changed), 200);
    }

    @Test void signOutRemovesTheStoredSessionAndClearsTheCookie() {
        String email = email("SignOut");
        var browser = signUp(email);
        UUID id = ApiTestBase.me(browser).id();
        String token = signIn(email);
        assertEquals(2, rows(id));
        var result = browser.post().uri("/api/auth/logout").exchange().expectStatus().isOk()
                .expectCookie().maxAge(LoginSessions.COOKIE, Duration.ZERO).returnResult();
        assertEquals("", token(result));
        assertEquals(1, rows(id));
        browser.get().uri("/api/auth/me").exchange().expectStatus().isUnauthorized();
        // Another device's sign-in stays.
        me(browserWith(token), 200);
    }

    /**
     * Only {@code GET /api/auth/clear-cache} tells the browser to drop its HTTP cache (club and private photos are kept there
     * for a week, 30-1), never its storage or cookies: the app keeps its own offline data. Sign-out and account deletion
     * answer without it (LOGOUT-2: the browser holds that answer while it empties the cache), and still clear the cookie.
     */
    @Test void onlyTheCacheEndpointClearsTheBrowsersHttpCache() {
        String email = email("Leaver");
        var browser = signUp(email);
        browser.get().uri("/api/auth/me").exchange().expectStatus().isOk().expectHeader().doesNotExist("Clear-Site-Data");
        login(browser, email, PASSWORD).expectStatus().isOk().expectHeader().doesNotExist("Clear-Site-Data");
        browser.post().uri("/api/auth/password/change").body(Map.of("currentPassword", PASSWORD, "newPassword", PASSWORD + "-2"))
                .exchange().expectStatus().isOk().expectHeader().doesNotExist("Clear-Site-Data");
        String token = token(login(guest(), email, PASSWORD + "-2").expectStatus().isOk().returnResult());
        // With a live session and its cookie: the session stays, nothing is set.
        assertCacheCleared(browserWith(token));
        me(browserWith(token), 200);
        browser.post().uri("/api/auth/logout").exchange().expectStatus().isOk()
                .expectHeader().doesNotExist("Clear-Site-Data")
                .expectCookie().maxAge(LoginSessions.COOKIE, Duration.ZERO);
        // A browser whose session already ended (a sign-out sent again after an offline one).
        browser.post().uri("/api/auth/logout").exchange().expectStatus().isOk().expectHeader().doesNotExist("Clear-Site-Data");
        assertCacheCleared(browser);
        guestWithoutCsrf().post().uri("/api/auth/logout").exchange().expectStatus().isForbidden().expectHeader().doesNotExist("Clear-Site-Data");
        // Without a session, a cookie or a CSRF token, and with a dead or unknown session cookie.
        assertCacheCleared(guestWithoutCsrf());
        assertCacheCleared(RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build());
        assertCacheCleared(browserWith(SecureTokens.generate()));
        // Only the GET: a write is the authenticated-or-CSRF chain like any unknown route.
        guestWithoutCsrf().post().uri("/api/auth/clear-cache").exchange().expectHeader().doesNotExist("Clear-Site-Data");

        var deleted = user("Deleted");
        deleted.post().uri("/api/auth/delete").body(Map.of("password", "wrong")).exchange().expectStatus().isBadRequest()
                .expectHeader().doesNotExist("Clear-Site-Data");
        deleted.post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange().expectStatus().isNoContent()
                .expectHeader().doesNotExist("Clear-Site-Data")
                .expectCookie().maxAge(LoginSessions.COOKIE, Duration.ZERO);
        assertCacheCleared(deleted);
    }
    /**
     * Only the app's own requests (and browsers without {@code Sec-Fetch-Site}) get the header: another site embedding the URL
     * gets the same uncacheable 204 without it, so it cannot empty a visitor's cache of this origin (LOGOUT-2 review).
     */
    @Test void theCacheIsClearedOnlyForSameOriginRequests() {
        var guest = guestWithoutCsrf();
        java.util.function.Function<String, ExchangeResult> clear = site -> {
            var request = guest.get().uri("/api/auth/clear-cache");
            if (site != null) request = request.header("Sec-Fetch-Site", site);
            var result = request.exchange().expectStatus().isNoContent().expectBody().isEmpty();
            assertEquals(List.of("no-store"), result.getResponseHeaders().getOrEmpty(HttpHeaders.CACHE_CONTROL), site);
            assertEquals(List.of(), result.getResponseHeaders().getOrEmpty(HttpHeaders.VARY), site);
            assertNoSetCookie(result);
            return result;
        };
        for (String site : new String[] {null, "same-origin", "none"})
            assertEquals(List.of("\"cache\""), clear.apply(site).getResponseHeaders().getOrEmpty("Clear-Site-Data"), site);
        for (String site : new String[] {"cross-site", "same-site", "Same-Origin", "same-origin, cross-site", "garbage"})
            assertEquals(List.of(), clear.apply(site).getResponseHeaders().getOrEmpty("Clear-Site-Data"), site);
    }
    /** 204, exactly {@code Clear-Site-Data: "cache"}, {@code no-store}, no body and no cookie of any kind. */
    private static void assertCacheCleared(RestTestClient browser) {
        var result = browser.get().uri("/api/auth/clear-cache").exchange().expectStatus().isNoContent()
                .expectBody().isEmpty();
        assertEquals(List.of("\"cache\""), result.getResponseHeaders().getOrEmpty("Clear-Site-Data"));
        assertEquals(List.of("no-store"), result.getResponseHeaders().getOrEmpty(HttpHeaders.CACHE_CONTROL));
        assertNoSetCookie(result);
    }

    @Test void aSignInAlwaysIssuesANewTokenAndEndsTheOneItCameWith() {
        String email = email("Fixation");
        signUp(email);
        var browser = guest();
        String first = token(login(browser, email, PASSWORD).expectStatus().isOk().returnResult());
        String second = token(login(browser, email, PASSWORD).expectStatus().isOk().returnResult());
        assertNotEquals(first, second);
        me(browserWith(first), 401);
        me(browserWith(second), 200);
        // A planted cookie value is never adopted: the sign-in answers a token of its own.
        String planted = SecureTokens.generate();
        var victim = browserWith(planted);
        // The CSRF token is the XSRF-TOKEN cookie's value (no masking), sent back as cookie and header.
        String csrf = victim.get().uri("/api/auth/csrf").exchange().returnResult().getResponseCookies().getFirst("XSRF-TOKEN").getValue();
        var signedIn = victim.post().uri("/api/auth/login").cookie("XSRF-TOKEN", csrf).header(CSRF_HEADER, csrf)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("email=" + email + "&password=" + PASSWORD).exchange().expectStatus().isOk().returnResult();
        assertNotEquals(planted, token(signedIn));
        me(browserWith(planted), 401);
        me(browserWith(token(signedIn)), 200);
    }

    @Test void aPasswordChangeEndsEveryOtherStoredSession() {
        String email = email("Change");
        var owner = signUp(email);
        UUID id = ApiTestBase.me(owner).id();
        String other = signIn(email);
        String third = signIn(email);
        assertEquals(3, rows(id));
        String renewed = token(owner.post().uri("/api/auth/password/change")
                .body(Map.of("currentPassword", PASSWORD, "newPassword", "changedpass456")).exchange()
                .expectStatus().isOk().returnResult());
        assertEquals(1, rows(id), "only the changing session, under a new token");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM login_session WHERE token_hash = ?", Integer.class, SecureTokens.hash(renewed)));
        me(browserWith(other), 401);
        me(browserWith(third), 401);
        me(owner, 200);
    }

    @Test void aPasswordResetEndsEveryStoredSession() {
        String email = email("Reset");
        var owner = signUp(email);
        UUID id = ApiTestBase.me(owner).id();
        String other = signIn(email);
        String reset = accountToken(id, AccountToken.Purpose.RESET_PASSWORD);
        guest().post().uri("/api/auth/password/reset").body(Map.of("token", reset, "password", "resetpass789"))
                .exchange().expectStatus().isOk();
        assertEquals(0, rows(id));
        me(owner, 401);
        me(browserWith(other), 401);
    }

    @Test void accountDeletionRemovesEveryStoredSession() {
        String email = email("Deleted");
        var owner = signUp(email);
        UUID id = ApiTestBase.me(owner).id();
        String other = signIn(email);
        owner.post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange().expectStatus().isNoContent()
                .expectCookie().maxAge(LoginSessions.COOKIE, Duration.ZERO);
        assertEquals(0, rows(id));
        me(browserWith(other), 401);
    }

    @Test void aStaleOrUnverifiedSessionIsRefusedAndItsRowEnds() {
        String email = email("Stale");
        signUp(email);
        String token = signIn(email);
        UUID id = ApiTestBase.me(browserWith(token)).id();
        String unverified = signIn(email);
        jdbc.update("UPDATE login_session SET session_version = session_version - 1 WHERE token_hash = ?", SecureTokens.hash(token));
        assertNull(setCookie(me(browserWith(token), 401)), "a stale request leaves the cookie alone");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM login_session WHERE token_hash = ?", Integer.class, SecureTokens.hash(token)));
        jdbc.update("UPDATE app_user SET email_verified = FALSE WHERE id = ?", id);
        try {
            me(browserWith(unverified), 401);
        } finally { jdbc.update("UPDATE app_user SET email_verified = TRUE WHERE id = ?", id); }
    }

    @Test void thePurgeRemovesExpiredSessionsOnly() {
        String email = email("Purge");
        signUp(email);
        String idle = signIn(email), old = signIn(email), alive = signIn(email);
        backdate(idle, Duration.ofDays(31), Duration.ofDays(31));
        backdate(old, Duration.ofHours(1), Duration.ofDays(366));
        backdate(alive, Duration.ofDays(29), Duration.ofDays(364));
        assertTrue(loginSessions.purge() >= 2);
        for (String gone : List.of(idle, old))
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM login_session WHERE token_hash = ?", Integer.class, SecureTokens.hash(gone)));
        me(browserWith(alive), 200);
    }

    /**
     * Eight requests of one session all read its day-old row before any of them renews (they wait for each other
     * after the lookup): all pass, the conditional UPDATE lets exactly one of them renew, and none sends the cookie.
     */
    @Test void concurrentRequestsOfOneSessionAllPassAndRenewOnce() throws Exception {
        String email = email("Parallel");
        signUp(email);
        String token = signIn(email);
        backdate(token, Duration.ofDays(2), Duration.ofDays(3));
        var browser = browserWith(token);
        int parallel = 8;
        var allRead = new CyclicBarrier(parallel);
        doAnswer(call -> {
            // The same lookup through a method the spy does not stub (it cannot call the interface's real one).
            var row = repository.findAllById(List.of(SecureTokens.hash(token))).stream().findFirst();
            allRead.await(10, TimeUnit.SECONDS);
            return row;
        }).when(repository).findById(SecureTokens.hash(token));
        var renewals = new AtomicInteger();
        doAnswer(call -> {
            int count = renewCommitted(call.getArgument(0), call.getArgument(1), call.getArgument(2));
            renewals.addAndGet(count);
            return count;
        }).when(repository).renew(eq(SecureTokens.hash(token)), any(), any());
        List<Future<EntityExchangeResult<byte[]>>> answers = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(parallel)) {
            for (int i = 0; i < parallel; i++) answers.add(executor.submit((Callable<EntityExchangeResult<byte[]>>) () -> me(browser, 200)));
            for (var answer : answers) assertNoSetCookie(answer.get(20, TimeUnit.SECONDS));
            assertEquals(1, renewals.get());
        } finally { reset(repository); }
        assertTrue(renewedAt(token).isAfter(Instant.now().minus(Duration.ofMinutes(1))));
        me(browser, 200);
    }

    /**
     * With secure cookies on (prod: GLAZELAB_SECURE_COOKIES), every answer that sets the session cookie marks it
     * Secure: the sign-in and the sign-out that clears it (the daily renewal sends none). The flag comes from the setting, not
     * from the request, which reaches the API as plain HTTP from Nginx.
     */
    @Test void everySessionCookieIsSecureWhenSecureCookiesAreOn() {
        String email = email("Secure");
        signUp(email);
        try (var secure = secondApplication("--server.servlet.session.cookie.secure=true")) {
            int port = portOf(secure);
            var browser = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
            String csrf = browser.get().uri("/api/auth/csrf").exchange().returnResult().getResponseCookies().getFirst("XSRF-TOKEN").getValue();
            var signedIn = browser.post().uri("/api/auth/login").cookie("XSRF-TOKEN", csrf).header(CSRF_HEADER, csrf)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body("email=" + email + "&password=" + PASSWORD)
                    .exchange().expectStatus().isOk().expectCookie().secure(LoginSessions.COOKIE, true).returnResult();
            String token = token(signedIn);

            assertTrue(setCookie(signedIn).contains("; Secure") && maxAge(setCookie(signedIn)) == ABSOLUTE_SECONDS, setCookie(signedIn));
            backdate(token, Duration.ofDays(2), Duration.ofDays(3));
            assertNoSetCookie(me(browserWith(port, token), 200));

            String next = browserWith(port, token).get().uri("/api/auth/csrf").exchange().returnResult()
                    .getResponseCookies().getFirst("XSRF-TOKEN").getValue();
            var signedOut = browser.post().uri("/api/auth/logout").cookie(LoginSessions.COOKIE, token)
                    .cookie("XSRF-TOKEN", next).header(CSRF_HEADER, next).exchange().expectStatus().isOk().returnResult();
            String cleared = setCookie(signedOut);
            assertTrue(cleared.startsWith(LoginSessions.COOKIE + "=;") && cleared.contains("Max-Age=0") && cleared.contains("; Secure"), cleared);
        }
    }

    /** Only the GLAZELAB_SESSION cookie with a token of the generated shape names a session; nothing else is looked up. */
    @Test void onlyTheSessionCookieWithAWellFormedTokenSignsIn() {
        String email = email("Name");
        signUp(email);
        String token = signIn(email);
        for (String name : List.of("JSESSIONID", "glazelab_session", "GLAZELAB_SESSION_", "XSRF-TOKEN", "SESSION"))
            RestTestClient.bindToServer().baseUrl("http://localhost:" + port).defaultCookie(name, token).build()
                    .get().uri("/api/auth/me").exchange().expectStatus().isUnauthorized();
        for (String value : List.of(token + "=", token + "A", token.substring(1), token.substring(0, 42) + "."))
            me(browserWith(value), 401);
        me(browserWith(token), 200);
    }

    /** A renewal time ahead of this clock (another instance's clock, or this one stepped back) is no reason to write or end. */
    @Test void aRenewalTimeAheadOfTheClockIsNeitherRenewedNorEnded() {
        String email = email("Skew");
        signUp(email);
        String token = signIn(email);
        Instant ahead = Instant.now().plus(Duration.ofHours(1));
        jdbc.update("UPDATE login_session SET renewed_at = ? WHERE token_hash = ?", Timestamp.from(ahead), SecureTokens.hash(token));
        Instant stored = renewedAt(token);
        assertNoSetCookie(me(browserWith(token), 200));
        loginSessions.purge();
        assertEquals(stored, renewedAt(token));
        me(browserWith(token), 200);
    }

    /**
     * No endpoint, error answers included, creates a servlet session or sends a JSESSIONID cookie (ApiTestBase's
     * session recorder fails every test that creates one; this walks the auth flows and the error paths explicitly).
     */
    @Test void noEndpointCreatesAServletSession() {
        String email = email("NoServletSession");
        var member = signUp(email);
        var anonymous = guest();
        for (var browser : List.of(anonymous, member)) {
            for (String path : List.of("/api/auth/csrf", "/api/auth/me", "/api/auth/options", "/api/health", "/api/glazes",
                    "/api/combinations", "/api/sync", "/api/openapi", "/api/stock", "/api/unknown", "/elsewhere", "/api/images/nope"))
                assertNoServletSessionCookie(browser.get().uri(path).exchange().returnResult());
            assertNoServletSessionCookie(browser.post().uri("/api/auth/magic/confirm").body(Map.of("token", "x".repeat(43))).exchange().returnResult());
            assertNoServletSessionCookie(browser.post().uri("/api/clubs").header(CSRF_HEADER, "wrong").body(Map.of("name", "x")).exchange().returnResult());
            assertNoServletSessionCookie(browser.put().uri("/api/auth/me").exchange().returnResult());
        }
        assertNoServletSessionCookie(login(guest(), email, "wrong-password").returnResult());
        assertNoServletSessionCookie(login(guest(), email, PASSWORD).expectStatus().isOk().returnResult());
        assertNoServletSessionCookie(member.post().uri("/api/auth/password/change")
                .body(Map.of("currentPassword", PASSWORD, "newPassword", "changedpass456")).exchange().expectStatus().isOk().returnResult());
        assertNoServletSessionCookie(member.post().uri("/api/auth/logout").exchange().expectStatus().isOk().returnResult());
    }
    private static void assertNoServletSessionCookie(ExchangeResult result) {
        assertTrue(result.getResponseHeaders().getOrEmpty(HttpHeaders.SET_COOKIE).stream().noneMatch(header -> header.startsWith("JSESSIONID")),
                result.getUrl() + ": " + result.getResponseHeaders().get(HttpHeaders.SET_COOKIE));
    }

    /**
     * The session lookup runs before everything else of a request with a session cookie: when the database fails
     * there, the answer is a plain 500 that names neither the token, its hash nor the failure, sets no cookie and
     * leaves the session as it was (no error-page loop through the lookup again).
     */
    @Test void aDatabaseFailureDuringTheSessionLookupAnswers500AndLeaksNothing() {
        String email = email("DatabaseDown");
        signUp(email);
        String token = signIn(email);
        String hash = SecureTokens.hash(token);
        doThrow(new DataAccessResourceFailureException("Connection refused by db-host")).when(repository).findById(hash);
        try {
            var result = browserWith(token).get().uri("/api/glazes").exchange().expectStatus().isEqualTo(500).expectBody().returnResult();
            String body = new String(result.getResponseBody() == null ? new byte[0] : result.getResponseBody());
            for (String secret : List.of(token, hash, "Connection refused", "db-host", "Exception"))
                assertFalse(body.contains(secret), body);
            assertNull(setCookie(result));
        } finally { reset(repository); }
        me(browserWith(token), 200);
    }
}
