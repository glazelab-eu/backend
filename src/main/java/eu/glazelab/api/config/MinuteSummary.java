package eu.glazelab.api.config;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Rate-limits a repeated log line to one per key and minute (backend guide "Logs"): the first event of a key logs at
 * once, later ones within the minute are only counted, and the next event after the minute logs with that count. Keys
 * are a fixed vocabulary (a route template, a reason class, an operation), never a value.
 */
public final class MinuteSummary {
    private static final long WINDOW_NANOS = Duration.ofMinutes(1).toNanos();

    private final LongSupplier clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public MinuteSummary() { this(System::nanoTime); }

    MinuteSummary(LongSupplier clock) { this.clock = clock; }

    /**
     * Counts one event of {@code key}; answers how many events of the key (this one included) the line should report,
     * or 0 when the line was already written this minute.
     */
    public int count(String key) {
        long now = clock.getAsLong();
        Window window = windows.computeIfAbsent(key, k -> new Window(now));
        synchronized (window) {
            window.count++;
            if (window.logged && now - window.start < WINDOW_NANOS) return 0;
            int events = window.count;
            window.count = 0;
            window.start = now;
            window.logged = true;
            return events;
        }
    }

    /** Forgets every window, so the next event of each key logs at once (tests in one JVM share static summaries). */
    public void reset() { windows.clear(); }

    private static final class Window {
        long start;
        boolean logged;
        int count;
        Window(long start) { this.start = start; }
    }
}
