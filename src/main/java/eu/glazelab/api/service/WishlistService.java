package eu.glazelab.api.service;

import eu.glazelab.api.dto.ClubDtos.NewWishlistEntry;
import eu.glazelab.api.dto.ClubDtos.WishlistEntryView;
import eu.glazelab.api.dto.ClubDtos.WishlistView;
import eu.glazelab.api.model.AppUser;
import eu.glazelab.api.model.Club;
import eu.glazelab.api.model.Glaze;
import eu.glazelab.api.model.Membership.Role;
import eu.glazelab.api.model.Text;
import eu.glazelab.api.model.WishlistEntry;
import eu.glazelab.api.model.WishlistLinks;
import eu.glazelab.api.repository.AppUserRepository;
import eu.glazelab.api.repository.ClubRepository;
import eu.glazelab.api.repository.GlazeRepository;
import eu.glazelab.api.repository.MembershipRepository;
import eu.glazelab.api.repository.WishlistRepository;
import eu.glazelab.api.security.CurrentUser;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * A club's wishlist: glazes from the catalogue and other items the members would like the club to get. Every member
 * reads it and adds entries; an entry's adder or a club admin removes it and marks it acquired or wanted again
 * ({@link WishlistEntry#manageableBy}). A non-member and an unknown club get the same 403 as the member list, decided by
 * a scalar membership read before any lock. Writes lock the club, then read the role (and the entry) under that lock.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class WishlistService {
    /** How many entries a club's wishlist may hold that are still wanted. */
    public static final int OPEN_LIMIT = 100;
    public static final String FULL = "The wishlist is full: mark entries as acquired or remove some first.";
    public static final String ALREADY_WISHED = "This glaze is already on the wishlist.";
    public static final String ENTRY_NOT_FOUND = "Wishlist entry not found";
    public static final String MANAGE_RIGHTS_REQUIRED = "Only a club admin or the member who added it can change this entry";
    public static final String GLAZE_REQUIRED = "Choose a glaze for the wishlist.";
    public static final String GLAZE_FIELDS = "A glaze entry takes no item name or link.";
    public static final String ITEM_FIELDS = "An item entry takes no glaze.";
    public static final String NAME_REQUIRED = "Give the item a name.";

    private final WishlistRepository wishlist;
    private final ClubRepository clubs;
    private final MembershipRepository memberships;
    private final GlazeRepository glazes;
    private final AppUserRepository users;

    @Transactional(readOnly = true)
    public WishlistView list(UUID clubId, AppUser actor) {
        boolean admin = requireMember(memberships.roleOf(clubId, actor.getId())) == Role.ADMIN;
        List<WishlistEntryView> entries = wishlist.rowsOf(clubId).stream().map(row -> view(row, actor.getId(), admin)).toList();
        return new WishlistView(entries, OPEN_LIMIT);
    }

    /**
     * Adds an entry. Its fields are checked before any lookup; under the club lock the membership, the open limit and, for
     * a glaze, that it is not already wanted (409).
     */
    public WishlistEntryView add(UUID clubId, NewWishlistEntry input, AppUser actor) {
        String note = Text.optional(input.note(), Text.Kind.LINE);
        String name = null;
        String link = null;
        if (input.type() == WishlistEntry.Type.GLAZE) {
            if (input.name() != null || input.link() != null) throw badRequest(GLAZE_FIELDS);
            if (input.glazeId() == null) throw badRequest(GLAZE_REQUIRED);
        } else {
            if (input.glazeId() != null) throw badRequest(ITEM_FIELDS);
            name = Text.optional(input.name(), Text.Kind.LINE);
            if (name == null) throw badRequest(NAME_REQUIRED);
            link = WishlistLinks.clean(input.link());
        }
        Role role = lockClub(clubId, actor);
        if (wishlist.countByClub_IdAndAcquiredAtIsNull(clubId) >= OPEN_LIMIT) throw conflict(FULL);
        Club club = clubs.getReferenceById(clubId);
        AppUser adder = users.getReferenceById(actor.getId());
        WishlistEntry entry;
        if (input.type() == WishlistEntry.Type.GLAZE) {
            Glaze glaze = glazes.findById(input.glazeId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, GlazeService.UNKNOWN_GLAZE));
            if (wishlist.existsByClub_IdAndGlaze_IdAndAcquiredAtIsNull(clubId, glaze.getId())) throw conflict(ALREADY_WISHED);
            entry = WishlistEntry.glaze(club, glaze, note, adder);
        } else {
            entry = WishlistEntry.item(club, name, link, note, adder);
        }
        wishlist.saveAndFlush(entry);
        return viewOf(entry.getId(), actor.getId(), role == Role.ADMIN);
    }

    /** Removes an entry: its adder or a club admin (403 for another member, 404 for an entry not on this club's list). */
    public void remove(UUID clubId, UUID entryId, AppUser actor) {
        managed(clubId, entryId, actor);
        if (wishlist.deleteEntry(entryId) == 0) throw notFound();
    }

    /**
     * Marks an entry acquired or wanted again, by the same rule as the removal; the state it already has changes nothing.
     * Wanted again counts towards the open limit and is refused while the same glaze is wanted in another entry.
     */
    public WishlistEntryView setAcquired(UUID clubId, UUID entryId, boolean acquired, AppUser actor) {
        var managed = managed(clubId, entryId, actor);
        WishlistEntry entry = managed.entry();
        if (acquired && entry.getAcquiredAt() == null) {
            entry.setAcquiredAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
        } else if (!acquired && entry.getAcquiredAt() != null) {
            if (wishlist.countByClub_IdAndAcquiredAtIsNull(clubId) >= OPEN_LIMIT) throw conflict(FULL);
            if (entry.getGlaze() != null && wishlist.existsByClub_IdAndGlaze_IdAndAcquiredAtIsNull(clubId, entry.getGlaze().getId()))
                throw conflict(ALREADY_WISHED);
            entry.setAcquiredAt(null);
        }
        wishlist.flush();
        return viewOf(entryId, actor.getId(), managed.role() == Role.ADMIN);
    }

    private record Managed(WishlistEntry entry, Role role) {}

    /** The locked club's entry that the actor may change: membership, role and adder read under the club lock. */
    private Managed managed(UUID clubId, UUID entryId, AppUser actor) {
        Role role = lockClub(clubId, actor);
        // The first load of the entry in this transaction, after the club lock: its adder is current.
        WishlistEntry entry = wishlist.findByIdAndClub_Id(entryId, clubId).orElseThrow(WishlistService::notFound);
        UUID adder = entry.getAddedBy() == null ? null : entry.getAddedBy().getId();
        if (!WishlistEntry.manageableBy(adder, actor.getId(), role == Role.ADMIN))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, MANAGE_RIGHTS_REQUIRED);
        return new Managed(entry, role);
    }

    /**
     * Locks the club for a write by one of its members and answers the actor's role read under the lock. A non-member and
     * an unknown club are refused before the lock (an early refusal only, repeated under it), so an outsider neither waits
     * for nor holds the club.
     */
    private Role lockClub(UUID clubId, AppUser actor) {
        requireMember(memberships.roleOf(clubId, actor.getId()));
        clubs.lockById(clubId).orElseThrow(WishlistService::notAMember);
        return requireMember(memberships.roleOf(clubId, actor.getId()));
    }

    private static Role requireMember(Optional<Role> role) {
        return role.orElseThrow(WishlistService::notAMember);
    }

    private WishlistEntryView viewOf(UUID entryId, UUID userId, boolean admin) {
        return view(wishlist.rowOf(entryId).orElseThrow(WishlistService::notFound), userId, admin);
    }

    private static WishlistEntryView view(WishlistRepository.Row row, UUID userId, boolean admin) {
        boolean glaze = row.getType() == WishlistEntry.Type.GLAZE;
        return new WishlistEntryView(row.getId(), row.getType(), row.getGlazeId(), row.getManufacturer(), row.getCode(),
                glaze ? row.getGlazeName() : row.getItemName(), row.getLink(), row.getNote(), row.getAddedById(), row.getAddedByName(),
                row.getCreatedAt(), row.getAcquiredAt(), WishlistEntry.manageableBy(row.getAddedById(), userId, admin));
    }

    private static ResponseStatusException notAMember() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, CurrentUser.CLUB_MEMBERSHIP_REQUIRED);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, ENTRY_NOT_FOUND);
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
