package eu.glazelab.api.security;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import eu.glazelab.api.model.AppUser;
import eu.glazelab.api.model.Membership;
import eu.glazelab.api.model.MembershipId;
import eu.glazelab.api.repository.AppUserRepository;
import eu.glazelab.api.repository.MembershipRepository;
import eu.glazelab.api.repository.MembershipRepository.ClubRole;
import jakarta.servlet.http.HttpServletRequest;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
@RequiredArgsConstructor
public class CurrentUser {
    public static final String SIGN_IN_REQUIRED = "Please sign in.";
    public static final String CLUB_MEMBERSHIP_REQUIRED = "Club membership required";
    public static final String SITE_ADMIN_REQUIRED = "Site admin role required";
    static final String VALIDATED_USER = CurrentUser.class.getName() + ".validatedUser";
    private final AppUserRepository users;
    private final MembershipRepository memberships;

    public AppUser user(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof AccountPrincipal principal))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, SIGN_IN_REQUIRED);
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            AppUser validated = validatedUser(attributes.getRequest(), principal.getUserId());
            if (validated != null) return validated;
        }
        // Email can be re-used after deletion. An in-flight request must keep its original identity.
        return users.findById(principal.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, SIGN_IN_REQUIRED));
    }
    /** Only the row checked by SessionVersionFilter, for this identity and this request. Never session-cached. */
    static AppUser validatedUser(HttpServletRequest request, UUID userId) {
        Object value = request.getAttribute(VALIDATED_USER);
        return value instanceof AppUser user && user.getId().equals(userId) ? user : null;
    }
    /** Call within the mutation transaction so account deletion cannot overtake an audit write. */
    public AppUser lockUser(UUID userId) {
        return users.lockById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, SIGN_IN_REQUIRED));
    }
    /**
     * Locks a site admin's account and re-reads the flag under the lock: 403 when SQL took it away after the request's
     * early {@link #siteAdmin} check, 401 when the account is gone.
     */
    public AppUser lockSiteAdmin(UUID userId) {
        AppUser user = lockUser(userId);
        if (!user.isSiteAdmin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, SITE_ADMIN_REQUIRED);
        return user;
    }
    /** The signed-in user if they are a site admin: 401 for guests, 403 for everyone else. */
    public AppUser siteAdmin(Authentication auth) {
        AppUser user = user(auth);
        if (!user.isSiteAdmin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, SITE_ADMIN_REQUIRED);
        return user;
    }
    public AppUser optionalUser(Authentication auth) {
        return auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())
                ? null : user(auth);
    }
    /**
     * Who is looking: the signed-in user's ID, clubs, admin clubs and site-admin flag (one membership query),
     * or {@code null} and no clubs for guests. Read-only listings only: a write decides club roles under the club lock.
     */
    public Viewer viewer(Authentication auth) {
        AppUser user = optionalUser(auth);
        if (user == null) return new Viewer(null, Set.of(), Set.of(), false);
        var roles = memberships.rolesOf(user.getId());
        return new Viewer(user.getId(), roles.stream().map(ClubRole::getClubId).collect(Collectors.toUnmodifiableSet()),
                roles.stream().filter(r -> r.getRole() == Membership.Role.ADMIN).map(ClubRole::getClubId)
                        .collect(Collectors.toUnmodifiableSet()), user.isSiteAdmin());
    }
    public record Viewer(UUID userId, Set<UUID> clubIds, Set<UUID> adminClubIds, boolean siteAdmin) {}
    public boolean inClub(UUID userId, UUID clubId) {
        return userId != null && clubId != null
                && memberships.existsById(new MembershipId(clubId, userId));
    }
    /** Clubs the account belongs to; empty for guests. */
    public Set<UUID> clubIds(UUID userId) {
        if (userId == null) return Set.of();
        return Set.copyOf(memberships.clubIdsOf(userId));
    }
    public void requireClub(UUID userId, UUID clubId) {
        if (!inClub(userId, clubId))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, CLUB_MEMBERSHIP_REQUIRED);
    }
}
