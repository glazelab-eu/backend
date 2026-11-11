package eu.glazelab.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Buddy requests, links and availability; the rules are in service.BuddyService. */
public final class BuddyDtos {
    private BuddyDtos() {}

    /** How the caller stands with another account. */
    public enum Relation {
        /** Nothing between them. */
        NONE,
        /** The caller asked; the other account has not accepted yet. */
        OUTGOING,
        /** The other account asked the caller. */
        INCOMING,
        /** Accepted: each sees which glazes the other has on their personal shelf. */
        BUDDIES,
        /** The caller's own link. */
        SELF
    }

    /** A buddy link token as the link carries it (43 characters, like an invite link). */
    public record BuddyLinkInput(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String token) {}
    /** Ask a member of a club the caller is in: both must be members of {@code clubId}. */
    public record BuddyMemberInput(@NotNull UUID clubId, @NotNull UUID userId) {}

    /** The new link's token, answered once: the server keeps only its hash. */
    public record BuddyLinkView(String token) {}
    /**
     * What a link shows before asking: the owner's display name and profile picture (shown to every signed-in account
     * anyway), never their ID.
     */
    public record BuddyLinkPreviewView(String displayName, Relation relation,
            @Schema(nullable = true, description = "The owner's profile picture (GET /api/avatars/{avatarId}), null without one")
            UUID avatarId) {}
    /** The relation after a request: {@code OUTGOING}, or {@code BUDDIES} when the other account had asked too. */
    public record BuddyRequestView(Relation relation) {}
    /**
     * Another account in the caller's lists: ID, display name and profile picture only; {@code since} is when the pair
     * was accepted (buddies) or asked (requests).
     */
    public record BuddyView(UUID id, String displayName, Instant since,
            @Schema(nullable = true, description = "The account's profile picture (GET /api/avatars/{avatarId}), null without one")
            UUID avatarId) {}
    /** The caller's buddies, the requests they received and sent, and whether they have a buddy link. */
    public record BuddiesView(List<BuddyView> buddies, List<BuddyView> incoming, List<BuddyView> outgoing, boolean hasLink) {}
    public record BuddyNameView(UUID id, String displayName) {}
    /** A glaze that accepted buddies have on their personal shelf (never an amount), with those buddies by name. */
    public record BuddyGlazeView(UUID glazeId, List<BuddyNameView> buddies) {}
}
