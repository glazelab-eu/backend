package eu.glazelab.api.security;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * In-memory token buckets per client and kind of request, plus the scraping detection that feeds the tarpit.
 * AUTH and CONTACT (the operator address, harvestable) are keyed on the real client IP (an IPv6 /64); API, image and
 * TOKEN (buddy link lookups, against token guessing), AVATAR (profile picture uploads, each one a full decode and
 * encode), REPORTS (error report batches) and FEEDBACK (feedback with up to three photos, a handful an hour) requests additionally use the signed-in account, so people sharing one address each get their own budget;
 * only API and image budgets grow for a signed-in session. At most {@code maxClients} clients are tracked
 * ({@link BoundedTable}): a client is forgotten only once it is quiet (every budget full again, no flag, no open count),
 * so a spent budget never comes back because many other keys were seen. While the table is full of clients that are not
 * quiet, new clients share one overflow client and its budgets.
 */
@Component
public class RateLimiter {
    public enum Kind { API, IMAGES, AUTH, CONTACT, TOKEN, AVATAR, REPORTS, FEEDBACK }

    private static final long SECOND = 1_000_000_000L;
    /** Text that {@link InetAddress#getByName} parses as an IPv6 literal (or refuses) without any DNS lookup: it starts with a hex digit or ':'. */
    private static final Pattern IPV6_LITERAL = Pattern.compile("[0-9A-Fa-f:][0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*");
    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private final RateLimitSettings settings;
    private final LongSupplier clock;
    private final BoundedTable<Client> clients;
    private final Client overflow;
    /** Whether the table was found full of clients that are not quiet (logged once per such period). */
    private boolean overflowing;

    @Autowired public RateLimiter(RateLimitSettings settings) { this(settings, System::nanoTime); }

    /** With a clock in nanoseconds, for tests. */
    RateLimiter(RateLimitSettings settings, LongSupplier clock) {
        this.settings = settings;
        this.clock = clock;
        clients = new BoundedTable<>(settings.maxClients());
        overflow = new Client(clock.getAsLong());
    }

    public boolean enabled() { return settings.enabled(); }

    /** The bucket for a request, or null for requests that are never limited (the health check and the cache clearing). */
    public static Kind kind(String method, String path) {
        // No server work and nothing reflected: never limited, so a neighbour on a shared address cannot make the cache clearing fail.
        if (path.equals("/api/health") || path.equals("/api/auth/clear-cache")) return null;
        if (path.equals("/api/auth/operator-contact")) return Kind.CONTACT;
        if (method.equals("POST") && (path.equals("/api/buddies/link/preview") || path.equals("/api/buddies/link/request"))) return Kind.TOKEN;
        if (method.equals("POST") && path.equals("/api/avatars")) return Kind.AVATAR;
        if (method.equals("POST") && path.equals("/api/reports")) return Kind.REPORTS;
        if (method.equals("POST") && path.equals("/api/feedback")) return Kind.FEEDBACK;
        // Photos and profile pictures: the image budget, and unknown IDs count towards the tarpit.
        if ((path.startsWith("/api/images/") || path.startsWith("/api/avatars/")) && (method.equals("GET") || method.equals("HEAD"))) return Kind.IMAGES;
        // Signing out always works; every other auth POST (sign-in, registration, emails) is strictly limited.
        if (path.startsWith("/api/auth/") && method.equals("POST") && !path.equals("/api/auth/logout")) return Kind.AUTH;
        return Kind.API;
    }

    /** AUTH and CONTACT count per address whatever the session: an account or a signed-in boost must not widen them. */
    public static boolean perAddress(Kind kind) {
        return kind == Kind.AUTH || kind == Kind.CONTACT;
    }

    /** Only browsing budgets grow for a signed-in session; AUTH, CONTACT, TOKEN, AVATAR, REPORTS and FEEDBACK stay as tight as for a guest. */
    public static boolean boosted(Kind kind) {
        return kind == Kind.API || kind == Kind.IMAGES;
    }

