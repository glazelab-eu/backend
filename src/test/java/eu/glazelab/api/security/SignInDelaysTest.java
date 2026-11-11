package eu.glazelab.api.security;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/** The sign-in delay per address (BE-4): 5 free attempts, then 1, 2, 4, 8 and at most 15 minutes. */
class SignInDelaysTest {
    private static final long SECOND = 1_000_000_000L;
    private static final long MINUTE = 60 * SECOND;
    private final AtomicLong clock = new AtomicLong(-3 * MINUTE);
    private final Logger logger = (Logger) LoggerFactory.getLogger(SignInDelays.class);
    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();

    @BeforeEach void attach() { captured.start(); logger.addAppender(captured); }
    @AfterEach void detach() { logger.detachAppender(captured); }

    private SignInDelays delays(int maxClients) {
        return new SignInDelays(RateLimiterTableTest.settings(maxClients), clock::get);
    }

    private static void fail(SignInDelays delays, String email, int times) {
        for (int i = 0; i < times; i++) assertThat(delays.attempt(email)).isZero();
    }

    @Test void fiveAttemptsAreFreeThenTheDelayDoublesUpToFifteenMinutes() {
        SignInDelays delays = delays(100);
        fail(delays, "potter@example.test", 5);
        long[] expected = {60, 120, 240, 480, 900, 900};
        for (long seconds : expected) {
            assertThat(delays.attempt("potter@example.test")).isEqualTo(seconds);
            clock.addAndGet(seconds * SECOND - SECOND / 2);
            // Still half a second to go: refused, and the refusal itself is not counted.
            assertThat(delays.attempt("potter@example.test")).isEqualTo(1);
            clock.addAndGet(SECOND / 2);
            fail(delays, "potter@example.test", 1);
        }
    }

    @Test void theAddressCountsWhateverItsCaseAndSpacesAndAnyAddressCountsTheSame() {
        SignInDelays delays = delays(100);
        fail(delays, " Potter@Example.TEST", 2);
        fail(delays, "potter@example.test ", 3);
        assertThat(delays.attempt("POTTER@example.test")).isEqualTo(60);
        // Another address is untouched; an address without an account is delayed exactly like one with.
        fail(delays, "nobody-registered-this@example.test", 5);
        assertThat(delays.attempt("nobody-registered-this@example.test")).isEqualTo(60);
        assertThat(delays.attempt("other@example.test")).isZero();
    }

    @Test void aSuccessOrAnEmailLinkClearsTheCount() {
        SignInDelays delays = delays(100);
        fail(delays, "potter@example.test", 4);
        delays.reset("Potter@example.test");
        fail(delays, "potter@example.test", 4);
        assertThat(delays.tracked()).isEqualTo(1);
        fail(delays, "potter@example.test", 1);
        assertThat(delays.attempt("potter@example.test")).isEqualTo(60);
        delays.reset("potter@example.test");
        assertThat(delays.attempt("potter@example.test")).isZero();
    }

    @Test void theCountIsForgottenADayAfterTheLastAttempt() {
        SignInDelays delays = delays(100);
        fail(delays, "potter@example.test", 5);
        clock.addAndGet(24 * 60 * MINUTE);
        fail(delays, "potter@example.test", 4);
        clock.addAndGet(MINUTE);
        fail(delays, "potter@example.test", 1);
        assertThat(delays.attempt("potter@example.test")).isEqualTo(60);
    }

    @Test void aFullTableKeepsEveryDelayInForceAndLetsSmallCountsGo() {
        SignInDelays delays = delays(4);
        fail(delays, "target@example.test", 5);
        // Small counts fill the table; a new address makes them go (one sweep a second), never the delay in force.
        for (int i = 0; i < 40; i++) {
            clock.addAndGet(SECOND);
            fail(delays, "spray" + i + "@example.test", 1);
        }
        assertThat(delays.tracked()).isLessThanOrEqualTo(4);
        assertThat(delays.attempt("target@example.test")).isPositive();
        assertThat(captured.list).isEmpty();
    }

    @Test void aTableFullOfDelaysDelaysNoNewAddressAndSaysSo() {
        SignInDelays delays = delays(2);
        fail(delays, "first@example.test", 5);
        fail(delays, "second@example.test", 5);
        // Never a lockout of strangers: a new address goes ahead untracked, however often.
        fail(delays, "third@example.test", 10);
        assertThat(delays.attempt("first@example.test")).isPositive();
        assertThat(captured.list).hasSize(1).allSatisfy(line -> assertThat(line.getFormattedMessage())
                .contains("2 addresses tracked", "not delayed").doesNotContain("@"));
    }
}
