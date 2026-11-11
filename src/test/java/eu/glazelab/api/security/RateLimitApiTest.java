package eu.glazelab.api.security;

import eu.glazelab.api.service.AccountService;
import eu.glazelab.api.support.ApiTestBase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Small budgets so limits are reached quickly. Each test is its own client address: the test connects from
 * 127.0.0.1, a trusted proxy for Tomcat's RemoteIpValve (as Nginx is in prod), so X-Forwarded-For is the client.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:ratelimit;DB_CLOSE_DELAY=-1",
        "spring.mail.host=",
        "glazelab.images.path=target/test-images",
        "server.forward-headers-strategy=native",
        "glazelab.rate-limit.enabled=true",
        "glazelab.rate-limit.factor=1",
        "glazelab.rate-limit.api.burst=5",
        "glazelab.rate-limit.api.per-minute=6",
        "glazelab.rate-limit.images.burst=8",
        "glazelab.rate-limit.images.per-minute=6",
        "glazelab.rate-limit.auth.burst=3",
        "glazelab.rate-limit.auth.per-minute=6",
        "glazelab.rate-limit.contact.burst=2",
        "glazelab.rate-limit.contact.per-minute=6",
        "glazelab.rate-limit.token.burst=3",
        "glazelab.rate-limit.token.per-minute=6",
        "glazelab.rate-limit.avatar.burst=2",
        "glazelab.rate-limit.avatar.per-minute=1",
        "glazelab.rate-limit.reports.burst=2",
        "glazelab.rate-limit.reports.per-minute=1",
        "glazelab.rate-limit.feedback.burst=2",
        "glazelab.rate-limit.feedback.per-minute=0.1",
        "glazelab.operator-email=operator@example.test",
        "glazelab.rate-limit.signed-in-factor=2",
        "glazelab.rate-limit.tarpit.enabled=true",
        "glazelab.rate-limit.tarpit.rejected-seconds=2",
        "glazelab.rate-limit.tarpit.unknown-images=3",
        "glazelab.rate-limit.tarpit.probability=1",
        "glazelab.rate-limit.tarpit.max-connections=1",
        "glazelab.rate-limit.tarpit.chunk-bytes=1000",
        "glazelab.rate-limit.tarpit.interval=50ms",
        "glazelab.rate-limit.tarpit.max-bytes=20000",
        "glazelab.rate-limit.tarpit.max-duration=10s" })
class RateLimitApiTest extends ApiTestBase {
    private static final AtomicInteger addresses = new AtomicInteger(1);
    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort int serverPort;
    @Autowired Tarpit tarpit;

    /**
     * A new browser session seen as coming from a public address. CSRF tokens are set explicitly, so every
     * request of the session, token fetches included, comes from that address.
     */
    private RestTestClient from(String address) {
        return client("http://localhost:" + serverPort, false).mutate().defaultHeader("X-Forwarded-For", address).build();
    }

    /** Waits for the start of a second, so a short burst stays within one second of the server's clock. */
    private static void startOfSecond() throws InterruptedException {
        while (System.nanoTime() % 1_000_000_000L > 50_000_000L) Thread.sleep(1);
    }

    private static String newAddress() {
        return "203.0.113." + addresses.getAndIncrement();
    }

    private HttpResponse<byte[]> get(String address, String path) throws Exception {
        return http.send(request(address, path), HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpRequest request(String address, String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + serverPort + path))
                .header("X-Forwarded-For", address).timeout(Duration.ofSeconds(20)).build();
    }

    private static boolean decoy(HttpResponse<?> response) {
        return response.headers().firstValue("Content-Type").orElse("").startsWith("application/octet-stream");
    }

    @Test void overTheLimitAnswers429WithRetryAfterAndJson() {
        String address = newAddress();
        RestTestClient client = from(address);
        for (int i = 0; i < 5; i++) client.get().uri("/api/glazes").exchange().expectStatus().isOk();
        client.get().uri("/api/glazes").exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().value("Retry-After", value -> assertThat(Long.parseLong(value)).isBetween(1L, 10L))
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody().jsonPath("$.error").isEqualTo(RateLimitFilter.TOO_MANY);
        // Another address has its own budget; the health check is never limited.
        from(newAddress()).get().uri("/api/glazes").exchange().expectStatus().isOk();
        for (int i = 0; i < 10; i++) client.get().uri("/api/health").exchange().expectStatus().isOk();
    }

