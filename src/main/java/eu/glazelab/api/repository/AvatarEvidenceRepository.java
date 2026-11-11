package eu.glazelab.api.repository;

import eu.glazelab.api.model.AvatarEvidence;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface AvatarEvidenceRepository extends JpaRepository<AvatarEvidence, UUID> {
    /** A removed picture's evidence row, locked: the decision on a report of a picture that is gone. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from AvatarEvidence e where e.avatarId = :avatarId")
    Optional<AvatarEvidence> lockById(UUID avatarId);

    /** Kept objects of a removed picture. */
    interface Kept { UUID getAvatarId(); String getStorageKey(); }

    /**
     * What the daily purge deletes, in ID order (the order every multi-row evidence write takes): evidence with no open
     * report and no decision (DISMISSED or ACTIONED) after {@code cutoff} (a CLOSED report, expired or closed with the
     * account, keeps nothing), and evidence of a deleted account (its reports' owner is nulled; the account deletion
     * releases it itself, so this only catches rows from before V32).
     */
    @Query("select e.avatarId as avatarId, e.storageKey as storageKey from AvatarEvidence e where e.storageKey is not null"
            + " and (not exists (select 1 from AvatarReport r where r.avatarId = e.avatarId"
            + " and (r.status = eu.glazelab.api.model.AvatarReportStatus.OPEN"
            + " or (r.status <> eu.glazelab.api.model.AvatarReportStatus.CLOSED and r.closedAt > :cutoff)))"
            + " or exists (select 1 from AvatarReport r where r.avatarId = e.avatarId and r.ownerId is null))"
            + " order by e.avatarId")
    List<Kept> expired(Instant cutoff);

    /** Kept objects of the account's earlier pictures (its reports name it as owner), in ID order. */
    @Query("select e.avatarId as avatarId, e.storageKey as storageKey from AvatarEvidence e where e.storageKey is not null"
            + " and e.avatarId in (select r.avatarId from AvatarReport r where r.ownerId = :ownerId) order by e.avatarId")
    List<Kept> keptOf(UUID ownerId);

    /** Forgets the key (the caller deletes the objects after commit); 0 when another transaction did so first. */
    @Modifying
    @Query("update AvatarEvidence e set e.storageKey = null where e.avatarId = :avatarId and e.storageKey = :storageKey")
    int release(UUID avatarId, String storageKey);
}
