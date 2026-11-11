package eu.glazelab.api.service;

import eu.glazelab.api.dto.BuddyDtos.BuddiesView;
import eu.glazelab.api.dto.BuddyDtos.BuddyGlazeView;
import eu.glazelab.api.dto.BuddyDtos.BuddyLinkPreviewView;
import eu.glazelab.api.dto.BuddyDtos.BuddyLinkView;
import eu.glazelab.api.dto.BuddyDtos.BuddyNameView;
import eu.glazelab.api.dto.BuddyDtos.BuddyRequestView;
import eu.glazelab.api.dto.BuddyDtos.BuddyView;
import eu.glazelab.api.dto.BuddyDtos.Relation;
import eu.glazelab.api.exception.ApiErrorHandler;
import eu.glazelab.api.model.AppUser;
import eu.glazelab.api.model.Buddy;
import eu.glazelab.api.model.BuddyId;
import eu.glazelab.api.model.BuddyLink;
import eu.glazelab.api.repository.AppUserRepository;
import eu.glazelab.api.repository.BuddyLinkRepository;
import eu.glazelab.api.repository.BuddyRepository;
import eu.glazelab.api.repository.ClubRepository;
import eu.glazelab.api.repository.UserAvatarRepository;
import eu.glazelab.api.security.CurrentUser;
import eu.glazelab.api.security.SecureTokens;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Glaze buddies (backend guide "Buddies"): two accounts that asked and accepted see which glazes the other has on
 * their personal shelf, by display name, never amounts. Every write locks both account rows in {@link BuddyId#ORDER}
 * (then, for a request to a club member, that club) before it reads the pair, so requests, accepts and removals of
 * one pair queue on the same two rows and an account deletion (its own row first) waits for them or they for it.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class BuddyService {
    /** Unknown and replaced links answer alike, so a link never confirms that it once existed. */
    public static final String INVALID_LINK = "This buddy link is invalid or was replaced";
    public static final String NOT_YOURSELF = "You cannot be your own buddy";
    /** Accepting without a request from that account (none, or the caller's own). */
    public static final String REQUEST_NOT_FOUND = "Buddy request not found";

    private static final Comparator<BuddyView> BY_NAME = Comparator.comparing(BuddyView::displayName)
            .thenComparing(BuddyView::id, BuddyId.ORDER);

    private final AppUserRepository users;
    private final BuddyRepository buddies;
    private final BuddyLinkRepository links;
    private final UserAvatarRepository avatars;
    private final ClubRepository clubs;
    private final CurrentUser current;
    private final EntityManager entities;

    private static ResponseStatusException invalidLink() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, INVALID_LINK);
    }

    private static ResponseStatusException memberNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, ClubService.MEMBER_NOT_FOUND);
    }

    private static ResponseStatusException notAMember() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, CurrentUser.CLUB_MEMBERSHIP_REQUIRED);
    }

    private static ResponseStatusException requestNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, REQUEST_NOT_FOUND);
    }

    /** The caller's buddies, received and sent requests (by display name), and whether they have a link: two statements. */
    @Transactional(readOnly = true)
    public BuddiesView list(AppUser actor) {
        var accepted = new ArrayList<BuddyView>();
        var incoming = new ArrayList<BuddyView>();
        var outgoing = new ArrayList<BuddyView>();
        for (var pair : buddies.of(actor.getId())) {
            if (pair.getStatus() == Buddy.Status.ACCEPTED)
                accepted.add(new BuddyView(pair.getId(), pair.getDisplayName(), pair.getAcceptedAt(), pair.getAvatarId()));
            else (pair.getRequestedBy().equals(actor.getId()) ? outgoing : incoming)
                    .add(new BuddyView(pair.getId(), pair.getDisplayName(), pair.getCreatedAt(), pair.getAvatarId()));
        }
        accepted.sort(BY_NAME);
        incoming.sort(BY_NAME);
        outgoing.sort(BY_NAME);
        return new BuddiesView(accepted, incoming, outgoing, links.existsById(actor.getId()));
    }

    /** A new buddy link for the caller; it replaces the previous one at once, and only its hash is stored. */
    public BuddyLinkView replaceLink(AppUser actor) {
        current.lockUser(actor.getId());
        String token = SecureTokens.generate();
        String hash = SecureTokens.hash(token);
        Instant now = Instant.now();
        links.findById(actor.getId()).ifPresentOrElse(link -> link.replace(hash, now),
                () -> entities.persist(new BuddyLink(actor.getId(), hash, now)));
        return new BuddyLinkView(token);
    }

    /** The owner's display name and picture and how the caller stands with them; nothing is written and nothing locked. */
    @Transactional(readOnly = true)
    public BuddyLinkPreviewView preview(String token, AppUser actor) {
        UUID owner = links.ownerOf(SecureTokens.hash(token)).orElseThrow(BuddyService::invalidLink);
        AppUser account = users.findById(owner).orElseThrow(BuddyService::invalidLink);
        return new BuddyLinkPreviewView(account.getDisplayName(), relation(actor.getId(), owner), avatars.idOf(owner).orElse(null));
    }

    /**
     * Asks the owner of a link. The link is looked up as a scalar, then both accounts are locked and the link is read
     * again: replacing it locks its owner's row, so a replaced link is refused even when this request waited for it.
     */
    public BuddyRequestView requestByLink(String token, AppUser actor) {
        String hash = SecureTokens.hash(token);
        UUID owner = links.ownerOf(hash).orElseThrow(BuddyService::invalidLink);
        if (owner.equals(actor.getId())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, NOT_YOURSELF);
        lockPair(actor.getId(), owner).orElseThrow(BuddyService::invalidLink);
        if (links.ownerOf(hash).filter(owner::equals).isEmpty()) throw invalidLink();
        return new BuddyRequestView(request(actor.getId(), owner));
    }

    /**
     * Asks another member of a club the caller is in. A non-member gets 403 for any club before anything about
     * {@code userId} is read (as the member list answers); a target who is not a verified member of the club 404.
     * Both memberships are read under the club lock, which comes after the two account locks.
     */
    public BuddyRequestView requestMember(UUID clubId, UUID userId, AppUser actor) {
        if (userId.equals(actor.getId())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, NOT_YOURSELF);
        // An early refusal only; repeated under the club lock.
        if (!current.inClub(actor.getId(), clubId)) throw notAMember();
        AppUser target = lockPair(actor.getId(), userId).orElseThrow(BuddyService::memberNotFound);
        clubs.lockById(clubId).orElseThrow(BuddyService::notAMember);
        if (!current.inClub(actor.getId(), clubId)) throw notAMember();
        if (!target.isEmailVerified() || !current.inClub(userId, clubId)) throw memberNotFound();
        return new BuddyRequestView(request(actor.getId(), userId));
    }

    /** Accepts the request {@code userId} sent: idempotent once accepted; without a request from them (none, or the caller's own) 404. */
    public void accept(UUID userId, AppUser actor) {
        lockPair(actor.getId(), userId);
        Buddy pair = pair(actor.getId(), userId).orElseThrow(BuddyService::requestNotFound);
        if (pair.accepted()) return;
        if (!pair.pendingFor(actor.getId())) throw requestNotFound();
        pair.accept(Instant.now());
    }

    /** Declines a request {@code userId} sent (silently: nobody is told). */
    public void decline(UUID userId, AppUser actor) {
        end(actor.getId(), userId, pair -> pair.pendingFor(actor.getId()));
    }

    /** Takes back the caller's own request to {@code userId}. */
    public void cancel(UUID userId, AppUser actor) {
        end(actor.getId(), userId, pair -> pair.pendingFrom(actor.getId()));
    }

    /** Ends an accepted pair; either side may, and either may ask again afterwards. */
    public void remove(UUID userId, AppUser actor) {
        end(actor.getId(), userId, Buddy::accepted);
    }

    /**
     * For each glaze that accepted buddies show the caller, those buddies by ID and display name: one statement
     * ({@code BuddyRepository.availableTo}: {@code AVAILABLE_STOCK}, personal stock above 0 g that its owner does not keep private).
     */
    @Transactional(readOnly = true)
    public List<BuddyGlazeView> availability(AppUser actor) {
        var byGlaze = new LinkedHashMap<UUID, List<BuddyNameView>>();
        for (var row : buddies.availableTo(actor.getId()))
            byGlaze.computeIfAbsent(row.glazeId(), id -> new ArrayList<>()).add(new BuddyNameView(row.buddyId(), row.displayName()));
        return byGlaze.entrySet().stream().map(entry -> new BuddyGlazeView(entry.getKey(), List.copyOf(entry.getValue()))).toList();
    }

    /**
     * Deletes the pair when {@code matches} (the state the caller saw). No pair at all is already the wanted outcome
     * (another device or the other account ended it); a pair in another state is 409: it changed meanwhile.
     */
    private void end(UUID actorId, UUID userId, Predicate<Buddy> matches) {
        lockPair(actorId, userId);
        Optional<Buddy> pair = pair(actorId, userId);
        if (pair.isEmpty()) return;
        if (!matches.test(pair.get())) throw new ResponseStatusException(HttpStatus.CONFLICT, ApiErrorHandler.CHANGED_MEANWHILE);
        buddies.delete(pair.get());
    }

    /**
     * Asks {@code otherId} for {@code actorId}, both rows locked: the caller's pending request and an accepted pair
     * stay as they are (idempotent), a pending request of the other account is accepted (both asked), otherwise a
     * new request is stored.
     */
    private Relation request(UUID actorId, UUID otherId) {
        BuddyId id = BuddyId.of(actorId, otherId);
        Optional<Buddy> found = buddies.findById(id);
        if (found.isEmpty()) {
            entities.persist(new Buddy(id, actorId, Instant.now()));
            return Relation.OUTGOING;
        }
        Buddy pair = found.get();
        if (pair.pendingFor(actorId)) pair.accept(Instant.now());
        return pair.accepted() ? Relation.BUDDIES : Relation.OUTGOING;
    }

    private Relation relation(UUID actorId, UUID otherId) {
        if (actorId.equals(otherId)) return Relation.SELF;
        return pair(actorId, otherId).map(pair -> pair.accepted() ? Relation.BUDDIES
                : pair.pendingFrom(actorId) ? Relation.OUTGOING : Relation.INCOMING).orElse(Relation.NONE);
    }

    /** The pair of two accounts; none for the caller and themself. */
    private Optional<Buddy> pair(UUID actorId, UUID otherId) {
        return actorId.equals(otherId) ? Optional.empty() : buddies.findById(BuddyId.of(actorId, otherId));
    }

    /**
     * Locks both account rows in {@link BuddyId#ORDER} (the buddy lock rule) and answers the other account, empty when
     * it does not exist (deleted, perhaps while this request waited) or is the caller. The caller's row is required (401).
     */
    private Optional<AppUser> lockPair(UUID actorId, UUID otherId) {
        if (actorId.equals(otherId)) {
            current.lockUser(actorId);
            return Optional.empty();
        }
        if (BuddyId.ORDER.compare(actorId, otherId) < 0) {
            current.lockUser(actorId);
            return users.lockById(otherId);
        }
        Optional<AppUser> other = users.lockById(otherId);
        current.lockUser(actorId);
        return other;
    }
}
