package eu.glazelab.api.dto;

import eu.glazelab.api.model.Feedback;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** In-app feedback (backend guide "Feedback"); sending answers {@code CommonDtos.IdView} with the new feedback's ID. */
public final class FeedbackDtos {
    private FeedbackDtos() {}

    /**
     * A photo of a feedback: {@code GET /api/admin/feedback/photos/{id}} (at most 1600 px) and {@code …/card} (480 px),
     * for site admins only.
     */
    public record FeedbackPhotoView(UUID id, int width, int height) {}

    /**
     * One feedback in the site admins' list: the text, the context the sender kept (each null when left out), the
     * sender (the account's current display name; feedback goes with its account), its state and photos in their order.
     */
    public record FeedbackView(UUID id, String message, @Schema(nullable = true) String route,
            @Schema(nullable = true) String build, @Schema(nullable = true) String language,
            @Schema(nullable = true) Feedback.Device device, UUID senderId, String sender, Instant createdAt,
            Feedback.Status status, @Schema(nullable = true) Instant doneAt, List<FeedbackPhotoView> photos) {}

    /** A page of the list, newest first; {@code open} counts every open feedback (not only this page's). */
    public record FeedbackPageView(List<FeedbackView> entries, boolean hasMore, long open) {}

    /** A site admin marks a feedback done (deleted after the retention) or open again. */
    public record FeedbackStatusInput(@NotNull Feedback.Status status) {}
}
