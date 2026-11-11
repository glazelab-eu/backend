package eu.glazelab.api.dto;

import eu.glazelab.api.model.AppUser;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** Account and session responses. */
public final class AuthDtos {
    private AuthDtos() {}

    /**
     * siteAdmin: may remove glazes, delete any photo, read the deletion log and the photo report queue; granted only with SQL. acceptedTermsVersion: null for accounts
     * from before V9; writes need AccountService.TERMS_VERSION (see TermsFilter). avatarId: the account's own profile
     * picture ({@code GET /api/avatars/{avatarId}}), null without one. diagnosticsOptIn: whether the account sends error
     * reports ({@code PUT /api/account/diagnostics}); off unless the owner turned it on. feedUnread: the activity feed's
     * badge (one more statement per answer, backend guide "Activity feed").
     */
    public record UserView(UUID id, String email, String displayName, boolean siteAdmin,
            @Schema(nullable = true) String acceptedTermsVersion,
            @Schema(nullable = true, description = "The account's profile picture (GET /api/avatars/{avatarId}); a new ID for every new picture, null without one")
            UUID avatarId,
            @Schema(description = "Whether the account sends error reports (POST /api/reports); off by default")
            boolean diagnosticsOptIn,
            @Schema(description = "New activity-feed entries since the account last looked (GET /api/feed), at most FeedService.BADGE_CAP")
            int feedUnread,
            @Schema(description = "Whether the activity feed shows what buddies add (PUT /api/account/feed); on by default")
            boolean feedBuddies,
            @Schema(description = "Whether the activity feed shows what is added in the account's clubs; on by default")
            boolean feedClubs) {
        public static UserView of(AppUser user, UUID avatarId, int feedUnread) {
            return new UserView(user.getId(), user.getEmail(), user.getDisplayName(), user.isSiteAdmin(),
                    user.getAcceptedTermsVersion(), avatarId, user.isDiagnosticsOptIn(), feedUnread, user.isFeedBuddies(),
                    user.isFeedClubs());
        }
    }
    /**
     * The same answer for a new address and one that already has an account (verificationRequired is always true), so
     * registration never tells whether an address is in use. setupToken is set only without SMTP (local development) and
     * only for the account this request created: it answers the activation challenge of
     * {@code /api/auth/verification/confirm} once, like the emailed link.
     */
    public record RegistrationView(boolean verificationRequired, @Schema(nullable = true) String setupToken) {}
    /** sourceUrl: where this server's source code is published (the AGPL source offer), null while none is configured. */
    public record AuthOptionsView(boolean emailEnabled, String termsVersion, @Schema(nullable = true) String sourceUrl) {}
    /** The operator's contact address, answered on request only so it never rides on a page load. */
    public record OperatorContactView(String email) {}
    /** The CSRF token write requests must send back. */
    public record CsrfView(String token) {}
}
