package eu.glazelab.api.service;

import eu.glazelab.api.dto.CombinationDtos.CombinationView;
import eu.glazelab.api.dto.GlazeDtos.GlazeView;
import eu.glazelab.api.dto.SyncDtos.CombinationPageView;
import eu.glazelab.api.dto.SyncDtos.Resync;
import eu.glazelab.api.dto.SyncDtos.SyncView;
import eu.glazelab.api.model.ChangeLogEntry.Audience;
import eu.glazelab.api.model.ChangeLogEntry.Kind;
import eu.glazelab.api.repository.ChangeLogRepository;
import eu.glazelab.api.repository.CombinationRepository;
import eu.glazelab.api.repository.GlazeRepository;
import eu.glazelab.api.security.CurrentUser;
import eu.glazelab.api.security.CurrentUser.Viewer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The incremental sync of glazes and combinations (backend AGENTS.md "Sync"). A cursor is
 * {@code <revision>.<epoch milliseconds>}: the newest log revision in the answer's snapshot that the caller may
 * receive and the server time taken before that snapshot. A delta answers every row after the revision plus every
 * row written within {@link #OVERLAP} before the time, so a transaction that inserted its row before the previous
 * answer but committed after it (a lower revision the previous answer could not see) is still delivered, as long as
 * it commits within the overlap after its log insert (the insert is its last statement). Re-delivered items are
 * idempotent upserts.
 */
@Service @Slf4j
public class SyncService {
    /**
     * The data format of the views; bump it when cached views must be refetched (a new or changed field). 2: the
     * combination times are instants with {@code Z} (V36), no longer server-local times.
     */
    public static final int FORMAT = 2;
    public static final Duration OVERLAP = Duration.ofMinutes(2);
    public static final Duration RETENTION = Duration.ofDays(30);
    /** More changed items than this answer {@code TOO_MANY}: a full load is cheaper then. */
    public static final int MAX_ITEMS = 2000;
    /** The largest page of the paged first sync. */
    public static final int MAX_PAGE = 1000;
    private static final UUID NONE = new UUID(0, 0);

    private final ChangeLogRepository changeLog;
    private final LibraryViews views;
    private final GlazeRepository glazes;
    private final CombinationRepository combinations;
    private final CurrentUser current;
    private final TransactionTemplate snapshot;
    private final TransactionTemplate reads;
    private final TransactionTemplate writes;

    public SyncService(ChangeLogRepository log, LibraryViews views, GlazeRepository glazes, CombinationRepository combinations,
            CurrentUser current, PlatformTransactionManager transactions) {
        this.changeLog = log;
        this.views = views;
        this.glazes = glazes;
        this.combinations = combinations;
        this.current = current;
        // One snapshot for the cursor, the rows, the views and the counts, so the counts describe exactly the delta's state.
        snapshot = new TransactionTemplate(transactions);
        snapshot.setReadOnly(true);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reads = new TransactionTemplate(transactions);
        reads.setReadOnly(true);
        writes = new TransactionTemplate(transactions);
    }

    private record Cursor(long revision, Instant time) {
        /** Null for anything this server did not answer. */
        static Cursor parse(String text) {
            if (text == null) return null;
            int dot = text.indexOf('.');
            try {
                long revision = Long.parseLong(text.substring(0, Math.max(dot, 0)));
                long millis = Long.parseLong(text.substring(dot + 1));
                return revision < 0 || millis < 0 ? null : new Cursor(revision, Instant.ofEpochMilli(millis));
            } catch (NumberFormatException | IndexOutOfBoundsException unreadable) {
                return null;
            }
        }
    }

    /** A delta since {@code since}, or a resync answer whose cursor a following full load starts from. */
    public SyncView sync(String since, String epoch, Integer format, Authentication auth) {
        // Taken before the snapshot: every row the snapshot misses commits after this moment.
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return snapshot.execute(status -> {
            Viewer viewer = current.viewer(auth);
            String epochNow = epoch(viewer);
            long top = Objects.requireNonNullElse(changeLog.top(), 0L);
            Set<UUID> clubs = viewer.clubIds().isEmpty() ? Set.of(NONE) : viewer.clubIds();
            // The cursor names the newest row this caller may receive, not the global newest: rows of other audiences
            // (someone's draft, a private photo) must not move it, or watching it would tell when others work in private.
            // Rows of other audiences between it and the global newest never reach this caller, so nothing is skipped.
            long seen = Objects.requireNonNullElse(changeLog.topFor(top, viewer.userId(), clubs, Audience.PUBLIC,
                    Audience.CLUB, Audience.USER), 0L);
            Cursor from = Cursor.parse(since);
            Resync resync = from == null || from.revision() > top || from.time().isAfter(now) ? Resync.CURSOR
                    : !Objects.equals(format, FORMAT) ? Resync.FORMAT
                    : !epochNow.equals(epoch) ? Resync.EPOCH
                    : from.time().isBefore(now.minus(RETENTION)) ? Resync.EXPIRED : null;
            if (resync == null) {
                var changed = changeLog.changes(from.revision(), from.time().minus(OVERLAP), top, viewer.userId(),
                        clubs, Audience.PUBLIC, Audience.CLUB, Audience.USER, PageRequest.of(0, MAX_ITEMS + 1));
                if (changed.size() <= MAX_ITEMS)
                    return delta(changed, viewer, Math.max(seen, from.revision()) + "." + now.toEpochMilli(), epochNow);
                resync = Resync.TOO_MANY;
            }
            return new SyncView(FORMAT, seen + "." + now.toEpochMilli(), epochNow, resync, List.of(), List.of(), List.of(),
                    List.of(), glazes.count(), combinations.countVisible(viewer.userId()));
        });
    }

    private SyncView delta(List<ChangeLogRepository.Change> changed, Viewer viewer, String cursor, String epoch) {
        Set<UUID> glazeIds = ids(changed, Kind.GLAZE), combinationIds = ids(changed, Kind.COMBINATION);
        List<GlazeView> glazeViews = views.glazes(glazeIds, viewer);
        List<CombinationView> combinationViews = views.combinations(combinationIds, viewer);
        // What is not rendered is a tombstone only when the item is gone. An existing item that does not render was
        // never this viewer's (glazes are public, publication is one-way, a draft is its author's), so no client of
        // theirs holds it, and a row a writer logged for too wide an audience never names someone else's draft.
        glazeViews.forEach(view -> glazeIds.remove(view.id()));
        combinationViews.forEach(view -> combinationIds.remove(view.id()));
        if (!combinationIds.isEmpty()) combinations.existingAmong(combinationIds).forEach(combinationIds::remove);
        return new SyncView(FORMAT, cursor, epoch, null, glazeViews, combinationViews, List.copyOf(glazeIds),
                List.copyOf(combinationIds), glazes.count(), combinations.countVisible(viewer.userId()));
    }

    private static Set<UUID> ids(List<ChangeLogRepository.Change> changed, Kind kind) {
        return changed.stream().filter(change -> change.getKind() == kind).map(ChangeLogRepository.Change::getItemId)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * Who the caller is to the sync: the account, its clubs with their roles and the site-admin flag. Any change to
     * them (joining, leaving or removal, a role change, an SQL grant) changes what the caller's views show without a
     * log row for each item, so a client with another epoch loads in full. Derived, never stored: a removal would
     * otherwise have to write the removed member's row, whose lock it must not take (backend AGENTS.md "Locks").
     */
    static String epoch(Viewer viewer) {
        var text = new StringBuilder(String.valueOf(viewer.userId())).append('|').append(viewer.siteAdmin());
        viewer.clubIds().stream().sorted().forEach(club ->
                text.append('|').append(club).append(viewer.adminClubIds().contains(club) ? ":ADMIN" : ":MEMBER"));
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException(missing);
        }
    }

    /** One page of the caller's combinations by ID after {@code after} (null: from the start), at most {@link #MAX_PAGE}. */
    public CombinationPageView page(UUID after, int limit, Authentication auth) {
        int size = Math.clamp(limit, 1, MAX_PAGE);
        return reads.execute(status -> {
            Viewer viewer = current.viewer(auth);
            List<UUID> ids = combinations.visibleIdsAfter(after == null ? NONE : after, viewer.userId(), PageRequest.of(0, size));
            return new CombinationPageView(views.combinations(ids, viewer), ids.size() < size ? null : ids.getLast());
        });
    }

    /**
     * Daily: drops rows older than the retention plus the overlap (a cursor inside the retention may still need
     * rows from its overlap window), always keeping the newest row so the newest revision never goes back.
     */
    @Scheduled(fixedDelay = 86_400_000, initialDelay = 3_600_000)
    public int purge() {
        Integer removed = writes.execute(status -> changeLog.purge(Instant.now().minus(RETENTION).minus(OVERLAP)));
        if (removed != null && removed > 0) log.info("Removed {} sync change log rows older than the retention", removed);
        return removed == null ? 0 : removed;
    }
}
