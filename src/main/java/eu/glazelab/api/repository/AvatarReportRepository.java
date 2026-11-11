package eu.glazelab.api.repository;

import eu.glazelab.api.model.AvatarPictureState;
import eu.glazelab.api.model.AvatarReport;
import eu.glazelab.api.model.AvatarReportStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface AvatarReportRepository extends JpaRepository<AvatarReport, UUID> {
    boolean existsByAvatarIdAndReporterId(UUID avatarId, UUID reporterId);

    /** Status and closing time of one report. */
    interface State { AvatarReportStatus getStatus(); Instant getClosedAt(); }
    /** Every report of one picture (read under that picture's row lock). */
    @Query("select r.status as status, r.closedAt as closedAt from AvatarReport r where r.avatarId = :avatarId")
    List<State> statesOf(UUID avatarId);

    @Query("select r.avatarId from AvatarReport r where r.id = :id")
    Optional<UUID> avatarOf(UUID id);

    /** The account's pictures (current or kept as evidence) with an open report, in ID order (the order they are locked in). */
    @Query("select distinct r.avatarId from AvatarReport r where r.ownerId = :ownerId"
            + " and r.status = eu.glazelab.api.model.AvatarReportStatus.OPEN order by r.avatarId")
    List<UUID> openPicturesOf(UUID ownerId);

    /**
     * The account goes (deletion, ban, pending purge; before its row, while {@code owner_id} still names it): its open
     * reports are CLOSED without a decision, and every report about its pictures loses its text (it may name the owner).
     */
    @Modifying
    @Query("update AvatarReport r set r.status = eu.glazelab.api.model.AvatarReportStatus.CLOSED, r.closedAt = :now"
            + " where r.ownerId = :ownerId and r.status = eu.glazelab.api.model.AvatarReportStatus.OPEN")
    int closeWithAccount(UUID ownerId, Instant now);

    @Modifying
    @Query("update AvatarReport r set r.reason = null where r.ownerId = :ownerId and r.reason is not null")
    int forgetReasonsAbout(UUID ownerId);

    /** Pictures with a report opened before {@code cutoff} that is still open, in ID order. */
    @Query("select distinct r.avatarId from AvatarReport r where r.status = eu.glazelab.api.model.AvatarReportStatus.OPEN"
            + " and r.createdAt < :cutoff order by r.avatarId")
    List<UUID> expiringPictures(Instant cutoff);

    /** Closes the picture's reports opened before {@code cutoff} that nobody decided as CLOSED (no {@code closed_by}). */
    @Modifying
    @Query("update AvatarReport r set r.status = eu.glazelab.api.model.AvatarReportStatus.CLOSED, r.closedAt = :now"
            + " where r.avatarId = :avatarId and r.status = eu.glazelab.api.model.AvatarReportStatus.OPEN and r.createdAt < :cutoff")
    int expire(UUID avatarId, Instant cutoff, Instant now);

    @Query("select r.status from AvatarReport r where r.id = :id")
    Optional<AvatarReportStatus> statusOf(UUID id);

    /** Closes every open report of one picture with the site admin's decision; closed reports keep theirs. */
    @Modifying
    @Query("update AvatarReport r set r.status = :decision, r.closedAt = :now, r.closedBy = :admin"
            + " where r.avatarId = :avatarId and r.status = eu.glazelab.api.model.AvatarReportStatus.OPEN")
    int close(UUID avatarId, AvatarReportStatus decision, Instant now, UUID admin);

    /**
     * One row of the admin queue: the report, its picture's account and the reporter and closing admin by current
     * name, and where the picture is now (its current row's ID, or how it went and whether its objects are kept).
     */
    interface QueueRow {
        UUID getId(); UUID getAvatarId(); UUID getOwnerId(); String getOwner(); String getReporter(); String getReason();
        Instant getCreatedAt(); AvatarReportStatus getStatus(); Instant getClosedAt(); String getClosedBy();
        UUID getCurrent(); AvatarPictureState getOutcome(); String getEvidenceKey();
    }
    String QUEUE = "select r.id as id, r.avatarId as avatarId, r.ownerId as ownerId, o.displayName as owner,"
            + " u.displayName as reporter, r.reason as reason, r.createdAt as createdAt, r.status as status,"
            + " r.closedAt as closedAt, c.displayName as closedBy, a.id as current, e.outcome as outcome,"
            + " e.storageKey as evidenceKey from AvatarReport r left join AppUser o on o.id = r.ownerId"
            + " left join AppUser u on u.id = r.reporterId left join AppUser c on c.id = r.closedBy"
            + " left join UserAvatar a on a.id = r.avatarId left join AvatarEvidence e on e.avatarId = r.avatarId";
    /** A slice, so no count query: one statement per page whatever the queue holds. */
    @Query(QUEUE)
    Slice<QueueRow> queue(Pageable page);

    @Query(QUEUE + " where r.status = :status")
    Slice<QueueRow> queue(AvatarReportStatus status, Pageable page);
}
