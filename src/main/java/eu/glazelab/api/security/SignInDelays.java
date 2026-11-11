package eu.glazelab.api.security;

import eu.glazelab.api.model.EmailAddresses;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The sign-in delay per address (backend guide "Security"), against password guessing from many networks: the per-IP
 * AUTH budget alone lets a routed /48 try 65,536 times as often. Keyed by a hash of the address in its lookup form,
 * known or not, so the delay tells nothing about accounts; whichever client tries, the address's count is the same.
 * Every attempt counts when it starts (so parallel attempts cannot slip through while a password is hashed) and a
 * success clears the count. From the {@code freeFailures}th counted attempt on, the next one waits {@code firstDelay},
 * doubling up to {@code maxDelay}: a delay, never a lockout, and email links (magic link, password reset, activation)
 * keep working and clear it too. Off with the rate limits. A full table keeps every delay in force and forgets the
 * smaller counts first; when nothing can go, new addresses are not delayed (never a stranger's lockout) and one line
 * says so.
 */
@Component
public class SignInDelays {
    public static final String DELAYED = "Too many failed sign-ins for this address. Please wait a few minutes or sign in with an email link.";
    private static final long SECOND = 1_000_000_000L;
    private static final Logger log = LoggerFactory.getLogger(SignInDelays.class);

    private final RateLimitSettings settings;
    private final LongSupplier clock;
    private final BoundedTable<Entry> addresses;
    private boolean overflowing;

    @Autowired public SignInDelays(RateLimitSettings settings) { this(settings, System::nanoTime); }

    /** With a clock in nanoseconds, for tests. */
    SignInDelays(RateLimitSettings settings, LongSupplier clock) {
        this.settings = settings;
        this.clock = clock;
        addresses = new BoundedTable<>(settings.maxClients());
    }

    public boolean enabled() { return settings.enabled(); }

    /** Starts a sign-in attempt for the address: 0 when it may go ahead (counted until it succeeds), else the seconds to wait. */
    public long attempt(String email) {
        var delay = settings.signIn();
        long now = clock.getAsLong();
        synchronized (addresses) {
            Entry entry = addresses.getOrCreate(key(email), now, Entry::new, held -> held.forgettable(now, delay));
            if (entry == null) {
                if (!overflowing) log.warn("Sign-in delays: {} addresses tracked and all needed; new addresses are not delayed until some are", addresses.size());
                overflowing = true;
                return 0;
            }
            if (!addresses.full()) overflowing = false;
            if (entry.attempts > 0 && now - entry.lastAttempt - delay.forgetAfter().toNanos() >= 0) entry.attempts = 0;
            if (entry.attempts >= delay.freeFailures() && now - entry.delayUntil < 0)
                return Math.max(1, (entry.delayUntil - now + SECOND - 1) / SECOND);
            entry.attempts++;
            entry.lastAttempt = now;
            if (entry.attempts >= delay.freeFailures()) entry.delayUntil = now + wait(entry.attempts, delay);
            return 0;
        }
    }

    /** A successful sign-in, or an email link that proved the mailbox: the address starts afresh. */
    public void reset(String email) {
        synchronized (addresses) { addresses.remove(key(email)); }
    }

    /** The number of addresses tracked, for tests. */
    int tracked() {
        synchronized (addresses) { return addresses.size(); }
    }

    /**
     * The address's {@link EmailAddresses#key}, the form sign-in looks the account up by, so every writing that reaches
     * one account (a long s or a dotless i for the plain letter included) shares one count. Only a hash is kept.
     */
    private static String key(String email) {
        return SecureTokens.hash(EmailAddresses.key(email == null ? "" : email));
    }

    /** The delay after the {@code attempts}th counted attempt: the first delay at the threshold, doubling up to the maximum. */
    private static long wait(int attempts, RateLimitSettings.SignInDelay delay) {
        long max = delay.maxDelay().toNanos();
        long wait = delay.firstDelay().toNanos();
        for (int i = delay.freeFailures(); i < attempts && wait < max; i++) wait *= 2;
        return Math.min(wait, max);
    }

    private static final class Entry {
        private int attempts;
        private long lastAttempt;
        private long delayUntil;

        /** Quiet (forgotten anyway), or a count below the threshold (no delay yet): what a full table lets go. */
        boolean forgettable(long now, RateLimitSettings.SignInDelay delay) {
            return attempts < delay.freeFailures() || now - lastAttempt - delay.forgetAfter().toNanos() >= 0;
        }
    }
}
