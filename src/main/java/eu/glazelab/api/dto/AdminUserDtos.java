package eu.glazelab.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The site admins' account list (backend guide "Admin user list"). Deliberately minimal: the email address is shown to site
 * admins only (they are the operator, who has database access anyway), never a hash, sign-in or session data or anything about
 * buddies, clubs or stock.
 */
public final class AdminUserDtos {
    private AdminUserDtos() {}

    /** {@code status} is {@code ACTIVE} (verified) or {@code PENDING} (registered, email not yet confirmed). */
    public enum AccountStatus { ACTIVE, PENDING }

    /** {@code createdAt} is the registration time, null for accounts that existed before it was recorded (V33). */
    public record AdminUserView(UUID id, String displayName, String email, AccountStatus status, boolean siteAdmin,
            @Schema(nullable = true) Instant createdAt) {}
    public record AdminUserPageView(List<AdminUserView> entries, boolean hasMore) {}
}
