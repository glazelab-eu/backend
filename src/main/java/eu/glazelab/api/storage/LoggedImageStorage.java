package eu.glazelab.api.storage;

import eu.glazelab.api.config.MinuteSummary;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The configured store with its failures logged (backend guide "Logs"): one WARN line per operation and minute with
 * the exception class and how many failed, never the key, path or message. A read of a missing object is no failure
 * ({@link ImageStorage#get} answers it with {@link NoSuchFileException}, which callers expect).
 */
final class LoggedImageStorage implements ImageStorage, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(LoggedImageStorage.class);

    private final ImageStorage store;
    private final MinuteSummary failures = new MinuteSummary();

    LoggedImageStorage(ImageStorage store) { this.store = store; }

    @Override public void put(String key, byte[] jpeg) throws IOException {
        try { store.put(key, jpeg); }
        catch (IOException | RuntimeException error) { failed("write", error); throw error; }
    }

    @Override public byte[] get(String key) throws IOException {
        try { return store.get(key); }
        catch (NoSuchFileException missing) { throw missing; }
        catch (IOException | RuntimeException error) { failed("read", error); throw error; }
    }

    @Override public void delete(String key) throws IOException {
        try { store.delete(key); }
        catch (IOException | RuntimeException error) { failed("delete", error); throw error; }
    }

    @Override public void close() throws Exception {
        if (store instanceof AutoCloseable closeable) closeable.close();
    }

    private void failed(String operation, Exception error) {
        int events = failures.count(operation);
        if (events > 0)
            log.warn("Image storage {} failed ({}); {} failed {}(s) since the last line", operation,
                    error.getClass().getName(), events, operation);
    }
}
