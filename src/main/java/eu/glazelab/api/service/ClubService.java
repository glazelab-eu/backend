package eu.glazelab.api.service;

import eu.glazelab.api.model.Text;
import eu.glazelab.api.dto.ClubDtos.NewClub;
import eu.glazelab.api.dto.ClubDtos.ClubSettings;
import eu.glazelab.api.dto.ClubDtos.InviteView;
import eu.glazelab.api.dto.ClubDtos.InvitePreviewView;
import eu.glazelab.api.dto.ClubDtos.ClubView;
import eu.glazelab.api.dto.ClubDtos.MemberView;
import eu.glazelab.api.model.AppUser;
import eu.glazelab.api.model.Club;
import eu.glazelab.api.model.Membership;
import eu.glazelab.api.model.Membership.Role;
import eu.glazelab.api.model.MembershipId;
import eu.glazelab.api.repository.ClubRepository;
import eu.glazelab.api.repository.MembershipRepository;
import eu.glazelab.api.repository.StockRepository;
import eu.glazelab.api.repository.WishlistRepository;
import eu.glazelab.api.security.CurrentUser;
import eu.glazelab.api.security.SecureTokens;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
@RequiredArgsConstructor
public class ClubService {
    /** The answer to every change that would leave a club without an admin: a demotion, a leave or a removal. */
    public static final String LAST_ADMIN = "A club must have at least one admin";
    public static final String ADMIN_REQUIRED = "Club admin role required";
    public static final String MEMBER_NOT_FOUND = "Member not found";
    /** The admin endpoints' answer to an unknown club and, alike, to a club the caller is not a member of. */
    public static final String CLUB_NOT_FOUND = "Club not found";
    private final ClubRepository clubs;
    private final MembershipRepository memberships;
    private final StockRepository stocks;
    private final WishlistRepository wishlist;
    private final CurrentUser current;

    @Transactional(readOnly = true)
    public List<ClubView> list(AppUser actor) {
        return memberships.findByUser_Id(actor.getId()).stream().map(m -> {
            Club c = m.getClub();
            return new ClubView(c.getId(), c.getName(), c.getOwner() == null ? null : c.getOwner().getId(), c.getHouseRules(),
                    m.getRole(), c.isInvitesEnabled(), c.getInviteHash() != null,
                    m.getRole() == Role.ADMIN ? inviteVersion(c.getInviteHash()) : null);
        }).sorted(Comparator.comparing(ClubView::name)).toList();
    }

    public UUID create(NewClub input, AppUser actor) {
        Club club = clubs.save(new Club(Text.clean(input.name(), Text.Kind.NAME), actor));
        Membership membership = new Membership(club, actor);
        membership.setRole(Role.ADMIN);
        memberships.save(membership);
        return club.getId();
    }

    public void update(UUID id, ClubSettings input, AppUser actor) {
        Club club = lockAdminClub(id, actor);
        club.setName(Text.clean(input.name(), Text.Kind.NAME));
        club.setHouseRules(Text.clean(input.houseRules(), Text.Kind.MULTI_LINE));
        club.setInvitesEnabled(input.invitesEnabled());
    }

    @Transactional(readOnly = true)
    public List<MemberView> members(UUID id, AppUser actor) {
        requireMember(id, actor);
        return memberships.membersOf(id).stream()
                .map(m -> new MemberView(m.getId(), m.getDisplayName(), m.getRole(), m.getAvatarId()))
                .sorted(Comparator.comparing(MemberView::displayName)).toList();
    }