    @Test void signedInSessionsGetTheirOwnLargerBudget() {
        String address = newAddress();
        RestTestClient member = from(address);
        String email = email("limits");
        String token = csrf(member);
        signUpWith(member, token, email);
        // Twice the guest budget of 5, counted apart from the address's guest requests (one so far).
        for (int i = 0; i < 10; i++) member.get().uri("/api/stock").exchange().expectStatus().isOk();
        member.get().uri("/api/stock").exchange().expectStatus().isEqualTo(429);
        RestTestClient guest = from(address);
        for (int i = 0; i < 4; i++) guest.get().uri("/api/glazes").exchange().expectStatus().isOk();
        guest.get().uri("/api/glazes").exchange().expectStatus().isEqualTo(429);
    }

    @Test void operatorContactHasItsOwnTightBudgetPerAddress() {
        String address = newAddress();
        RestTestClient client = from(address);
        for (int i = 0; i < 2; i++) client.get().uri("/api/auth/operator-contact").exchange().expectStatus().isOk();
        client.get().uri("/api/auth/operator-contact").exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().value("Retry-After", value -> assertThat(Long.parseLong(value)).isBetween(1L, 10L))
                .expectBody().jsonPath("$.error").isEqualTo(RateLimitFilter.TOO_MANY).jsonPath("$.email").doesNotExist();
        // The other budgets are untouched, and another address has its own.
        client.get().uri("/api/auth/options").exchange().expectStatus().isOk();
        client.get().uri("/api/glazes").exchange().expectStatus().isOk();
        from(newAddress()).get().uri("/api/auth/operator-contact").exchange().expectStatus().isOk();
    }

    @Test void buddyLinkLookupsHaveATightBudgetPerAccountWithoutTheSignedInBoost() {
        String address = newAddress();
        RestTestClient member = signedInFrom(address);
        String token = csrf(member);
        var unknown = Map.of("token", eu.glazelab.api.security.SecureTokens.generate());
        // The signed-in factor is 2 for the API budget; the token budget stays at its 3 for previews and requests together.
        for (String path : List.of("/api/buddies/link/preview", "/api/buddies/link/request", "/api/buddies/link/preview"))
            member.post().uri(path).header(CSRF_HEADER, token).body(unknown).exchange().expectStatus().isNotFound();
        member.post().uri("/api/buddies/link/request").header(CSRF_HEADER, token).body(unknown).exchange()
                .expectStatus().isEqualTo(429).expectBody().jsonPath("$.error").isEqualTo(RateLimitFilter.TOO_MANY);
        // The API budget is untouched, and another account on the same address has its own token budget.
        member.get().uri("/api/buddies").exchange().expectStatus().isOk();
        RestTestClient other = signedInFrom(address);
        other.post().uri("/api/buddies/link/preview").header(CSRF_HEADER, csrf(other)).body(unknown).exchange().expectStatus().isNotFound();
    }

    @Test void profilePictureUploadsHaveATightBudgetPerAccountWithoutTheSignedInBoost() {
        String address = newAddress();
        RestTestClient member = signedInFrom(address);
        String token = csrf(member);
        // The upload budget is 2 whatever the signed-in factor; refused uploads count too (each one costs a decode).
        upload(member, token, PNG).expectStatus().isOk();
        upload(member, token, "not an image".getBytes()).expectStatus().isBadRequest();
        upload(member, token, PNG).expectStatus().isEqualTo(429)
                .expectHeader().value("Retry-After", value -> assertThat(Long.parseLong(value)).isBetween(1L, 60L))
                .expectBody().jsonPath("$.error").isEqualTo(RateLimitFilter.TOO_MANY);
        // Everything else is untouched, and another account on the same address has its own upload budget.
        member.get().uri("/api/auth/me").exchange().expectStatus().isOk();
        RestTestClient other = signedInFrom(address);
        upload(other, csrf(other), PNG).expectStatus().isOk();
    }

