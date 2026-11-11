package eu.glazelab.api.repository;

import eu.glazelab.api.model.ChangeLogEntry;
import eu.glazelab.api.model.ChangeLogEntry.Audience;
import eu.glazelab.api.model.ChangeLogEntry.Kind;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Reads of the sync change log; rows are written only by {@code service.ChangeRecorder}. */
public interface ChangeLogRepository extends JpaRepository<ChangeLogEntry, Long> {
    /** The newest revision, null while the log is empty. */
    @Query("select max(e.revision) from ChangeLogEntry e")
    Long top();

    /** The newest revision up to {@code top} that the viewer may receive (audiences as in {@link #changes}), null if none. */
    @Query("select max(e.revision) from ChangeLogEntry e where e.revision <= :top and (e.audience = :everyone"
            + " or (e.audience = :club and e.audienceId in :clubIds) or (e.audience = :account and e.audienceId = :userId))")
    Long topFor(long top, UUID userId, Collection<UUID> clubIds, Audience everyone, Audience club, Audience account);

    interface Change { Kind getKind(); UUID getItemId(); }
    /**
     * The distinct items of the rows up to {@code top} after revision {@code since} or written from {@code from} on
     * (the overlap window), for an audience the viewer belongs to: everyone, one of {@code clubIds} (never empty:
     * pass a placeholder) or the account {@code userId} (null for guests); at most {@code limit} items (a caller
     * asks for one more than it delivers, so a fabricated old cursor never materialises the whole log).
     */
    @Query("select distinct e.kind as kind, e.itemId as itemId from ChangeLogEntry e"
            + " where e.revision <= :top and (e.revision > :since or e.changedAt >= :from) and (e.audience = :everyone"
            + " or (e.audience = :club and e.audienceId in :clubIds) or (e.audience = :account and e.audienceId = :userId))")
    List<Change> changes(long since, Instant from, long top, UUID userId, Collection<UUID> clubIds,
            Audience everyone, Audience club, Audience account, Pageable limit);

    /** Removes the rows written before {@code before} (the retention purge), except the newest one. */
    @Modifying @Query("delete from ChangeLogEntry e where e.changedAt < :before"
            + " and e.revision < (select max(o.revision) from ChangeLogEntry o)")
    int purge(Instant before);
}
