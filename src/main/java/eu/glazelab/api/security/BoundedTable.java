package eu.glazelab.api.security;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * In-memory state per key with at most {@code max} entries, for {@link RateLimiter} and {@link SignInDelays}. An entry
 * leaves only when the caller's predicate lets it go (once it holds nothing a fresh entry would not), never because newer
 * keys arrived: spending many keys never refunds an old one. A full table sweeps when a new key arrives, at most once a
 * second (a sweep reads every entry), and answers null while nothing could go. Not thread-safe: callers synchronize on it.
 */
final class BoundedTable<V> {
    private static final long SWEEP_EVERY = 1_000_000_000L;
    private final int max;
    private final Map<String, V> entries = new HashMap<>();
    private boolean swept;
    private long nextSweep;

    BoundedTable(int max) { this.max = max; }

    int size() { return entries.size(); }
    boolean full() { return entries.size() >= max; }
    void remove(String key) { entries.remove(key); }

    /** The key's entry; a new one when there is room (after a due sweep of {@code evictable} entries), else null. */
    V getOrCreate(String key, long now, Supplier<V> create, Predicate<V> evictable) {
        V entry = entries.get(key);
        if (entry != null) return entry;
        if (full() && sweepDue(now)) entries.values().removeIf(evictable);
        if (full()) return null;
        entry = create.get();
        entries.put(key, entry);
        return entry;
    }

    private boolean sweepDue(long now) {
        if (swept && now - nextSweep < 0) return false;
        swept = true;
        nextSweep = now + SWEEP_EVERY;
        return true;
    }
}