    public void changeRole(UUID id, UUID userId, Role role, AppUser actor) {
        lockAdminClub(id, actor);
        // Joining needs a signed-in (verified) account; a pending member could only come from outside the API and never
        // becomes an admin: the purge removes it.
        Membership member = memberships.findById(new MembershipId(id, userId))
                .filter(m -> role != Role.ADMIN || m.getUser().isEmailVerified())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, MEMBER_NOT_FOUND));
        if (member.getRole() == Role.ADMIN && role == Role.MEMBER) requireAnotherAdmin(id);
        member.setRole(role);
    }

    /**
     * A member leaves ({@code userId} is the actor) or a club admin removes another member; the last admin can do
     * neither (409, the role change's refusal), so a club always keeps an admin and is never left empty. A non-member
     * and an unknown club alike get 403, as the member list answers, from a membership read before any lock (so an
     * outsider neither waits for nor holds the club). Lock order: actor account, then the club; the actor's role,
     * the target's membership and the admin count are read fresh under the club lock. The target's account is never
     * locked: its deletion locks account, then club, and would deadlock with club, then account.
     * What stays with the club: its stock and wishlist (club-owned; the leaver's name leaves {@code added_by}), its CLUB photos
     * (their uploader keeps them) and its invite link. The leaver keeps personal stock, combinations and photos.
     */
    public void removeMember(UUID id, UUID userId, AppUser actor) {
        // An early refusal only; repeated under the club lock.
        memberships.roleOf(id, actor.getId()).orElseThrow(ClubService::notAMember);
        current.lockUser(actor.getId());
        Club club = clubs.lockById(id).orElseThrow(ClubService::notAMember);
        Role actorRole = memberships.roleOf(id, actor.getId()).orElseThrow(ClubService::notAMember);
        if (!userId.equals(actor.getId()) && actorRole != Role.ADMIN)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ADMIN_REQUIRED);
        Role role = memberships.roleOf(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, MEMBER_NOT_FOUND));
        if (role == Role.ADMIN) requireAnotherAdmin(id);
        memberships.deleteMembership(id, userId);
        stocks.forgetAdder(id, userId);
        wishlist.forgetAdder(id, userId);
        club.forgetOwner(userId);
    }

    private void requireAnotherAdmin(UUID id) {
        if (memberships.countByClub_IdAndRole(id, Role.ADMIN) <= 1)
            throw new ResponseStatusException(HttpStatus.CONFLICT, LAST_ADMIN);
    }

    /**
     * Replaces the club's invite link and turns invite links on (a new club starts with them off, and a link that
     * could not be used would mislead); the settings switch stays the way to turn them off. Under the club lock, so a
     * settings save queued behind it applies its switch after this (the last write decides).
     */
    public InviteView regenerate(UUID id, AppUser actor) {
        Club club = lockAdminClub(id, actor);
        String token = SecureTokens.generate();
        String hash = SecureTokens.hash(token);
        club.setInviteHash(hash);
        club.setInvitesEnabled(true);
        return new InviteView(token, inviteVersion(hash));
    }

    /**
     * Names the active invite link for clients that keep the raw token in memory: a hash of the stored hash, which is
     * neither the token nor usable to look the club up, and null without a link. It changes with every replacement,
     * so a client can tell its remembered link from one another admin generated.
     */
    static String inviteVersion(String inviteHash) {
        return inviteHash == null ? null : SecureTokens.hash(inviteHash).substring(0, 16);
    }

    public InvitePreviewView preview(String token) {
        Club club = invitedClub(token);
        return new InvitePreviewView(club.getName(), club.getHouseRules());
    }

    public UUID join(String token, AppUser actor) {
        Club club = invitedClub(token);
        MembershipId id = new MembershipId(club.getId(), actor.getId());
        if (!memberships.existsById(id)) memberships.save(new Membership(club, actor));
        return club.getId();
    }

    private Club invitedClub(String token) {
        return clubs.findByInviteHash(SecureTokens.hash(token)).filter(Club::isInvitesEnabled)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "This invite link is inactive or invalid"));
    }

    /**
     * Locks a club for an admin write. A club the caller cannot see (not a member, or no such club) answers 404
     * exactly like an unknown club, decided by a scalar membership read before any lock, so neither the answer nor
     * its timing (a wait for a held club lock) confirms that a club ID exists; a plain member, who sees the club,
     * gets 403. That read is only an early refusal: the role is read again under the club lock.
     */
    private Club lockAdminClub(UUID id, AppUser actor) {
        requireAdmin(memberships.roleOf(id, actor.getId()));
        Club club = clubs.lockById(id).orElseThrow(ClubService::clubNotFound);
        requireAdmin(memberships.roleOf(id, actor.getId()));
        return club;
    }

    private static void requireAdmin(Optional<Role> role) {
        if (role.isEmpty()) throw clubNotFound();
        if (role.get() != Role.ADMIN) throw new ResponseStatusException(HttpStatus.FORBIDDEN, ADMIN_REQUIRED);
    }

    private static ResponseStatusException clubNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, CLUB_NOT_FOUND);
    }

    /** Any member may read the member list; a fresh membership read, 403 for a non-member whether or not the club exists. */
    private void requireMember(UUID id, AppUser actor) {
        if (!memberships.existsById(new MembershipId(id, actor.getId()))) throw notAMember();
    }

    private static ResponseStatusException notAMember() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, CurrentUser.CLUB_MEMBERSHIP_REQUIRED);
    }
}
