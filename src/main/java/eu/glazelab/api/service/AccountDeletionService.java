package eu.glazelab.api.service;

import eu.glazelab.api.exception.ApiErrorHandler;
import eu.glazelab.api.model.AdminAction;
import eu.glazelab.api.model.AppUser;
import eu.glazelab.api.model.BuddyId;
import eu.glazelab.api.model.Combination;
import eu.glazelab.api.model.Glaze;
import eu.glazelab.api.model.ImageRecord;
import eu.glazelab.api.model.ImageVisibility;
import eu.glazelab.api.model.Membership;
import eu.glazelab.api.model.MembershipId;
import eu.glazelab.api.repository.AdminActionRepository;
import eu.glazelab.api.repository.AppUserRepository;
import eu.glazelab.api.repository.ClubRepository;
import eu.glazelab.api.repository.CombinationRepository;
import eu.glazelab.api.repository.CombinationStepRepository;
import eu.glazelab.api.repository.FavoriteRepository;
import eu.glazelab.api.repository.GlazeEditRepository;
import eu.glazelab.api.repository.GlazeRepository;
import eu.glazelab.api.repository.ImageDeletionRepository;
import eu.glazelab.api.repository.ImageRecordRepository;
import eu.glazelab.api.repository.MembershipRepository;
import eu.glazelab.api.repository.StockRepository;
import eu.glazelab.api.security.CurrentUser;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Account deletion by its owner and the site admins' ban (backend guide "Bans"): one removal, so a banned account goes
 * exactly as a deleted one does. The ban differs in four places only: two account locks, a club's last admin is
 * replaced by its longest-standing verified member instead of refusing, the deletion log names the banning admin, and
 * the address goes onto the blocklist ({@link EmailBlocklist}) in the same transaction.
 */
@Service @RequiredArgsConstructor @Slf4j
public class AccountDeletionService {
    public static final String SELF_BAN = "You cannot ban your own account.";
    public static final String ADMIN_BAN = "Site admins cannot be banned.";
    public static final String ACCOUNT_NOT_FOUND = "Account not found";
    private final AppUserRepository users;
    private final MembershipRepository memberships;
    private final ClubRepository clubs;
    private final ImageRecordRepository images;
    private final ImageDeletionRepository deletions;
    private final GlazeRepository glazes;
    private final CombinationRepository combinations;
    private final CombinationStepRepository steps;
    private final StockRepository stocks;
    private final FavoriteRepository favorites;
    private final AccountRows accountRows;
    private final PasswordEncoder passwords;
    private final TransactionTemplate transactions;
    private final ImageRemovalService imageRemoval;
    private final GlazeEditRepository edits;
    private final ChangeRecorder changes;
    private final EntityManager entities;
    private final EmailBlocklist blocklist;
    private final AvatarService avatars;
    private final AdminActionRepository actions;

    /** Who bans, and whether the account's reported profile pictures are blocked too; null for an own deletion. */
    private record Ban(UUID adminId, boolean blockPictures) {}

