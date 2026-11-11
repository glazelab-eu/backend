package eu.glazelab.api.service;

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The request IDs of recent {@code POST /api/stock/add} calls per account, so a repeat after a lost answer (the person
 * taps "+1 jar" again, the client sends the same ID) answers the entry instead of adding the jar twice. Kept in memory
 * for {@link #KEEP} and at most {@link #MAX} IDs: the deployment runs one API instance, and a restart forgets only the
 * repeat protection of the last minutes. More instances would need a shared store.
 *
 * <p>Both calls run under the owner lock of the addition: a repeat queued behind the first sees its ID once the first
 * committed. The ID is remembered before the commit and forgotten again when the transaction rolls back.
 */
@Component
public class StockAdditionRepeats {
    static final Duration KEEP = Duration.ofMinutes(15);
    static final int MAX = 20_000;

    private record Key(UUID account, UUID request) {}
    private record Done(UUID stockId, UUID glazeId, long at) {}

    private final Map<Key, Done> done = new LinkedHashMap<>();

    /** The entry an earlier addition with this request ID of the account wrote to this glaze, if it is still remembered. */
    public synchronized Optional<UUID> earlier(UUID account, UUID request, UUID glazeId) {
        forgetOld();
        Done found = done.get(new Key(account, request));
        return found == null || !found.glazeId().equals(glazeId) ? Optional.empty() : Optional.of(found.stockId());
    }

    /** Remembers the addition from the commit of the current transaction on; a rollback forgets it. */
    public void remember(UUID account, UUID request, UUID glazeId, UUID stockId) {
        Key key = new Key(account, request);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void beforeCommit(boolean readOnly) { put(key, new Done(stockId, glazeId, System.nanoTime())); }
            @Override public void afterCompletion(int status) { if (status != STATUS_COMMITTED) forget(key); }
        });
    }

    private synchronized void put(Key key, Done value) {
        done.remove(key);
        done.put(key, value);
        forgetOld();
    }

    private synchronized void forget(Key key) { done.remove(key); }

    /** Insertion order is time order: drop from the oldest until one is young enough and the map is within its bound. */
    private void forgetOld() {
        long oldest = System.nanoTime() - KEEP.toNanos();
        for (Iterator<Done> it = done.values().iterator(); it.hasNext(); ) {
            Done next = it.next();
            if (next.at() - oldest >= 0 && done.size() <= MAX) break;
            it.remove();
        }
    }
}
