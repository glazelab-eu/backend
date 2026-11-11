package eu.glazelab.api.repository;

import eu.glazelab.api.model.ImageReport;
import eu.glazelab.api.model.ImageVisibility;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ImageReportRepository extends JpaRepository<ImageReport, UUID> {
    boolean existsByImage_IdAndReporter_Id(UUID imageId, UUID reporterId);

    /** One row of the admin queue: the report with its photo's target, the reporter's and the uploader's current name. */
    interface QueueRow {
        UUID getId(); UUID getImageId(); UUID getGlazeId(); UUID getCombinationId(); String getCombinationTitle();
        String getGlazeName(); ImageVisibility getVisibility(); String getReporter(); String getReason();
        Instant getCreatedAt(); UUID getUploaderId(); String getUploader();
    }
    /** A slice, so no count query: one statement per page whatever the queue holds. */
    @Query("select r.id as id, i.id as imageId, g.id as glazeId, c.id as combinationId, c.title as combinationTitle,"
            + " g.name as glazeName, i.visibility as visibility, u.displayName as reporter, r.reason as reason,"
            + " r.createdAt as createdAt, up.id as uploaderId, up.displayName as uploader from ImageReport r join r.image i"
            + " left join i.combination c left join i.glaze g left join r.reporter u left join i.uploadedBy up")
    Slice<QueueRow> queue(Pageable page);

    /** Scalars only: the rows themselves disappear with their photo through the database cascade. */
    interface Carried { UUID getId(); UUID getReporterId(); String getReason(); Instant getCreatedAt(); }
    @Query("select r.id as id, u.id as reporterId, r.reason as reason, r.createdAt as createdAt from ImageReport r"
            + " left join r.reporter u where r.image.id = :imageId")
    List<Carried> carriedOf(UUID imageId);

    @Modifying
    @Query("delete from ImageReport r where r.id = :id")
    int dismiss(UUID id);

    /** Reports nobody decided: deleted like a dismissal once opened before {@code cutoff}; one statement on {@code image_report_time_idx}. */
    @Modifying
    @Query("delete from ImageReport r where r.createdAt < :cutoff")
    int expire(Instant cutoff);
}