    public void delete(UUID userId, String password) {
        try {
            transactions.executeWithoutResult(status -> {
                var user = users.lockById(userId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, CurrentUser.SIGN_IN_REQUIRED));
                if (!passwords.matches(password, user.getPasswordHash()))
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Incorrect password. Your account was not deleted.");
                removeRecords(userId, null);
            });
        } catch (DataIntegrityViolationException error) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Your account data changed. Please try deleting it again.");
        }
    }

    /**
     * A site admin bans {@code targetId}: removed as {@link #delete} removes an account, its address blocked from
     * registering again, the ban logged. 409 for the admin's own account and for any site admin (so the last site
     * admin can never be banned), 404 for an unknown account, 403 when the actor is no longer a site admin. Locks both
     * account rows in {@link BuddyId#ORDER} (the rule of every two-account write), then continues in the deletion's
     * order; the actor's row is held so its flag and its foreign keys (blocklist, logs) stay valid until commit.
     */
    public void ban(UUID adminId, UUID targetId, String reason, boolean blockPictures) {
        if (adminId.equals(targetId)) throw new ResponseStatusException(HttpStatus.CONFLICT, SELF_BAN);
        try {
            transactions.executeWithoutResult(status -> {
                AppUser admin = null, target = null;
                for (UUID id : List.of(adminId, targetId).stream().sorted(BuddyId.ORDER).toList()) {
                    AppUser locked = users.lockById(id).orElse(null);
                    if (id.equals(adminId)) admin = locked; else target = locked;
                }
                if (admin == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, CurrentUser.SIGN_IN_REQUIRED);
                if (!admin.isSiteAdmin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, CurrentUser.SITE_ADMIN_REQUIRED);
                if (target == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, ACCOUNT_NOT_FOUND);
                if (target.isSiteAdmin()) throw new ResponseStatusException(HttpStatus.CONFLICT, ADMIN_BAN);
                String email = target.getEmail();
                removeRecords(targetId, new Ban(adminId, blockPictures));
                blocklist.add(email, reason, adminId, targetId);
                log.info("Site admin {} banned account {}", adminId, targetId);
            });
        } catch (DataIntegrityViolationException error) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiErrorHandler.CHANGED_MEANWHILE);
        }
    }

    /**
     * Lock order (after the account lock, or both account locks of a ban): clubs by ID -> authored glazes and
     * combinations by ID -> image rows by ID -> profile picture and evidence rows ({@code blockPictures}, then AccountRows).
     */
    private void removeRecords(UUID userId, Ban ban) {
        var soleMemberClubs = lockClubs(userId, ban);
        // Admin glaze removal also locks glaze -> images. Keep that order before bulk author updates.
        var authoredGlazes = glazes.lockByAuthor(userId);
        // Parent locks precede image locks and the later author update, as image/combination writes do.
        var authored = combinations.lockByAuthor(userId);
        var drafts = authored.stream().filter(Combination::isDraft).toList();
        var keptUploads = removeOwnImages(userId, soleMemberClubs, ban == null ? userId : ban.adminId());
        if (ban != null && ban.blockPictures()) avatars.blockReportedOf(userId, ban.adminId());
        recordAnonymisation(userId, authoredGlazes, authored, keptUploads);
        anonymiseAndDelete(userId, soleMemberClubs, drafts);
    }

    /**
     * Locks every club the user belongs to or owns in ID order and returns those nobody else is in.
     * Club mutations use the same club locks; ordered locking also serializes simultaneous deletions.
     * The last admin of a club with other members: an own deletion is refused (409); a ban must not be blockable by
     * it, so the longest-standing verified member becomes admin (pending leftovers never take over a club: for a ban
     * a club whose only other members are pending counts as a sole-member club and goes with the account).
     */
    private SortedSet<UUID> lockClubs(UUID userId, Ban ban) {
        var clubIds = new TreeSet<UUID>();
        var soleMemberClubs = new TreeSet<UUID>();
        // Scalar IDs only: a Membership loaded before its club lock would keep a stale role for the admin check.
        clubIds.addAll(memberships.clubIdsOf(userId));
        clubs.findByOwner_Id(userId).forEach(c -> clubIds.add(c.getId()));
        for (UUID clubId : clubIds) {
            clubs.lockById(clubId).orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Club changed. Please try again."));
            // Includes a legacy club the user owns without any membership: nobody else could ever reach it.
            var others = memberships.findByClub_Id(clubId).stream().filter(m -> !m.getUser().getId().equals(userId))
                    .filter(m -> ban == null || m.getUser().isEmailVerified()).toList();
            if (others.isEmpty()) {
                soleMemberClubs.add(clubId);
                continue;
            }
            memberships.findById(new MembershipId(clubId, userId)).ifPresent(member -> {
                if (member.getRole() != Membership.Role.ADMIN
                        || memberships.countByClub_IdAndRole(clubId, Membership.Role.ADMIN) > 1) return;
                if (ban == null)
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Make another member an admin in each club before deleting your account.");
                promote(clubId, others, ban.adminId());
            });
        }
        return soleMemberClubs;
    }

    /**
     * Under the club lock: the member who joined first (ties, such as memberships older than {@code joined_at}, by
     * account ID in {@link BuddyId#ORDER}) becomes admin, logged by IDs only and in {@code admin_action}. The member's
     * account row is not locked (nothing locks an account after a club); their own deletion or leave queues on the
     * club lock and then reads the new role.
     */
    private void promote(UUID clubId, List<Membership> others, UUID adminId) {
        UUID heir = others.stream().min(Comparator.comparing(Membership::getJoinedAt)
                .thenComparing(m -> m.getUser().getId(), BuddyId.ORDER)).orElseThrow().getUser().getId();
        memberships.promote(clubId, heir);
        actions.save(new AdminAction(adminId, AdminAction.Type.CLUB_ADMIN_PROMOTED, heir, clubId, null, Instant.now()));
        log.info("A ban by site admin {} made member {} an admin of club {}", adminId, heir, clubId);
    }

    /**
     * Sole-member clubs lose all their photos (CLUB-only by the visibility constraint, anonymous uploads too);
     * elsewhere only the user's PRIVATE photos go, and those of their drafts. One ID-ordered lock set avoids deadlocks
     * with glaze removal.
     * The audit rows commit with the deletion; stored objects go after the outer commit.
     */
    private List<ImageRecord> removeOwnImages(UUID userId, Set<UUID> soleMemberClubs, UUID actor) {
        var locked = soleMemberClubs.isEmpty() ? images.findByUploadedBy_IdOrderByIdAsc(userId)
                : images.lockByUploaderOrClubs(userId, soleMemberClubs);
        // A draft's photos are effectively PRIVATE: they go with the drafts.
        Predicate<ImageRecord> removed = image -> image.effectiveVisibility() == ImageVisibility.PRIVATE
                || image.getClub() != null && soleMemberClubs.contains(image.getClub().getId());
        imageRemoval.remove(locked.stream().filter(removed).toList(), actor);
        images.flush();
        return locked.stream().filter(removed.negate()).filter(image -> image.uploadedBy(userId)).toList();
    }

    /**
     * The bulk anonymisation below changes views without touching their rows one by one, so it logs explicitly:
     * authored glazes ({@code addedBy}) and combinations ({@code author}; drafts reach only their gone author), glazes
     * whose newest correction may name the account ({@code lastEditedBy}) and the parents of the shared photos it
     * uploaded ({@code ImageView.uploader}), for whoever may see each photo. Removed photos log in ImageRemovalService.
     */
    private void recordAnonymisation(UUID userId, List<Glaze> authoredGlazes, List<Combination> authored, List<ImageRecord> keptUploads) {
        authoredGlazes.forEach(glaze -> changes.glaze(glaze.getId()));
        edits.glazeIdsEditedBy(userId).forEach(changes::glaze);
        authored.forEach(changes::combination);
        keptUploads.forEach(changes::picture);
    }

    private void anonymiseAndDelete(UUID userId, Set<UUID> soleMemberClubs, List<Combination> drafts) {
        // Drafts are private (their photos went above): they go with the account instead of becoming anonymous
        // rows nobody could see. Published combinations are shared and only lose their author.
        if (!drafts.isEmpty()) {
            for (var draft : drafts) favorites.deleteByCombinationId(draft.getId());
            steps.deleteAll(steps.findForCombinations(drafts.stream().map(Combination::getId).toList()));
            combinations.deleteAll(drafts);
            combinations.flush();
        }
        // An upload from another session can commit after the locked read above (inserts only take a key-share
        // lock on the account). A late PRIVATE photo keeps its uploader, so the FK fails the deletion (409, retry)
        // instead of leaving an anonymous private photo nobody can see or remove.
        images.anonymiseSharedUploads(userId);
        glazes.anonymiseAuthor(userId);
        combinations.anonymiseAuthor(userId);
        clubs.anonymiseOwner(userId);
        accountRows.delete(userId);
        for (UUID clubId : soleMemberClubs) stocks.deleteByClub_Id(clubId);
        deletions.anonymiseUploader(userId);
        deletions.anonymiseActor(userId);
        // Bulk anonymisation leaves old associations in managed entities. Flush removals and reload
        // before deleting the account so Hibernate cannot re-use those stale references.
        entities.flush();
        // Bulk club deletion does not automatically flush queued membership/stock removals. A ban's sole-member club may
        // still hold leftover pending members (they never count as members there).
        if (!soleMemberClubs.isEmpty()) {
            memberships.deleteByClubs(soleMemberClubs);
            clubs.deleteAllByIdInBatch(soleMemberClubs);
        }
        entities.clear();
        users.deleteById(userId);
        users.flush();
    }
}
