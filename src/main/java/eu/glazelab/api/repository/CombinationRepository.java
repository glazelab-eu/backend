package eu.glazelab.api.repository;

import java.time.Instant;
import java.util.UUID;
import eu.glazelab.api.model.Combination;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface CombinationRepository extends JpaRepository<Combination, UUID> {
    @Modifying
    @Query("update Combination c set c.createdBy = null where c.createdBy.id = :userId")
    void anonymiseAuthor(UUID userId);
    /** The order of every combination listing: newest first by publication (a draft by creation), then by ID. */
    String LISTING_ORDER = " order by coalesce(c.publishedAt, c.createdAt) desc, c.id";
    /** Published combinations and the account's own drafts (none for guests), in {@link #LISTING_ORDER}. */
    @EntityGraph(attributePaths = {"tags", "createdBy"})
    @Query("select c from Combination c left join c.createdBy a where c.publishedAt is not null or a.id = :userId" + LISTING_ORDER)
    List<Combination> findVisible(UUID userId);
    /** The combinations among {@code ids} the account may see (published or own drafts; none of the drafts for guests). */
    @EntityGraph(attributePaths = {"tags", "createdBy"})
    @Query("select c from Combination c left join c.createdBy a where c.id in :ids and (c.publishedAt is not null or a.id = :userId)"
            + LISTING_ORDER)
    List<Combination> findVisibleAmong(Collection<UUID> ids, UUID userId);
    /** The IDs among {@code ids} the account may see, as scalars. */
    @Query("select c.id from Combination c left join c.createdBy a where c.id in :ids and (c.publishedAt is not null or a.id = :userId)")
    List<UUID> visibleIdsAmong(Collection<UUID> ids, UUID userId);
    /** The IDs among {@code ids} that exist, whoever may see them (the sync tells tombstones from hidden drafts). */
    @Query("select c.id from Combination c where c.id in :ids")
    List<UUID> existingAmong(Collection<UUID> ids);
    /** One page of the visible IDs after {@code after}, by ID (the paged first sync). */
    @Query("select c.id from Combination c left join c.createdBy a where c.id > :after and (c.publishedAt is not null or a.id = :userId) order by c.id")
    List<UUID> visibleIdsAfter(UUID after, UUID userId, Pageable page);
    /** How many combinations the account's listing has (the sync integrity count). */
    @Query("select count(c) from Combination c left join c.createdBy a where c.publishedAt is not null or a.id = :userId")
    long countVisible(UUID userId);
    /** The variations of a combination, as scalars: their views name the source only while the viewer may see it. */
    @Query("select c.id as id, a.id as authorId, c.publishedAt as publishedAt from Combination c left join c.createdBy a where c.basedOnId = :sourceId")
    List<Ownership> variationsOf(UUID sourceId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Combination c where c.id = :id")
    Optional<Combination> lockById(UUID id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Combination c where c.createdBy.id = :userId order by c.id")
    List<Combination> lockByAuthor(UUID userId);
    interface Ownership {
        UUID getId(); UUID getAuthorId(); Instant getPublishedAt();
        /** Drafts exist only for their author; everyone else is told they do not exist. */
        default boolean visibleTo(UUID userId) { return getPublishedAt() != null || (userId != null && userId.equals(getAuthorId())); }
    }
    @Query("select c.id as id, a.id as authorId, c.publishedAt as publishedAt from Combination c left join c.createdBy a where c.id = :id")
    Optional<Ownership> ownership(UUID id);
    /** Combinations with a glaze step that uses the glaze (custom steps have none), by title. */
    @Query("select c from Combination c where c.id in (select s.combination.id from CombinationStep s"
            + " where s.glaze.id = :glazeId) order by c.title, c.id")
    List<Combination> findUsingGlaze(UUID glazeId);
}
