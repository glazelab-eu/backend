package eu.glazelab.api.repository;

import eu.glazelab.api.model.Feedback;
import eu.glazelab.api.model.FeedbackPhoto;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/**
 * Feedback (backend guide "Feedback"). Deletions are bulk statements; the photo rows go with their feedback by the
 * foreign key's cascade, so every deletion reads the photos' storage keys first and deletes their objects after commit.
 */
public interface FeedbackRepository extends JpaRepository<Feedback, UUID> {
    /** One row of the site admins' listing: the feedback and its sender's current display name. */
    interface Row {
        Feedback getFeedback();
        String getSender();
    }

    String ROW = "select f as feedback, u.displayName as sender from Feedback f join AppUser u on u.id = f.userId";

    /** Every feedback, in the order of {@code page}'s sort. */
    @Query(value = ROW, countQuery = "select count(f) from Feedback f")
    Slice<Row> listing(Pageable page);

    /** The feedback in one state, in the order of {@code page}'s sort. */
    @Query(value = ROW + " where f.status = :status", countQuery = "select count(f) from Feedback f where f.status = :status")
    Slice<Row> listing(Feedback.Status status, Pageable page);

    long countByStatus(Feedback.Status status);

    /** The photos of the listed feedback, in their order (one statement per page). */
    @Query("select p from FeedbackPhoto p where p.feedbackId in :ids order by p.feedbackId, p.photoIndex")
    List<FeedbackPhoto> photosOf(Collection<UUID> ids);

    /** Marks one feedback done; one already done keeps the time it was done (its retention runs from that). 0 when it is gone. */
    @Modifying @Query("update Feedback f set f.status = eu.glazelab.api.model.Feedback.Status.DONE, f.doneAt = coalesce(f.doneAt, :now) where f.id = :id")
    int markDone(UUID id, Instant now);

    /** Opens one feedback again, without a done time; 0 when it is gone. */
    @Modifying @Query("update Feedback f set f.status = eu.glazelab.api.model.Feedback.Status.OPEN, f.doneAt = null where f.id = :id")
    int reopen(UUID id);

    @Query("select p.storageKey from FeedbackPhoto p where p.feedbackId = :id")
    List<String> keysOf(UUID id);

    @Modifying @Query("delete from Feedback f where f.id = :id")
    int deleteOne(UUID id);

    /** The photos of every feedback of the account (account deletion, a ban and the pending purge). */
    @Query("select p.storageKey from FeedbackPhoto p, Feedback f where f.id = p.feedbackId and f.userId = :userId")
    List<String> keysOfUser(UUID userId);

    @Modifying @Query("delete from Feedback f where f.userId = :userId")
    int deleteOfUser(UUID userId);

    /** The photos of the feedback the retention purge deletes. */
    @Query("select p.storageKey from FeedbackPhoto p, Feedback f where f.id = p.feedbackId and f.status = eu.glazelab.api.model.Feedback.Status.DONE and f.doneAt < :before")
    List<String> expiredKeys(Instant before);

    @Modifying @Query("delete from Feedback f where f.status = eu.glazelab.api.model.Feedback.Status.DONE and f.doneAt < :before")
    int purge(Instant before);
}
