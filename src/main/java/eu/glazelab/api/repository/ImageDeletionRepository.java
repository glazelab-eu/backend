package eu.glazelab.api.repository;

import eu.glazelab.api.model.ImageDeletion;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ImageDeletionRepository extends JpaRepository<ImageDeletion, UUID> {
    @Modifying
    @Query("update ImageDeletion d set d.uploaderId = null where d.uploaderId = :userId")
    void anonymiseUploader(UUID userId);
    @Modifying
    @Query("update ImageDeletion d set d.deletedBy = null where d.deletedBy = :userId")
    void anonymiseActor(UUID userId);

    /** The retention purge: one statement on {@code image_deletion_time_idx}. */
    @Modifying
    @Query("delete from ImageDeletion d where d.deletedAt < :cutoff")
    int purge(Instant cutoff);
}
