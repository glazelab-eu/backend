package eu.glazelab.api.security;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/** The bounded client table (BE-4): a spent budget is never refunded by other keys, and quiet clients make room. */
class RateLimiterTableTest {
    private static final long SECOND = 1_000_000_000L;
    private static final int MAX = 50;
    private final AtomicLong clock = new AtomicLong(-5 * SECOND);
    private final Logger logger = (Logger) LoggerFactory.getLogger(RateLimiter.class);
    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();

    @BeforeEach void attach() { captured.start(); logger.addAppender(captured); }
    @AfterEach void detach() { logger.detachAppender(captured); }

    /** AUTH: 3 at once, then 6 a minute (full again 30 s after the last take); the tarpit is off. */
    private RateLimiter limiter() {
        return new RateLimiter(settings(MAX), clock::get);
    }

    static RateLimitSettings settings(int maxClients) {
        var budget = new RateLimitSettings.Budget(3, 6);
        var tarpit = new RateLimitSettings.TarpitSettings(false, 30, 20, Duration.ofMinutes(5), Duration.ofMinutes(15), 0, 1, 1000,
                Duration.ofSeconds(1), 1000, Duration.ofSeconds(1));
        var signIn = new RateLimitSettings.SignInDelay(5, Duration.ofMinutes(1), Duration.ofMinutes(15), Duration.ofHours(24));
        return new RateLimitSettings(true, 1, budget, budget, budget, budget, budget, budget, budget, budget, 1, maxClients, tarpit, signIn);
    }

    private static String address(int i) { return "198.51." + (i / 250) + "." + (i % 250); }

    private static void spend(RateLimiter limiter, String key) {
        while (limiter.take(key, RateLimiter.Kind.AUTH, false) == 0) { /* spend the burst */ }
    }

    @Test void aSpentBudgetStaysSpentHoweverManyOtherClientsAreSeen() {
        RateLimiter limiter = limiter();
        spend(limiter, "203.0.113.1");
        // Far more keys than the table holds, each spending its own budget: none of them is quiet yet.
        for (int i = 0; i < 20 * MAX; i++) {
            clock.addAndGet(SECOND / 1000);
            spend(limiter, address(i));
        }
        assertThat(limiter.take("203.0.113.1", RateLimiter.Kind.AUTH, false)).isPositive();
        assertThat(limiter.tracked()).isEqualTo(MAX);
    }

    @Test void newClientsShareOneBudgetWhileNoTrackedClientIsQuiet() {
        RateLimiter limiter = limiter();
        for (int i = 0; i < MAX; i++) spend(limiter, address(i));
        // Two clients the table has no room for: the first spends the shared budget, the second finds it spent.
        spend(limiter, "203.0.113.2");
        assertThat(limiter.take("203.0.113.3", RateLimiter.Kind.AUTH, false)).isPositive();
        assertThat(captured.list).hasSize(1).allSatisfy(line -> assertThat(line.getFormattedMessage())
                .contains(MAX + " clients tracked", "share one budget").doesNotContain("203.0.113"));
    }

    @Test void quietClientsLeaveSoNewClientsGetTheirOwnBudgets() {
        RateLimiter limiter = limiter();
        for (int i = 0; i < MAX; i++) spend(limiter, address(i));
        // A budget of 3 at 6 a minute is full again 30 seconds after it was spent: those clients hold nothing more.
        clock.addAndGet(31 * SECOND);
        spend(limiter, "203.0.113.4");
        assertThat(limiter.take("203.0.113.5", RateLimiter.Kind.AUTH, false)).isZero();
        assertThat(limiter.tracked()).isEqualTo(2);
        assertThat(captured.list).isEmpty();
    }

    @Test void aClientThatSpentPartOfItsBudgetIsNotQuietUntilItRefilled() {
        RateLimiter limiter = limiter();
        limiter.take(address(0), RateLimiter.Kind.AUTH, false);
        for (int i = 1; i < MAX; i++) limiter.take(address(i), RateLimiter.Kind.AUTH, false);
        clock.addAndGet(5 * SECOND);
        // One take refills in 10 seconds: after 5 nobody is quiet, so a new client shares the overflow budget.
        limiter.take("203.0.113.6", RateLimiter.Kind.AUTH, false);
        assertThat(limiter.tracked()).isEqualTo(MAX);
        clock.addAndGet(6 * SECOND);
        limiter.take("203.0.113.7", RateLimiter.Kind.AUTH, false);
        assertThat(limiter.tracked()).isEqualTo(1);
    }
}
