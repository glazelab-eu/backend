package eu.glazelab.api.storage;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Storage failures (backend guide "Logs"): one WARN line per operation and minute, the exception class, never the key. */
class LoggedImageStorageTest {
    private static final String KEY = "0b6f7a4e-1d2c-4f3b-9a8e-123456789abc.jpg";
    private final Logger logger = (Logger) LoggerFactory.getLogger(LoggedImageStorage.class);
    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();

    @BeforeEach void attach() { captured.start(); logger.addAppender(captured); }
    @AfterEach void detach() { logger.detachAppender(captured); }

    /** Fails every operation with the key in the message, like the file adapter's exceptions do. */
    private static final class Broken implements ImageStorage {
        boolean missing;
        public void put(String key, byte[] jpeg) throws IOException { throw new AccessDeniedException("/srv/images/" + key); }
        public byte[] get(String key) throws IOException {
            if (missing) throw new NoSuchFileException(key);
            throw new IOException("connection reset reading " + key);
        }
        public void delete(String key) { throw new IllegalStateException("bucket gone for " + key); }
    }

    @Test void failuresAreLoggedPerOperationOnceAMinuteWithoutTheKey() {
        var broken = new Broken();
        var storage = new LoggedImageStorage(broken);
        for (int i = 0; i < 3; i++) assertThrows(AccessDeniedException.class, () -> storage.put(KEY, new byte[1]));
        assertThrows(IOException.class, () -> storage.get(KEY));
        assertThrows(IllegalStateException.class, () -> storage.delete(KEY));
        broken.missing = true;
        var missing = assertThrows(NoSuchFileException.class, () -> storage.get(KEY));
        assertSame(NoSuchFileException.class, missing.getClass());
        List<ILoggingEvent> events = List.copyOf(captured.list);
        assertThat(events).allSatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.WARN));
        assertThat(events.stream().map(ILoggingEvent::getFormattedMessage)).containsExactly(
                "Image storage write failed (java.nio.file.AccessDeniedException); 1 failed write(s) since the last line",
                "Image storage read failed (java.io.IOException); 1 failed read(s) since the last line",
                "Image storage delete failed (java.lang.IllegalStateException); 1 failed delete(s) since the last line");
        assertThat(events).allSatisfy(event -> {
            assertThat(event.getThrowableProxy()).isNull();
            assertThat(event.getFormattedMessage()).doesNotContain(KEY, "/srv/images", "connection reset", "bucket gone");
        });
    }
}
