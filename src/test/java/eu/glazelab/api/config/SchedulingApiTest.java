package eu.glazelab.api.config;

import eu.glazelab.api.support.ApiTestBase;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.TaskScheduler;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The scheduled purges share a pool of two (application.properties): one stuck purge never holds up the others. */
class SchedulingApiTest extends ApiTestBase {
    @Autowired TaskScheduler scheduler;

    @Test void aBlockedScheduledTaskDoesNotHoldUpTheNextOne() throws Exception {
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        var ran = new CountDownLatch(1);
        scheduler.schedule(() -> {
            started.countDown();
            try { release.await(20, TimeUnit.SECONDS); } catch (InterruptedException stop) { Thread.currentThread().interrupt(); }
        }, Instant.now());
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            scheduler.schedule(ran::countDown, Instant.now());
            assertTrue(ran.await(5, TimeUnit.SECONDS), "the second task waited for the blocked one");
        } finally {
            release.countDown();
        }
    }
}
