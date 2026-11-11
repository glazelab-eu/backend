package eu.glazelab.api.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-client request budgets (token buckets: {@code burst} requests at once, refilled at {@code perMinute})
 * and the tarpit for clients that keep scraping. {@code factor} scales every budget (load tests).
 */
@ConfigurationProperties("glazelab.rate-limit")
public record RateLimitSettings(boolean enabled, double factor, Budget api, Budget images, Budget auth,
        Budget contact, Budget token, Budget avatar, Budget reports, Budget feedback, double signedInFactor, int maxClients, TarpitSettings tarpit,
        SignInDelay signIn) {

    /** {@code perMinute} may be a fraction: 0.1 refills six requests an hour (feedback). */
    public record Budget(int burst, double perMinute) {}

    /**
     * The sign-in delay per address ({@link SignInDelays}, never scaled by {@code factor}): from the {@code freeFailures}th
     * attempt without a success on, the next one waits {@code firstDelay}, doubling up to {@code maxDelay}; the count is
     * forgotten {@code forgetAfter} after the last attempt.
     */
    public record SignInDelay(int freeFailures, Duration firstDelay, Duration maxDelay, Duration forgetAfter) {}

    /**
     * A client is flagged for {@code flagFor} after being rejected in {@code rejectedSeconds} different seconds,
     * or after {@code unknownImages} image requests for unknown IDs, within one {@code window}. A flagged
     * client's GET requests are answered, with {@code probability}, by a slow decoy: {@code chunkBytes} every
     * {@code interval}, ending after {@code maxBytes} or {@code maxDuration}, at most {@code maxConnections} at once.
     */
    public record TarpitSettings(boolean enabled, int rejectedSeconds, int unknownImages, Duration window,
            Duration flagFor, double probability, int maxConnections, int chunkBytes, Duration interval,
            long maxBytes, Duration maxDuration) {}
}