    /** Client key: the address (IPv6 shortened to its /64 network) plus the account for signed-in sessions. Never logged: lines name a {@link ClientLabel}. */
    public static String clientKey(String remoteAddress, UUID userId) {
        String address = remoteAddress;
        int zone = remoteAddress.indexOf('%');
        String literal = zone < 0 ? remoteAddress : remoteAddress.substring(0, zone);
        try {
            // Only text shaped like an IPv6 literal is parsed (the zone dropped), so nothing ever becomes a DNS lookup.
            if (IPV6_LITERAL.matcher(literal).matches() && InetAddress.getByName(literal) instanceof Inet6Address v6)
                address = HexFormat.of().formatHex(v6.getAddress(), 0, 8) + "::/64";
        } catch (UnknownHostException | SecurityException ignored) {
            // Not an address literal; keep the raw value.
        }
        return userId == null ? address : address + " " + userId;
    }

    /** Takes one request from the client's budget: 0 when allowed, else the seconds to wait (Retry-After). */
    public long take(String key, Kind kind, boolean signedIn) {
        RateLimitSettings.Budget budget = switch (kind) {
            case API -> settings.api();
            case IMAGES -> settings.images();
            case AUTH -> settings.auth();
            case CONTACT -> settings.contact();
            case TOKEN -> settings.token();
            case AVATAR -> settings.avatar();
            case REPORTS -> settings.reports();
            case FEEDBACK -> settings.feedback();
        };
        double scale = settings.factor() * (signedIn && boosted(kind) ? settings.signedInFactor() : 1);
        double capacity = Math.max(1, budget.burst() * scale);
        double perSecond = Math.max(1e-6, budget.perMinute() * scale / 60);
        long now = clock.getAsLong();
        Client client = client(key, now);
        synchronized (client) {
            released(key, client, now);
            long wait = client.take(kind.ordinal(), capacity, perSecond, now);
            if (wait > 0 && client.rejected(now, settings.tarpit()))
                log.warn("Rate limit: client {} keeps exceeding its budget; tarpit for {}", ClientLabel.of(key), settings.tarpit().flagFor());
            return wait;
        }
    }

    /** Counts an image request for an unknown ID (404, or 400 for a malformed one), which listings never link to. */
    public void unknownImage(String key) {
        long now = clock.getAsLong();
        Client client = client(key, now);
        synchronized (client) {
            released(key, client, now);
            if (client.unknownImage(now, settings.tarpit()))
                log.warn("Rate limit: client {} requests many unknown images; tarpit for {}", ClientLabel.of(key), settings.tarpit().flagFor());
        }
    }

    /**
     * Counts a failed sign-in of this client. One WARN line when the count in the tarpit window first reaches 5 and
     * again at each fourfold (20, 80, ...), so a guessing run is one to four lines, never one per attempt. The email and the address are never logged.
     */
    public void signInFailed(String key) {
        long now = clock.getAsLong();
        Client client = client(key, now);
        synchronized (client) {
            int count = client.signInFailed(now, settings.tarpit());
            if (count > 0) log.warn("Sign-in failures: client {} has failed {} sign-ins within {}", ClientLabel.of(key), count, settings.tarpit().window());
        }
    }

    /** Logs the end of a tarpit flag once, on the client's next request after the flag ran out (nothing runs in between). */
    private void released(String key, Client client, long now) {
        if (client.released(now)) log.info("Rate limit: client {} is released from the tarpit", ClientLabel.of(key));
    }

    /** Whether this request of a flagged client should get the decoy instead (only occasionally, GET only). */
    public boolean tarpitCandidate(String key, String method) {
        var tarpit = settings.tarpit();
        if (!tarpit.enabled() || !method.equals("GET")) return false;
        long now = clock.getAsLong();
        Client client = client(key, now);
        synchronized (client) {
            released(key, client, now);
            return client.flagged(now) && ThreadLocalRandom.current().nextDouble() < tarpit.probability();
        }
    }

    private Client client(String key, long now) {
        synchronized (clients) {
            Client client = clients.getOrCreate(key, now, () -> new Client(now), tracked -> tracked.quiet(now));
            if (client != null) {
                if (!clients.full()) overflowing = false;
                return client;
            }
            if (!overflowing) log.warn("Rate limit: {} clients tracked and none quiet; new clients share one budget until some are", clients.size());
            overflowing = true;
            return overflow;
        }
    }

    /** The number of clients tracked, for tests. */
    int tracked() {
        synchronized (clients) { return clients.size(); }
    }

