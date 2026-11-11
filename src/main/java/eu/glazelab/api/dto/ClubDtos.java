package eu.glazelab.api.dto;

import eu.glazelab.api.model.Text.Plain;
import static eu.glazelab.api.model.Text.Kind.NAME;
import static eu.glazelab.api.model.Text.Kind.MULTI_LINE;
import static eu.glazelab.api.model.Text.Kind.LINE;

import eu.glazelab.api.model.Membership.Role;
import eu.glazelab.api.model.WishlistEntry;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ClubDtos {
    private ClubDtos() {}
    public record NewClub(@NotBlank @Plain(NAME) @Size(max = 120) String name) {}
    public record ClubSettings(@NotBlank @Plain(NAME) @Size(max = 120) String name,
            @NotNull @Plain(MULTI_LINE) @Size(max = 10000) String houseRules, @NotNull Boolean invitesEnabled) {}
    public record RoleInput(@NotNull Role role) {}
    public record InviteInput(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String token) {}
    /** The raw link token (shown once) and the {@code inviteVersion} the club reports to its admins while this link is the active one. */
    public record InviteView(String token,
            @Schema(description = "The inviteVersion that ClubView reports to the club's admins while this link is the active one")
            String version) {}
    public record InvitePreviewView(String name, String houseRules) {}
    public record ClubView(UUID id, String name, @Schema(nullable = true) UUID ownerId, String houseRules, Role role,
            boolean invitesEnabled, boolean hasInviteLink,
            @Schema(nullable = true, description = "Names the active invite link without revealing it: changes whenever the link is replaced; club admins only (null for plain members) and null without a link")
            String inviteVersion) {}
    public record MemberView(UUID id, String displayName, Role role,
            @Schema(nullable = true, description = "The member's profile picture (GET /api/avatars/{avatarId}), null without one")
            UUID avatarId) {}

    /**
     * A new wishlist entry: a catalogue glaze ({@code glazeId}, no name or link) or an item ({@code name} and {@code link});
     * {@code note} is optional for both.
     */
    public record NewWishlistEntry(@NotNull WishlistEntry.Type type, @Schema(nullable = true) UUID glazeId,
            @Schema(nullable = true) @Plain(LINE) @Size(max = WishlistEntry.MAX_NAME) String name,
            @Schema(nullable = true, description = "An http or https link (items only); stored, never fetched by the server")
            @Plain(LINE) String link,
            @Schema(nullable = true) @Plain(LINE) @Size(max = WishlistEntry.MAX_NOTE) String note) {}
    public record AcquiredInput(@NotNull Boolean acquired) {}
    public record WishlistEntryView(UUID id, WishlistEntry.Type type,
            @Schema(nullable = true, description = "The catalogue glaze of a GLAZE entry") UUID glazeId,
            @Schema(nullable = true, description = "The glaze's manufacturer name (GLAZE entries)") String manufacturer,
            @Schema(nullable = true, description = "The glaze's code (GLAZE entries)") String code,
            @Schema(description = "The glaze's name or the item's name") String name,
            @Schema(nullable = true, description = "The item's link (ITEM entries)") String link,
            @Schema(nullable = true) String note,
            @Schema(nullable = true, description = "The member who added it; null once they left the club or deleted their account") UUID addedById,
            @Schema(nullable = true, description = "That member's display name, null with addedById") String addedBy,
            Instant addedAt,
            @Schema(nullable = true, description = "When it was marked acquired; null while it is still wanted") Instant acquiredAt,
            @Schema(description = "The caller may remove it and mark it acquired or wanted (a club admin or its adder)") boolean canManage) {}
    /** A club's wishlist and how many entries may be open (not acquired) at once. */
    public record WishlistView(List<WishlistEntryView> entries, int openLimit) {}
}
