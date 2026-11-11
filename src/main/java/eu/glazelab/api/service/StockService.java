package eu.glazelab.api.service;

import eu.glazelab.api.model.AppUser;
import eu.glazelab.api.model.Club;
import eu.glazelab.api.model.Glaze;
import eu.glazelab.api.model.Membership;
import eu.glazelab.api.model.Stock;
import eu.glazelab.api.repository.AppUserRepository;
import eu.glazelab.api.repository.ClubRepository;
import eu.glazelab.api.repository.MembershipRepository;
import eu.glazelab.api.repository.StockRepository;
import eu.glazelab.api.security.CurrentUser;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * The one place that authorizes and writes a stock entry, shared by {@code POST /api/stock}, {@code POST
 * /api/stock/add}, the stock part of a new glaze and {@code DELETE /api/stock/{id}}. The callers run it inside their
 * own transaction: {@link #lockOwner} first (the owner row is the lock that serializes first saves and additions),
 * {@link #save} or {@link #add} after;
 * {@link #remove} takes that lock itself and then deletes the entry.
 */
@Service @RequiredArgsConstructor
public class StockService {
    /** A member who may see a club entry but neither administers the club nor added the entry. */
    public static final String REMOVE_RIGHTS_REQUIRED = "Only a club admin or the member who added it can remove this entry";
    /** A member asking to keep a club entry private: club stock is the club's and buddies never see it. */
    public static final String ONLY_PERSONAL_PRIVATE = "Only glazes on your own shelf can be kept private";
    /** An addition whose total would pass {@link #MAX_QUANTITY_G}. */
    public static final String AMOUNT_TOO_LARGE = "That amount is too large for one shelf entry";
    /** A save without an amount and without a jar size. */
    public static final String NOTHING_TO_SAVE = "Send an amount or a jar size";
    /** The largest amount a save or an addition may reach (the client's own bound for an amount). */
    public static final int MAX_QUANTITY_G = 1_000_000_000;

    /**
     * The locked owner of a stock entry: exactly one of {@code user} and {@code club} is set; {@code addedBy} is the
     * member who saves a club entry (recorded when the save creates it).
     */
    public record Owner(AppUser user, Club club, AppUser addedBy) {}

    private final AppUserRepository users;
    private final ClubRepository clubs;
    private final StockRepository stocks;
    private final MembershipRepository memberships;
    private final CurrentUser current;

    private static ResponseStatusException unknownStock() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown stock entry");
    }

    private static ResponseStatusException notAMember() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, CurrentUser.CLUB_MEMBERSHIP_REQUIRED);
    }

    /**
     * Validates the {@code ownerType} of a save request ("personal" or "club" with a club ID) and returns the
     * club ID, or null for personal stock (a club ID sent with "personal" is ignored).
     */
    public UUID ownerOf(String ownerType, UUID clubId) {
        if ("personal".equals(ownerType)) return null;
        if ("club".equals(ownerType) && clubId != null) return clubId;
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose personal or club stock");
    }

    /** Locks the owner of a save; a non-member or an unknown club gets 403 (see the three-argument form). */
    public Owner lockOwner(AppUser actor, UUID clubId) {
        return lockOwner(actor, clubId, StockService::notAMember);
    }

    /**
     * Locks the actor (personal stock, {@code clubId} null) or the club, then checks the membership with a fresh
     * query under that lock, so a member removed while the request waited is refused; an unknown club and a
     * non-member both get {@code denial}, refused before the lock (an early refusal, repeated under it), so an
     * outsider neither waits for nor holds the club and a real club answers like an unknown one.
     */
    private Owner lockOwner(AppUser actor, UUID clubId, Supplier<ResponseStatusException> denial) {
        // An account deleted or banned while the request waited is signed out: 401, never a 500.
        if (clubId == null) return new Owner(current.lockUser(actor.getId()), null, null);
        if (!current.inClub(actor.getId(), clubId)) throw denial.get();
        Club club = clubs.lockById(clubId).orElseThrow(denial);
        if (!current.inClub(actor.getId(), clubId)) throw denial.get();
        return new Owner(null, club, users.getReferenceById(actor.getId()));
    }

    /**
     * Sets the owner's amount of a glaze, creating the entry when needed; an absent jar size keeps the old one, an absent
     * amount the stored one (a size-only save: a new entry starts at 0 g), so a device changing only the size never
     * resets an amount another device saved.
     */
    public Stock save(Owner owner, Glaze glaze, Integer quantityG, Integer jarSizeG) {
        Stock stock = entryOf(owner, glaze);
        if (quantityG != null) stock.setQuantityG(quantityG);
        if (jarSizeG != null) stock.setJarSizeG(jarSizeG);
        return stocks.save(stock);
    }

    /**
     * Adds {@code addG} grams (a jar, "+1 jar") to the amount the owner holds now, creating the entry at {@code addG}
     * when there is none. Runs after {@link #lockOwner}, so the stored amount is read under the owner lock and
     * concurrent additions all count, whatever the devices last saw. A sent jar size applies only to an entry without
     * one (a new entry included): a stored size is kept (STOCK-2: a stale one-click add never resets another device's
     * jar), unless {@code replaceJarSize} asks to change it on purpose. A total above {@link #MAX_QUANTITY_G} is
     * refused with 400.
     */
    public Stock add(Owner owner, Glaze glaze, int addG, Integer jarSizeG, boolean replaceJarSize) {
        Stock stock = entryOf(owner, glaze);
        long total = (long) stock.getQuantityG() + addG;
        if (total > MAX_QUANTITY_G) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, AMOUNT_TOO_LARGE);
        stock.setQuantityG((int) total);
        if (jarSizeG != null && (replaceJarSize || stock.getJarSizeG() == null)) stock.setJarSizeG(jarSizeG);
        return stocks.save(stock);
    }

    /** The owner's entry of the glaze, or a new one at 0 g (its creation time and adder set by the constructor). */
    private Stock entryOf(Owner owner, Glaze glaze) {
        return (owner.user() != null
                ? stocks.findByGlaze_IdAndUser_Id(glaze.getId(), owner.user().getId())
                : stocks.findByGlaze_IdAndClub_Id(glaze.getId(), owner.club().getId()))
                .orElseGet(() -> new Stock(glaze, owner.user(), owner.club(), owner.addedBy(), 0));
    }

    /**
     * Sets whether the owner keeps a personal entry from their buddies ({@code BuddyService.availability} skips it).
     * Locks the owner row first, as a save and a removal do; only the owner may change it: another user's personal
     * entry and an unknown one answer 404, a club entry 400 for its members (they see it) and 404 for anyone else.
     */
    public void keepPrivate(AppUser actor, UUID id, boolean keepPrivate) {
        current.lockUser(actor.getId());
        Stock stock = stocks.findById(id).orElseThrow(StockService::unknownStock);
        if (stock.getClub() != null) {
            if (current.inClub(actor.getId(), stock.getClub().getId()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ONLY_PERSONAL_PRIVATE);
            throw unknownStock();
        }
        if (!stock.getUser().getId().equals(actor.getId())) throw unknownStock();
        stock.setKeepPrivate(keepPrivate);
    }

    /**
     * Removes a stock entry by {@link Stock#removableBy}: personal stock for its owner, club stock for an admin of
     * the club or the member who added it. Someone who cannot see the entry (another user's personal stock, a
     * non-member) and an unknown entry get 404; a member without the right 403. Locks the owner row first, as the
     * save does, so a removal and a save of the same shelf never interleave; membership, role and adder are read
     * fresh under that lock.
     */
    public void remove(AppUser actor, UUID id) {
        Stock found = stocks.findById(id).orElseThrow(StockService::unknownStock);
        UUID clubId = found.getClub() == null ? null : found.getClub().getId();
        if (clubId == null && !found.getUser().getId().equals(actor.getId())) throw unknownStock();
        lockOwner(actor, clubId, StockService::unknownStock);
        if (clubId != null) {
            boolean admin = memberships.roleOf(clubId, actor.getId()).filter(role -> role == Membership.Role.ADMIN).isPresent();
            if (!Stock.removableBy(clubId, stocks.adderOf(id).orElse(null), actor.getId(), admin ? Set.of(clubId) : Set.of())) {
                if (!stocks.existsById(id)) throw unknownStock();
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, REMOVE_RIGHTS_REQUIRED);
            }
        }
        // A concurrent removal (or glaze removal) may have deleted it before the lock was granted.
        if (stocks.deleteStockById(id) == 0) throw unknownStock();
    }
}
