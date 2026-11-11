package eu.glazelab.api.dto;

import eu.glazelab.api.model.AvatarPictureState;
import eu.glazelab.api.model.AvatarReportStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Profile pictures (backend guide "Profile pictures"); uploads answer {@code CommonDtos.IdView} with the new picture's ID. */
public final class AvatarDtos {
    private AvatarDtos() {}

    /** The current picture of an account that authored a published combination (matched against {@code CombinationView.authorId}). */
    public record AuthorAvatarView(UUID userId, UUID avatarId) {}

    /**
     * One profile-picture report in the site admins' queue. {@code avatarId} names the reported picture (the same ID
     * for every report of it); {@code ownerId}/{@code owner} its account (null once deleted), {@code reporter} the
     * reporter's display name and {@code closedBy} the deciding admin's (null when there is none or it was deleted).
     * {@code picture} says where the picture is now and {@code hasPicture} whether
     * {@code GET /api/admin/avatar-reports/{id}/picture} can show it (the current picture or its kept evidence; never
     * once the account is gone: its pictures are deleted with it, its reports are CLOSED and keep no {@code reason}).
     */
    public record AvatarReportView(UUID id, UUID avatarId, @Schema(nullable = true) UUID ownerId,
            @Schema(nullable = true) String owner, @Schema(nullable = true) String reporter,
            @Schema(nullable = true) String reason, Instant createdAt, AvatarReportStatus status,
            @Schema(nullable = true) Instant closedAt, @Schema(nullable = true) String closedBy,
            AvatarPictureState picture, boolean hasPicture) {}
    public record AvatarReportPageView(List<AvatarReportView> entries, boolean hasMore) {}
}