    /** Mutable per-client state; guarded by its own monitor. */
    private static final class Client {
        private final double[] tokens = new double[Kind.values().length];
        private final long[] updated = new long[Kind.values().length];
        /** Per budget: when the last Retry-After given runs out (0: none given yet). */
        private final long[] waitUntil = new long[Kind.values().length];
        /** Whether the last refusal came before the client's previous wait in that budget ran out. */
        private boolean ignoredWait;
        private long windowStart;
        private int rejectedSeconds;
        private long lastRejectedSecond = Long.MIN_VALUE;
        private int unknownImages;
        private boolean flagged;
        private long failuresStart;
        private int signInFailures;
        private int failureLogAt = 5;
        private long flaggedUntil;
        /** From then on the client holds nothing a new one would not: every budget full, no flag, no open count. */
        private long quietAt;

        Client(long now) {
            Arrays.fill(tokens, -1);
            windowStart = now;
            quietAt = now;
        }

        boolean quiet(long now) {
            return now - quietAt >= 0;
        }

        private void busyUntil(long time) {
            if (time - quietAt > 0) quietAt = time;
        }

        long take(int bucket, double capacity, double perSecond, long now) {
            double available = tokens[bucket] < 0 ? capacity
                    : Math.min(capacity, tokens[bucket] + (now - updated[bucket]) * perSecond / SECOND);
            updated[bucket] = now;
            if (available >= 1) {
                tokens[bucket] = available - 1;
                busyUntil(now + (long) Math.ceil((capacity - tokens[bucket]) / perSecond * SECOND));
                return 0;
            }
            tokens[bucket] = available;
            busyUntil(now + (long) Math.ceil((capacity - available) / perSecond * SECOND));
            long wait = Math.max(1, (long) Math.ceil((1 - available) / perSecond));
            // Asked again before the wait it was told: only that counts towards the tarpit.
            ignoredWait = waitUntil[bucket] != 0 && now - waitUntil[bucket] < 0;
            waitUntil[bucket] = now + wait * SECOND;
            return wait;
        }

        /**
         * Counts each second in which the client was refused although it had not yet waited the Retry-After it was given
         * (once per second, so one oversized burst never looks like scraping). A client that waits as told never counts,
         * however long it stays over its budget.
         */
        boolean rejected(long now, RateLimitSettings.TarpitSettings tarpit) {
            roll(now, tarpit);
            if (!ignoredWait) return false;
            long second = now / SECOND;
            if (second == lastRejectedSecond) return false;
            lastRejectedSecond = second;
            busyUntil(windowStart + tarpit.window().toNanos());
            return ++rejectedSeconds >= tarpit.rejectedSeconds() && flag(now, tarpit);
        }

        boolean unknownImage(long now, RateLimitSettings.TarpitSettings tarpit) {
            roll(now, tarpit);
            busyUntil(windowStart + tarpit.window().toNanos());
            return ++unknownImages >= tarpit.unknownImages() && flag(now, tarpit);
        }

        /** The failure count when it just reached a logging step (5, 20, 80, ...), else 0. */
        int signInFailed(long now, RateLimitSettings.TarpitSettings tarpit) {
            if (now - failuresStart >= tarpit.window().toNanos()) {
                failuresStart = now;
                signInFailures = 0;
                failureLogAt = 5;
            }
            busyUntil(failuresStart + tarpit.window().toNanos());
            if (++signInFailures < failureLogAt) return 0;
            failureLogAt *= 4;
            return signInFailures;
        }

        /** True once after a flag expired. */
        boolean released(long now) {
            if (!flagged || now - flaggedUntil < 0) return false;
            flagged = false;
            return true;
        }

        boolean flagged(long now) {
            return flagged && now - flaggedUntil < 0;
        }

        private boolean flag(long now, RateLimitSettings.TarpitSettings tarpit) {
            boolean newly = !flagged(now);
            flagged = true;
            flaggedUntil = now + tarpit.flagFor().toNanos();
            busyUntil(flaggedUntil);
            rejectedSeconds = 0;
            unknownImages = 0;
            return newly && tarpit.enabled();
        }

        private void roll(long now, RateLimitSettings.TarpitSettings tarpit) {
            if (now - windowStart >= tarpit.window().toNanos()) {
                windowStart = now;
                rejectedSeconds = 0;
                unknownImages = 0;
            }
        }
    }
}
