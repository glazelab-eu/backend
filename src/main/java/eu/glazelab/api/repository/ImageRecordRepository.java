package eu.glazelab.api.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.UUID;
import eu.glazelab.api.model.ImageRecord;
import eu.glazelab.api.model.ImageVisibility;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ImageRecordRepository extends JpaRepository<ImageRecord, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<ImageRecord> findByUploadedBy_IdOrderByIdAsc(UUID userId);
    /** One ID-ordered lock set, so concurrent ID-ordered image locks (glaze removal) cannot deadlock with it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from ImageRecord i where i.uploadedBy.id = :userId or i.club.id in :clubIds order by i.id")
    List<ImageRecord> lockByUploaderOrClubs(UUID userId, Collection<UUID> clubIds);
    /** PRIVATE rows keep their uploader, so one committed after the deletion's lock blocks the account removal. */
    @Modifying
    @Query("update ImageRecord i set i.uploadedBy = null where i.uploadedBy.id = :userId and i.visibility <> eu.glazelab.api.model.ImageVisibility.PRIVATE")
    void anonymiseSharedUploads(UUID userId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from ImageRecord i where i.glaze.id = :glazeId order by i.id")
    List<ImageRecord> lockByGlazeId(UUID glazeId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from ImageRecord i where i.combination.id = :combinationId order by i.id")
    List<ImageRecord> lockByCombinationId(UUID combinationId);
    // Listings name each picture's uploader (ImageView.uploader) without a query per picture.
    @EntityGraph(attributePaths = "uploadedBy")
    List<ImageRecord> findByCombination_IdInOrderByCreatedAtDesc(Collection<UUID> combinationIds);
    @EntityGraph(attributePaths = "uploadedBy")
    List<ImageRecord> findByGlaze_IdOrderByCreatedAtDesc(UUID glazeId);
    @EntityGraph(attributePaths = "uploadedBy")
    List<ImageRecord> findByGlazeIsNotNullOrderByCreatedAtDesc();
    @EntityGraph(attributePaths = "uploadedBy")
    List<ImageRecord> findByGlaze_IdInOrderByCreatedAtDesc(Collection<UUID> glazeIds);
    /** The picture's parent, uploader and club; {@code getVisibility} is the effective one (PRIVATE on a draft). */
    interface Target {
        UUID getCombinationId(); UUID getGlazeId(); UUID getUploaderId(); UUID getClubId(); ImageVisibility getStoredVisibility();
        Instant getPublishedAt();
        default boolean isOnDraft() { return getCombinationId() != null && getPublishedAt() == null; }
        default ImageVisibility getVisibility() { return ImageRecord.effective(getStoredVisibility(), isOnDraft()); }
    }
    @Query("select c.id as combinationId, g.id as glazeId, u.id as uploaderId, club.id as clubId, i.visibility as storedVisibility,"
            + " c.publishedAt as publishedAt from ImageRecord i"
            + " left join i.combination c left join i.glaze g left join i.uploadedBy u left join i.club club where i.id = :id")
    Optional<Target> target(UUID id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from ImageRecord i where i.id = :id")
    Optional<ImageRecord> lockById(UUID id);
}
