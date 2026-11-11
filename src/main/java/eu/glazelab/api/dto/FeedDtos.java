package eu.glazelab.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The activity feed (backend guide "Activity feed"). */
public final class FeedDtos {
    private FeedDtos() {}

    /**
     * What was added. Buddy entries come from accepted buddies, club entries from the viewer's current clubs; BUDDY_REQUEST is a
     * pending request addressed to the viewer (shown whatever the sources: it is addressed to them).
     */
    public enum FeedType { BUDDY_COMBINATION, BUDDY_GLAZE, NEW_BUDDY, BUDDY_REQUEST, CLUB_COMBINATION, CLUB_MEMBER, CLUB_GLAZE }

    /**
     * One addition the viewer can see now. {@code itemId} is what the entry links to: the combination
     * (BUDDY_/CLUB_COMBINATION), the glaze (BUDDY_/CLUB_GLAZE), the buddy (NEW_BUDDY), the asking account (BUDDY_REQUEST) or the
     * new member (CLUB_MEMBER).
     * The actor is named by display name only; never an amount, a jar size or an email address.
     */
    public record FeedEntryView(
            @Schema(description = "Unique per entry; stable while the entry exists") String id,
            FeedType type, Instant at, UUID actorId, String actorName, UUID itemId,
            @Schema(nullable = true, description = "The combination's title") String title,
            @Schema(nullable = true, description = "The glaze's manufacturer") String manufacturer,
            @Schema(nullable = true, description = "The glaze's name") String glazeName,
            @Schema(nullable = true, description = "The glaze's code") String glazeCode,
            @Schema(nullable = true, description = "The club of a club entry") UUID clubId,
            @Schema(nullable = true) String clubName) {}

    /**
     * A page of entries, newest first. {@code next} is the opaque cursor of the following page (null on the last page);
     * {@code seenAt} is the account's seen position: entries later than it are new.
     */
    public record FeedPageView(List<FeedEntryView> entries, @Schema(nullable = true) String next,
            @Schema(nullable = true) Instant seenAt) {}

    /**
     * The badge alone, for the app's light poll while it is open: entries later than the seen position (capped like
     * {@code UserView.feedUnread}) and the newest of them (null without any). Beside it the newest waiting buddy request
     * addressed to the account that is later than the seen position (FEED-2, the app's toast): the asking account's ID and
     * display name and the request's time, exactly what a {@code BUDDY_REQUEST} entry shows (all null without one), and how
     * many such requests wait.
     */
    public record FeedBadgeView(int unread, @Schema(nullable = true) Instant newest,
            @Schema(nullable = true, description = "The asking account of the newest unseen waiting request") UUID requesterId,
            @Schema(nullable = true, description = "Its display name") String requesterName,
            @Schema(nullable = true, description = "When it was sent") Instant requestedAt,
            @Schema(description = "How many unseen requests wait") int requests) {}

    /** The newest entry time the client showed; the seen position never moves back and never past the server's now. */
    public record FeedSeenInput(@NotNull Instant through) {}

    /** The account's two feed sources. */
    public record FeedSettingsInput(@NotNull Boolean buddies, @NotNull Boolean clubs) {}
}
