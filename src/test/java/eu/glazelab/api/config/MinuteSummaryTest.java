package eu.glazelab.api.config;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** One line per key and minute: the first event at once, the rest counted into the next line after the minute. */
class MinuteSummaryTest {
    @Test void theFirstEventLogsAndTheOthersOfTheMinuteAreCountedIntoTheNextLine() {
        var now = new AtomicLong();
        var summary = new MinuteSummary(now::get);
        assertEquals(1, summary.count("read"));
        assertEquals(0, summary.count("read"));
        assertEquals(1, summary.count("write"), "keys are counted apart");
        now.addAndGet(Duration.ofSeconds(59).toNanos());
        assertEquals(0, summary.count("read"));
        now.addAndGet(Duration.ofSeconds(1).toNanos());
        assertEquals(3, summary.count("read"), "the two counted ones and this one");
        assertEquals(0, summary.count("read"));
        summary.reset();
        assertEquals(1, summary.count("read"));
    }
}
