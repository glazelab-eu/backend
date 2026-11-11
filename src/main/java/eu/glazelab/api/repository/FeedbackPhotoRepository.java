package eu.glazelab.api.repository;

import eu.glazelab.api.model.FeedbackPhoto;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** The photos of feedback (backend guide "Feedback"); their deletions are {@link FeedbackRepository}'s. */
public interface FeedbackPhotoRepository extends JpaRepository<FeedbackPhoto, UUID> {}
