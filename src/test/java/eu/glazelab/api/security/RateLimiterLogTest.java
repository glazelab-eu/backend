package eu.glazelab.api.security;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/** Operator log lines of the rate limiter: tarpit flagged and released, sign-in failures counted per client, named by label only. */
class RateLimiterLogTest {
    private final Logger logger = (Logger) LoggerFactory.getLogger(RateLimiter.class);
    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();

    @BeforeEach void attach() { captured.start(); logger.addAppender(captured); }
    @AfterEach void detach() { logger.detachAppender(captured); }

    private static RateLimiter limiter(Duration flagFor) {
        var budget = new RateLimitSettings.Budget(1, 1);
        var tarpit = new RateLimitSettings.TarpitSettings(true, 1, 1, Duration.ofMinutes(5), flagFor, 1, 1, 1000,
                Duration.ofMillis(50), 1000, Duration.ofSeconds(1));
        var signIn = new RateLimitSettings.SignInDelay(5, Duration.ofMinutes(1), Duration.ofMinutes(15), Duration.ofHours(24));
        return new RateLimiter(new RateLimitSettings(true, 1, budget, budget, budget, budget, budget, budget, budget, budget, 1, 100, tarpit, signIn));
    }

    private List<String> lines() { return captured.list.stream().map(ILoggingEvent::getFormattedMessage).toList(); }

    @Test void tarpitFlagAndReleaseAreEachLoggedOnce() throws Exception {
        RateLimiter limiter = limiter(Duration.ofMillis(100));
        String key = RateLimiter.clientKey("203.0.113.9", null);
        String label = ClientLabel.of(key);
        limiter.take(key, RateLimiter.Kind.API, false);
        // The first refusal tells the client to wait; asking again before that is what counts.
        assertThat(limiter.take(key, RateLimiter.Kind.API, false)).isPositive();
        assertThat(lines()).isEmpty();
        assertThat(limiter.take(key, RateLimiter.Kind.API, false)).isPositive();
        assertThat(lines()).hasSize(1).allSatisfy(line -> assertThat(line).contains("client " + label, "tarpit for"));
        Thread.sleep(200);
        limiter.take(key, RateLimiter.Kind.API, false);
        limiter.take(key, RateLimiter.Kind.API, false);
        assertThat(lines()).filteredOn(line -> line.contains("released")).hasSize(1).allSatisfy(line -> assertThat(line).contains("client " + label));
        assertNoAddress("203.0.113.9");
    }

    @Test void signInFailuresAreCountedNotLoggedPerAttempt() {
        RateLimiter limiter = limiter(Duration.ofMinutes(1));
        String key = RateLimiter.clientKey("203.0.113.10", null);
        for (int i = 0; i < 100; i++) limiter.signInFailed(key);
        assertThat(lines()).hasSize(3);
        assertThat(lines().get(0)).contains("client " + ClientLabel.of(key), "failed 5 sign-ins");
        assertThat(lines().get(1)).contains("failed 20 sign-ins");
        assertThat(lines().get(2)).contains("failed 80 sign-ins");
        assertThat(lines()).allSatisfy(line -> assertThat(line).doesNotContain("@"));
        assertNoAddress("203.0.113.10");
    }

    /** IPv6 clients are keyed by their /64; neither the address nor the network appears, and a signed-in key keeps its account ID. */
    @Test void ipv6ClientsAreNamedByLabelOnly() {
        RateLimiter limiter = limiter(Duration.ofMinutes(1));
        String key = RateLimiter.clientKey("2001:db8:1:2:aaaa::1", null);
        for (int i = 0; i < 5; i++) limiter.signInFailed(key);
        var account = java.util.UUID.randomUUID();
        String signedIn = RateLimiter.clientKey("2001:db8:1:2:bbbb::9", account);
        limiter.take(signedIn, RateLimiter.Kind.API, true);
        limiter.take(signedIn, RateLimiter.Kind.API, true);
        limiter.take(signedIn, RateLimiter.Kind.API, true);
        assertThat(lines()).hasSize(2);
        assertThat(lines().get(0)).contains("client " + ClientLabel.of(key));
        // Same /64, so the same label; the account stays a row ID.
        assertThat(lines().get(1)).contains("client " + ClientLabel.of(key) + " account " + account);
        assertNoAddress("2001:db8:1:2:aaaa::1", "2001:db8:1:2:bbbb::9", key, "20010db8", "2001:db8", "::/64", "/64");
    }

    @Test void aLabelIsStableShortAndKeyed() {
        String key = RateLimiter.clientKey("198.51.100.7", null);
        assertThat(ClientLabel.of(key)).matches("[0-9a-f]{8}").isEqualTo(ClientLabel.of(key)).isNotEqualTo(ClientLabel.of("198.51.100.8"));
        // Keyed: not a plain hash of the address that anyone could recompute.
        assertThat(ClientLabel.of(key)).isNotEqualTo(sha256Prefix(key));
    }

    private static String sha256Prefix(String text) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)), 0, 4);
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private void assertNoAddress(String... forms) {
        assertThat(lines()).isNotEmpty().allSatisfy(line -> assertThat(line).doesNotContain(forms));
    }
}