    private static RestTestClient.ResponseSpec upload(RestTestClient client, String token, byte[] file) {
        return client.post().uri("/api/avatars").header(CSRF_HEADER, token).contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts(Map.of(), file)).exchange();
    }

    @Test void errorReportsHaveATightBudgetPerAccountWithoutTheSignedInBoost() {
        String address = newAddress();
        RestTestClient member = signedInFrom(address);
        String token = csrf(member);
        member.put().uri("/api/account/diagnostics").header(CSRF_HEADER, token).body(Map.of("enabled", true)).exchange()
                .expectStatus().isNoContent();
        // The budget is 2 whatever the signed-in factor; refused batches count too.
        report(member, token, "{}").expectStatus().isBadRequest();
        report(member, token, "{\"device\":{},\"events\":[{\"kind\":\"ERROR\",\"message\":\"x\",\"occurredAt\":\"2026-11-01T00:00:00Z\",\"build\":\"1\"}]}")
                .expectStatus().isNoContent();
        report(member, token, "{}").expectStatus().isEqualTo(429)
                .expectHeader().value("Retry-After", value -> assertThat(Long.parseLong(value)).isBetween(1L, 60L))
                .expectBody().jsonPath("$.error").isEqualTo(RateLimitFilter.TOO_MANY);
        // Everything else is untouched, and another account on the same address has its own budget.
        member.get().uri("/api/auth/me").exchange().expectStatus().isOk();
        RestTestClient other = signedInFrom(address);
        report(other, csrf(other), "{}").expectStatus().isBadRequest();
    }

    private static RestTestClient.ResponseSpec report(RestTestClient client, String token, String body) {
        return client.post().uri("/api/reports").header(CSRF_HEADER, token).contentType(MediaType.APPLICATION_JSON)
                .body(body).exchange();
    }

    @Test void feedbackHasATightBudgetPerAccountWithoutTheSignedInBoost() {
        String address = newAddress();
        RestTestClient member = signedInFrom(address);
        String token = csrf(member);
        // The budget is 2 whatever the signed-in factor (then 6 an hour); refused feedback counts too.
        feedback(member, token, " ").expectStatus().isBadRequest();
        feedback(member, token, "Works").expectStatus().isOk();
        feedback(member, token, "Third").expectStatus().isEqualTo(429)
                .expectHeader().value("Retry-After", value -> assertThat(Long.parseLong(value)).isBetween(60L, 600L))
                .expectBody().jsonPath("$.error").isEqualTo(RateLimitFilter.TOO_MANY);
        // Everything else is untouched, and another account on the same address has its own budget.
        member.get().uri("/api/auth/me").exchange().expectStatus().isOk();
        RestTestClient other = signedInFrom(address);
        feedback(other, csrf(other), "Mine").expectStatus().isOk();
    }

    private static RestTestClient.ResponseSpec feedback(RestTestClient client, String token, String message) {
        return client.post().uri("/api/feedback").header(CSRF_HEADER, token).contentType(MediaType.MULTIPART_FORM_DATA)
                .body(new org.springframework.util.LinkedMultiValueMap<>(Map.of("message", List.of(message)))).exchange();
    }

    @Test void encodedBuddyLinkPathsSpendTheTokenBudgetAndOtherSpellingsNeverReachTheLookup() {
        RestTestClient member = signedInFrom(newAddress());
        String token = csrf(member);
        var unknown = Map.of("token", eu.glazelab.api.security.SecureTokens.generate());
        String base = "http://localhost:" + serverPort;
        String invalid = "{\"error\":\"" + eu.glazelab.api.service.BuddyService.INVALID_LINK + "\"}";
        // Percent-encoded spellings reach the lookup and count in its budget of 3.
        for (String path : List.of("/api/buddies/link/pr%65view", "/api/buddies/link/r%65quest", "/api/buddies/link/preview"))
            member.post().uri(URI.create(base + path)).header(CSRF_HEADER, token).body(unknown).exchange()
                    .expectStatus().isNotFound().expectBody(String.class).isEqualTo(invalid);
        member.post().uri(URI.create(base + "/api/buddies/link/pr%65view")).header(CSRF_HEADER, token).body(unknown).exchange()
                .expectStatus().isEqualTo(429);
        // A trailing slash or another letter case is no route to the lookup at all (classified API, never the lookup's answer).
        for (String path : List.of("/api/buddies/link/preview/", "/api/buddies/link/request/", "/api/buddies/link/Preview"))
            member.post().uri(URI.create(base + path)).header(CSRF_HEADER, token).body(unknown).exchange()
                    .expectBody(String.class).value(body -> assertThat(body).doesNotContain(eu.glazelab.api.service.BuddyService.INVALID_LINK));
    }

    @Test void operatorContactIgnoresTheSignedInBoostAndSharesOneBudgetPerAddress() {
        String address = newAddress();
        RestTestClient member = signedInFrom(address);
        // The signed-in factor is 2 for the API budget; the contact budget stays at its 2 for the address.
        for (int i = 0; i < 2; i++) member.get().uri("/api/auth/operator-contact").exchange().expectStatus().isOk();
        member.get().uri("/api/auth/operator-contact").exchange().expectStatus().isEqualTo(429);
        // A guest or another account on the same address spends the same budget.
        from(address).get().uri("/api/auth/operator-contact").exchange().expectStatus().isEqualTo(429);
        signedInFrom(address).get().uri("/api/auth/operator-contact").exchange().expectStatus().isEqualTo(429);
    }

    /**
     * The cache clearing after a sign-out is never limited, like the health check (LOGOUT-2 review): a neighbour on a shared
     * address who exhausts the guest budget cannot make it answer 429 without the header. Sign-out stays in the API budget.
     */
    @Test void cacheClearingIsNeverLimited() {
        String address = newAddress();
        RestTestClient client = from(address);
        for (int i = 0; i < 5; i++) client.get().uri("/api/glazes").exchange().expectStatus().isOk();
        client.get().uri("/api/glazes").exchange().expectStatus().isEqualTo(429);
        for (int i = 0; i < 10; i++)
            client.get().uri("/api/auth/clear-cache").exchange().expectStatus().isNoContent()
                    .expectHeader().valueEquals("Clear-Site-Data", "\"cache\"");
        client.get().uri("/api/glazes").exchange().expectStatus().isEqualTo(429);
    }

    @Test void signInAttemptsHaveAStrictBudgetButSignOutAlwaysWorks() {
        RestTestClient client = from(newAddress());
        String token = csrf(client);
        for (int i = 0; i < 3; i++) login(client, token, email("nobody"), "wrong-password").expectStatus().isUnauthorized();
        login(client, token, email("nobody"), "wrong-password").expectStatus().isEqualTo(429)
                .expectBody().jsonPath("$.error").isEqualTo(RateLimitFilter.TOO_MANY);
        client.post().uri("/api/auth/logout").header(CSRF_HEADER, token).exchange().expectStatus().isOk();
    }

    /** One wrong sign-in from a network of its own: the per-IP budget never stops it. */
    private void wrongSignIn(String email) {
        RestTestClient client = from(newAddress());
        login(client, csrf(client), email, "wrong-password").expectStatus().isUnauthorized();
    }

    @Test void fiveFailedSignInsDelayTheAddressWhicheverNetworkTries() {
        RestTestClient owner = from(newAddress());
        String email = email("guessed"), token = csrf(owner);
        signUpWith(owner, token, email);
        for (int i = 0; i < 5; i++) wrongSignIn(i % 2 == 0 ? email : " " + email.toUpperCase());
        // Even the right password from a fresh network waits now, refused before any password is compared.
        RestTestClient elsewhere = from(newAddress());
        login(elsewhere, csrf(elsewhere), email, PASSWORD).expectStatus().isEqualTo(429)
                .expectHeader().value("Retry-After", value -> assertThat(Long.parseLong(value)).isBetween(1L, 60L))
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody().jsonPath("$.error").isEqualTo(SignInDelays.DELAYED);
        // An address without an account answers exactly the same: the delay tells nothing about accounts.
        String unknown = email("unregistered");
        for (int i = 0; i < 5; i++) wrongSignIn(unknown);
        RestTestClient prober = from(newAddress());
        login(prober, csrf(prober), unknown, "wrong-password").expectStatus().isEqualTo(429)
                .expectHeader().value("Retry-After", value -> assertThat(Long.parseLong(value)).isBetween(1L, 60L))
                .expectBody().jsonPath("$.error").isEqualTo(SignInDelays.DELAYED);
        // Other addresses sign in as before.
        RestTestClient other = from(newAddress());
        signUpWith(other, csrf(other), email("unaffected"));
    }

    /**
     * The lookup key ({@code EmailAddresses.key}) folds a long s (U+017F) or a dotless i (U+0131) to the plain letter, so
     * such a spelling signs in to that account and counts for its address, never as an address of its own.
     */
    @Test void aSpellingThatSignsInToTheAddressCountsForIt() {
        RestTestClient owner = from(newAddress());
        String email = email("guessed"), token = csrf(owner);
        signUpWith(owner, token, email);
        String variant = email.replace('s', 'ſ').replace('i', 'ı');
        RestTestClient probe = from(newAddress());
        login(probe, csrf(probe), variant, PASSWORD).expectStatus().isOk();
        for (int i = 0; i < 5; i++) wrongSignIn(email);
        RestTestClient elsewhere = from(newAddress());
        login(elsewhere, csrf(elsewhere), variant, PASSWORD).expectStatus().isEqualTo(429)
                .expectBody().jsonPath("$.error").isEqualTo(SignInDelays.DELAYED);
    }

    @Test void aSuccessfulSignInOrAnActivationClearsTheCount() {
        RestTestClient owner = from(newAddress());
        String email = email("forgetful"), token = csrf(owner);
        signUpWith(owner, token, email);
        for (int i = 0; i < 4; i++) wrongSignIn(email);
        RestTestClient laptop = from(newAddress());
        login(laptop, csrf(laptop), email, PASSWORD).expectStatus().isOk();
        for (int i = 0; i < 4; i++) wrongSignIn(email);
        RestTestClient phone = from(newAddress());
        login(phone, csrf(phone), email, PASSWORD).expectStatus().isOk();

        // Failures on a pending address end with the mailbox owner's activation.
        RestTestClient newcomer = from(newAddress());
        String pending = email("pendingDelay"), newcomerToken = csrf(newcomer);
        String setup = setupToken(newcomer.post().uri("/api/auth/register").header(CSRF_HEADER, newcomerToken)
                .body(Map.of("email", pending, "displayName", "Pending")).exchange());
        for (int i = 0; i < 5; i++) wrongSignIn(pending);
        RestTestClient waiting = from(newAddress());
        login(waiting, csrf(waiting), pending, PASSWORD).expectStatus().isEqualTo(429);
        newcomer.post().uri("/api/auth/verification/confirm").header(CSRF_HEADER, newcomerToken)
                .body(activation(setup, PASSWORD, "Pending")).exchange().expectStatus().isOk();
        login(newcomer, newcomerToken, pending, PASSWORD).expectStatus().isOk();
    }

    @Test void authBudgetIsSharedByGuestsAndDifferentSignedInAccountsOnOneIp() {
        String address = newAddress();
        RestTestClient first = signedInFrom(address);
        RestTestClient second = signedInFrom(address);
        RestTestClient guest = from(address);
        String firstToken = csrf(first), secondToken = csrf(second), guestToken = csrf(guest);
        // Guest and authenticated attempts spend the same three-request budget, without a signed-in boost.
        login(guest, guestToken, email("nobody"), "wrong-password").expectStatus().isUnauthorized();
        login(first, firstToken, email("nobody"), "wrong-password").expectStatus().isUnauthorized();
        login(second, secondToken, email("nobody"), "wrong-password").expectStatus().isUnauthorized();
        for (var session : Map.of(first, firstToken, second, secondToken, guest, guestToken).entrySet())
            login(session.getKey(), session.getValue(), email("nobody"), "wrong-password")
                    .expectStatus().isEqualTo(429).expectHeader().exists("Retry-After")
                    .expectBody().jsonPath("$.error").isEqualTo(RateLimitFilter.TOO_MANY);
        // Changing address still gets a separate budget; logout remains available after AUTH is exhausted.
        RestTestClient otherIp = from(newAddress());
        login(otherIp, csrf(otherIp), email("nobody"), "wrong-password").expectStatus().isUnauthorized();
        first.post().uri("/api/auth/logout").header(CSRF_HEADER, firstToken).exchange().expectStatus().isOk();
    }

    @Test void passwordChangeAttemptsSpendTheSignInBudgetOfTheAddress() {
        String address = newAddress();
        RestTestClient member = signedInFrom(address);
        String token = csrf(member);
        // A wrong current password costs what a failed sign-in costs, from the same three-request budget.
        for (int i = 0; i < 2; i++)
            member.post().uri("/api/auth/password/change").header(CSRF_HEADER, token)
                    .body(Map.of("currentPassword", "wrong-password", "newPassword", "another-password")).exchange()
                    .expectStatus().isBadRequest();
        RestTestClient guest = from(address);
        login(guest, csrf(guest), email("nobody"), "wrong-password").expectStatus().isUnauthorized();
        member.post().uri("/api/auth/password/change").header(CSRF_HEADER, token)
                .body(Map.of("currentPassword", PASSWORD, "newPassword", "another-password")).exchange()
                .expectStatus().isEqualTo(429).expectHeader().exists("Retry-After")
                .expectBody().jsonPath("$.error").isEqualTo(RateLimitFilter.TOO_MANY);
        // Refused before the password was compared: the old one still signs in elsewhere.
        RestTestClient elsewhere = from(newAddress());
        login(elsewhere, csrf(elsewhere), me(member).email(), PASSWORD).expectStatus().isOk();
    }

    /** Registration, first password and sign-in: three AUTH requests, the whole burst of one address. */
    private static void signUpWith(RestTestClient member, String token, String email) {
        String setup = setupToken(member.post().uri("/api/auth/register").header(CSRF_HEADER, token).body(Map.of("termsAccepted", true,
                "termsVersion", AccountService.TERMS_VERSION, "email", email, "displayName", "Limits")).exchange());
        member.post().uri("/api/auth/verification/confirm").header(CSRF_HEADER, token)
                .body(activation(setup, PASSWORD, "Limits")).exchange().expectStatus().isOk();
        login(member, token, email, PASSWORD).expectStatus().isOk();
    }

    @Test void registrationSpendsTheStrictAuthBudgetBeforeCreatingAnything() {
        RestTestClient client = from(newAddress());
        String token = csrf(client);
        for (int i = 0; i < 3; i++) registerWith(client, token, email("burst")).expectStatus().isOk();
        String refused = email("refused");
        registerWith(client, token, refused).expectStatus().isEqualTo(429).expectHeader().exists("Retry-After")
                .expectBody().jsonPath("$.error").isEqualTo(RateLimitFilter.TOO_MANY);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE email = ?", Integer.class, refused)).isZero();
    }

    private static RestTestClient.ResponseSpec registerWith(RestTestClient client, String token, String email) {
        return client.post().uri("/api/auth/register").header(CSRF_HEADER, token).body(Map.of("termsAccepted", true,
                "termsVersion", AccountService.TERMS_VERSION, "email", email, "displayName", "Burst")).exchange();
    }

    private RestTestClient signedInFrom(String address) {
        // Establish the session elsewhere so setup does not spend the address under test's AUTH budget.
        RestTestClient member = from(newAddress());
        String email = email("shared-auth"), token = csrf(member);
        signUpWith(member, token, email);
        return member.mutate().defaultHeaders(headers -> headers.set("X-Forwarded-For", address)).build();
    }

    @Test void oneOversizedBurstIsLimitedButNeverTarpitted() throws Exception {
        String address = newAddress();
        startOfSecond();
        int rejected = 0;
        for (int i = 0; i < 30; i++) {
            var response = get(address, "/api/glazes");
            assertThat(decoy(response)).isFalse();
            if (response.statusCode() == 429) rejected++;
        }
        assertThat(rejected).isEqualTo(25);
        assertThat(decoy(get(address, "/api/glazes"))).isFalse();
    }

    @Test void unknownImageIdsTriggerASlowUncacheableDecoy() throws Exception {
        String address = newAddress();
        for (int i = 0; i < 3; i++) assertThat(get(address, "/api/images/" + UUID.randomUUID()).statusCode()).isEqualTo(404);
        long start = System.nanoTime();
        var response = get(address, "/api/images/" + UUID.randomUUID());
        long elapsed = Duration.ofNanos(System.nanoTime() - start).toMillis();
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(decoy(response)).isTrue();
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store, private");
        assertThat(response.headers().firstValue("X-Accel-Buffering")).contains("no");
        assertThat(response.body()).hasSize(20000);
        // 20 chunks, one every 50 ms.
        assertThat(elapsed).isGreaterThanOrEqualTo(900);
        // Malformed IDs are what a scanner sends too.
        assertThat(decoy(get(address, "/api/images/not-an-id"))).isTrue();
        // Other clients are unaffected.
        assertThat(get(newAddress(), "/api/images/" + UUID.randomUUID()).statusCode()).isEqualTo(404);
    }

    /** One picture per visibility, uploaded from an address of its own (so the address under test spends nothing). */
    private Map<String, UUID> pictures() {
        RestTestClient owner = signedInFrom(newAddress());
        owner = owner.mutate().defaultHeader(CSRF_HEADER, csrf(owner)).build();
        UUID glaze = addGlaze(owner, unique("Tarpit"), "Glaze", "T1");
        UUID combination = addCombination(owner, "Tarpit", glaze);
        return Map.of("PUBLIC", uploadTile(owner, combination, "PUBLIC", null), "PRIVATE", uploadTile(owner, combination, "PRIVATE", null));
    }

    @Test void aFlaggedClientGetsTheDecoyOnlyInsteadOfAnUnknownPicture() throws Exception {
        Map<String, UUID> pictures = pictures();
        String address = newAddress();
        for (int i = 0; i < 3; i++) assertThat(get(address, "/api/images/" + UUID.randomUUID()).statusCode()).isEqualTo(404);
        // Flagged, and every draw is a decoy (probability 1): JSON the app parses and real pictures stay what they are.
        for (String path : List.of("/api/glazes", "/api/combinations", "/api/auth/me", "/api/auth/csrf", "/api/sync?format=1")) {
            var response = get(address, path);
            assertThat(decoy(response)).as(path).isFalse();
            assertThat(response.headers().firstValue("Content-Type").orElse("")).as(path).startsWith("application/json");
        }
        var picture = get(address, "/api/images/" + pictures.get("PUBLIC") + "/card");
        assertThat(picture.statusCode()).isEqualTo(200);
        assertThat(picture.headers().firstValue("Content-Type")).contains("image/jpeg");
        assertThat(decoy(get(address, "/api/images/" + pictures.get("PRIVATE")))).isFalse();
        assertThat(decoy(get(address, "/api/glazes/" + UUID.randomUUID()))).isFalse();
        assertThat(decoy(get(address, "/api/images/" + UUID.randomUUID()))).isTrue();
    }

    @Test void picturesThatExistButAreHiddenNeverCountAsUnknown() throws Exception {
        UUID hidden = pictures().get("PRIVATE");
        String address = newAddress();
        // A stale listing (a club left, a picture made private) asks for pictures that exist: 404, never counted.
        for (int i = 0; i < 6; i++) {
            var response = get(address, "/api/images/" + hidden + (i % 2 == 0 ? "" : "/card"));
            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(decoy(response)).isFalse();
        }
        for (int i = 0; i < 2; i++) assertThat(decoy(get(address, "/api/images/" + UUID.randomUUID()))).isFalse();
    }

    @Test void aClientThatWaitsItsRetryAfterIsNeverFlagged() throws Exception {
        String address = newAddress();
        for (int i = 0; i < 5; i++) assertThat(get(address, "/api/glazes").statusCode()).isEqualTo(200);
        // Two 429s in different seconds, the second only after the wait the first one asked for.
        var refused = get(address, "/api/glazes");
        assertThat(refused.statusCode()).isEqualTo(429);
        Thread.sleep(Long.parseLong(refused.headers().firstValue("Retry-After").orElseThrow()) * 1000 + 50);
        assertThat(get(address, "/api/glazes").statusCode()).isEqualTo(200);
        assertThat(get(address, "/api/glazes").statusCode()).isEqualTo(429);
        assertThat(decoy(get(address, "/api/images/" + UUID.randomUUID()))).isFalse();
    }

    @Test void sustainedOverLimitTrafficTriggersTheTarpit() throws Exception {
        String address = newAddress();
        for (int i = 0; i < 5; i++) assertThat(get(address, "/api/glazes").statusCode()).isEqualTo(200);
        // The first 429 tells the client to wait; asking again before that in two more seconds flags it.
        assertThat(get(address, "/api/glazes").statusCode()).isEqualTo(429);
        Thread.sleep(1100);
        assertThat(get(address, "/api/glazes").statusCode()).isEqualTo(429);
        Thread.sleep(1100);
        assertThat(get(address, "/api/glazes").statusCode()).isEqualTo(429);
        // Flagged: its JSON stays JSON (here still a 429), and an unknown picture gets the decoy.
        var json = get(address, "/api/glazes");
        assertThat(decoy(json)).isFalse();
        assertThat(json.statusCode()).isEqualTo(429);
        var response = get(address, "/api/images/" + UUID.randomUUID());
        assertThat(decoy(response)).isTrue();
        assertThat(response.body()).hasSize(20000);
    }

    @Test void concurrentDecoysAreBounded() throws Exception {
        String address = newAddress();
        for (int i = 0; i < 3; i++) get(address, "/api/images/" + UUID.randomUUID());
        CompletableFuture<HttpResponse<byte[]>> first = http.sendAsync(request(address, "/api/images/" + UUID.randomUUID()),
                HttpResponse.BodyHandlers.ofByteArray());
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (tarpit.active() == 0 && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(tarpit.active()).isEqualTo(1);
        // The only slot is taken: the flagged client gets the ordinary answer at once.
        var second = get(address, "/api/images/" + UUID.randomUUID());
        assertThat(decoy(second)).isFalse();
        assertThat(second.statusCode()).isEqualTo(404);
        assertThat(second.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        assertThat(decoy(first.get())).isTrue();
        deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (tarpit.active() > 0 && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(tarpit.active()).isZero();
    }

    @Test void clientsAreKeyedOnTheAddressAndIpv6Networks() {
        assertThat(RateLimiter.clientKey("2001:db8:1:2:aaaa::1", null))
                .isEqualTo(RateLimiter.clientKey("2001:db8:1:2:bbbb::9", null));
        assertThat(RateLimiter.clientKey("2001:db8:1:3::1", null)).isNotEqualTo(RateLimiter.clientKey("2001:db8:1:2::1", null));
        assertThat(RateLimiter.clientKey("fe80::1%eth0", null)).isEqualTo(RateLimiter.clientKey("fe80::2", null));
        // Anything that is not an IPv6 literal is never resolved and keys on its raw text.
        for (String raw : List.of("host:1", "g::1", ".:1", "2001:db8::zz", "example.org"))
            assertThat(RateLimiter.clientKey(raw, null)).isEqualTo(raw);
        UUID user = UUID.randomUUID();
        assertThat(RateLimiter.clientKey("198.51.100.7", user)).isEqualTo("198.51.100.7 " + user);
        assertThat(RateLimiter.kind("GET", "/api/health")).isNull();
        assertThat(RateLimiter.kind("GET", "/api/images/x")).isEqualTo(RateLimiter.Kind.IMAGES);
        assertThat(RateLimiter.kind("POST", "/api/auth/login")).isEqualTo(RateLimiter.Kind.AUTH);
        assertThat(RateLimiter.kind("POST", "/api/auth/logout")).isEqualTo(RateLimiter.Kind.API);
        assertThat(RateLimiter.kind("GET", "/api/auth/operator-contact")).isEqualTo(RateLimiter.Kind.CONTACT);
        assertThat(RateLimiter.kind("GET", "/api/auth/options")).isEqualTo(RateLimiter.Kind.API);
        assertThat(RateLimiter.kind("GET", "/api/auth/clear-cache")).isNull();
        assertThat(RateLimiter.perAddress(RateLimiter.Kind.CONTACT)).isTrue();
        assertThat(RateLimiter.perAddress(RateLimiter.Kind.AUTH)).isTrue();
        assertThat(RateLimiter.perAddress(RateLimiter.Kind.API)).isFalse();
        assertThat(RateLimiter.kind("POST", "/api/buddies/link/preview")).isEqualTo(RateLimiter.Kind.TOKEN);
        assertThat(RateLimiter.kind("POST", "/api/buddies/link/request")).isEqualTo(RateLimiter.Kind.TOKEN);
        assertThat(RateLimiter.kind("POST", "/api/buddies/link")).isEqualTo(RateLimiter.Kind.API);
        assertThat(RateLimiter.kind("GET", "/api/buddies/availability")).isEqualTo(RateLimiter.Kind.API);
        assertThat(RateLimiter.perAddress(RateLimiter.Kind.TOKEN)).isFalse();
        assertThat(RateLimiter.kind("POST", "/api/avatars")).isEqualTo(RateLimiter.Kind.AVATAR);
        assertThat(RateLimiter.kind("GET", "/api/avatars/x")).isEqualTo(RateLimiter.Kind.IMAGES);
        assertThat(RateLimiter.kind("HEAD", "/api/avatars/x/small")).isEqualTo(RateLimiter.Kind.IMAGES);
        assertThat(RateLimiter.kind("DELETE", "/api/avatars/x")).isEqualTo(RateLimiter.Kind.API);
        assertThat(RateLimiter.kind("POST", "/api/avatars/x/reports")).isEqualTo(RateLimiter.Kind.API);
        assertThat(RateLimiter.kind("GET", "/api/combinations/author-avatars")).isEqualTo(RateLimiter.Kind.API);
        assertThat(RateLimiter.perAddress(RateLimiter.Kind.AVATAR)).isFalse();
        assertThat(RateLimiter.boosted(RateLimiter.Kind.AVATAR)).isFalse();
        assertThat(RateLimiter.kind("POST", "/api/reports")).isEqualTo(RateLimiter.Kind.REPORTS);
        assertThat(RateLimiter.kind("PUT", "/api/account/diagnostics")).isEqualTo(RateLimiter.Kind.API);
        assertThat(RateLimiter.perAddress(RateLimiter.Kind.REPORTS)).isFalse();
        assertThat(RateLimiter.boosted(RateLimiter.Kind.REPORTS)).isFalse();
        assertThat(RateLimiter.kind("POST", "/api/feedback")).isEqualTo(RateLimiter.Kind.FEEDBACK);
        assertThat(RateLimiter.kind("GET", "/api/admin/feedback")).isEqualTo(RateLimiter.Kind.API);
        assertThat(RateLimiter.perAddress(RateLimiter.Kind.FEEDBACK)).isFalse();
        assertThat(RateLimiter.boosted(RateLimiter.Kind.FEEDBACK)).isFalse();
        assertThat(RateLimiter.boosted(RateLimiter.Kind.TOKEN)).isFalse();
        assertThat(RateLimiter.boosted(RateLimiter.Kind.AUTH)).isFalse();
        assertThat(RateLimiter.boosted(RateLimiter.Kind.API)).isTrue();
    }

    @Test void percentEncodedPathsCountInTheBudgetOfThePathTheyReach() throws Exception {
        // Tomcat's raw request URI keeps %6F and %69, while Spring MVC decodes them and reaches the same handler.
        String address = newAddress();
        for (int i = 0; i < 2; i++) assertThat(get(address, "/api/auth/operator-contact").statusCode()).isEqualTo(200);
        assertThat(get(address, "/api/auth/%6Fperator-contact").statusCode()).isEqualTo(429);
        assertThat(get(address, "/api/auth/operator%2Dcontact").statusCode()).isEqualTo(429);
        String other = newAddress();
        HttpRequest login = HttpRequest.newBuilder(URI.create("http://localhost:" + serverPort + "/api/auth/login"))
                .header("X-Forwarded-For", other).POST(HttpRequest.BodyPublishers.noBody()).build();
        for (int i = 0; i < 3; i++) assertThat(http.send(login, HttpResponse.BodyHandlers.discarding()).statusCode()).isNotEqualTo(429);
        HttpRequest encoded = HttpRequest.newBuilder(URI.create("http://localhost:" + serverPort + "/api/auth/log%69n"))
                .header("X-Forwarded-For", other).POST(HttpRequest.BodyPublishers.noBody()).build();
        assertThat(http.send(encoded, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(429);
    }
}
