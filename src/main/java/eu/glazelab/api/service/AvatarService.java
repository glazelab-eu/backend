package eu.glazelab.api.service;

import eu.glazelab.api.dto.AvatarDtos.AuthorAvatarView;
import eu.glazelab.api.exception.ApiErrorHandler;
import eu.glazelab.api.model.AdminAction;
import eu.glazelab.api.model.AppUser;
import eu.glazelab.api.model.AvatarBlock;
import eu.glazelab.api.model.AvatarEvidence;
import eu.glazelab.api.model.AvatarFingerprint;
import eu.glazelab.api.model.AvatarPictureState;
import eu.glazelab.api.model.AvatarReport;
import eu.glazelab.api.model.AvatarReportStatus;
import eu.glazelab.api.model.UserAvatar;
import eu.glazelab.api.repository.AdminActionRepository;
import eu.glazelab.api.repository.AvatarBlockRepository;
import eu.glazelab.api.repository.AvatarEvidenceRepository;
import eu.glazelab.api.repository.AvatarReportRepository;
import eu.glazelab.api.repository.UserAvatarRepository;
import eu.glazelab.api.security.CurrentUser;
import eu.glazelab.api.security.TermsFilter;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Profile pictures (backend guide "Profile pictures"): one per account, shown to every signed-in account, never to
 * guests, in no photo listing and no sync; reports, quarantined evidence and the re-upload block. Every write locks the
 * acting account first, then the picture row (a removed picture's evidence row instead), and changes reports, evidence
 * and blocks only after that, so a replacement, a removal, a report, a site admin's decision and an account deletion
 * queue on these rows and never deadlock; a picture that went meanwhile is 404. Objects are deleted after commit.
 */
@Service @RequiredArgsConstructor @Slf4j
public class AvatarService {
    public static final String AVATAR_NOT_FOUND = "Profile picture not found";
    public static final String OWN_AVATAR_REPORT = "You cannot report your own profile picture; remove it instead";
    public static final String REMOVE_RIGHTS_REQUIRED = "Only its owner or a site admin can remove a profile picture";
    /** The blocked-upload answer: the same for every account, and it names no report, account or reason. */
    public static final String PICTURE_REFUSED = "This picture cannot be used as a profile picture.";

    private final UserAvatarRepository avatars;
    private final AvatarReportRepository reports;
    private final AvatarEvidenceRepository evidence;
    private final AvatarBlockRepository blocks;
    private final AdminActionRepository actions;
    private final CurrentUser current;
    private final ImageRemovalService removal;
    private final TransactionTemplate transactions;

    /** How long a reported picture's objects are kept after its last report was closed (the terms name it). */
    @Value("${glazelab.avatar.evidence-retention}") private Duration retention;
    /** How long a report stays open before it is CLOSED without a decision (its evidence then goes with the purge). */
    @Value("${glazelab.moderation.report-expiry}") private Duration reportExpiry;
    /** The largest dHash distance (bits of 64) at which an upload counts as a blocked picture. */
    @Value("${glazelab.avatar.block-distance}") private int blockDistance;

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, AVATAR_NOT_FOUND);
    }

    /**
     * Refuses (409 {@link #PICTURE_REFUSED}) an upload that matches a block, whoever sends it. Runs before the objects
     * are stored and holds no lock: an upload that races the removal blocking it is not refused (it can be reported).
     */
    @Transactional(readOnly = true)
    public void refuseBlocked(AvatarFingerprint upload) {
        for (var block : blocks.hashes())
            if (upload.matches(block.getUploadSha256(), block.getStoredSha256(), block.getDhash(), blockDistance))
                throw new ResponseStatusException(HttpStatus.CONFLICT, PICTURE_REFUSED);
    }

    /**
     * Makes the stored objects under {@code key} the account's picture. The previous one (if any) goes: its ID and URLs
     * answer 404 from then on, its reports stay, and a reported one becomes evidence ({@link #retire}). Under the
     * account lock, which every write of the account's picture takes first, so the account keeps exactly one.
     */
    @Transactional
    public UUID replace(UUID userId, String key, AvatarFingerprint fingerprint) {
        current.lockUser(userId);
        avatars.lockByUserId(userId).ifPresent(old -> retire(old, AvatarPictureState.REPLACED));
        return avatars.saveAndFlush(new UserAvatar(userId, key, fingerprint, Instant.now())).getId();
    }

    /**
     * {@code DELETE /api/avatars/{id}}: its owner (who needs no current terms: {@code TermsFilter} passes this route,
     * so withdrawing a picture never requires accepting new terms) or a site admin (with current terms; moderation:
     * removed, blocked and its open reports ACTIONED); any other signed-in account sees the picture and gets 428 with
     * pending terms, else 403; an unknown or already replaced picture is 404. Locks the actor's account, then the
     * picture row.
     */
    @Transactional
    public void remove(UUID avatarId, AppUser actor) {
        AppUser locked = current.lockUser(actor.getId());
        UserAvatar avatar = avatars.lockById(avatarId).orElseThrow(AvatarService::notFound);
        if (avatar.ownedBy(actor.getId())) {
            retire(avatar, AvatarPictureState.OWNER_REMOVED);
            return;
        }
        if (!AccountService.TERMS_VERSION.equals(locked.getAcceptedTermsVersion()))
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, TermsFilter.TERMS_REQUIRED);
        if (!locked.isSiteAdmin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, REMOVE_RIGHTS_REQUIRED);
        moderate(avatar, actor.getId());
    }

    /**
     * A signed-in account reports another account's picture, once: a repeat keeps the first report (a closed one too)
     * and answers 204 again. The owner removes instead (400). Locks the reporter's account, then the picture row.
     */
    @Transactional
    public void report(UUID avatarId, AppUser actor, String reason) {
        current.lockUser(actor.getId());
        UserAvatar avatar = avatars.lockById(avatarId).orElseThrow(AvatarService::notFound);
        if (avatar.ownedBy(actor.getId())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, OWN_AVATAR_REPORT);
        if (!reports.existsByAvatarIdAndReporterId(avatarId, actor.getId()))
            reports.save(new AvatarReport(avatar, actor.getId(), reason, Instant.now()));
    }

    /**
     * A site admin's decision on a report, applied to every open report of its picture: DISMISSED leaves the picture;
     * ACTIONED removes it if it is still the account's picture and blocks it either way (the fingerprint comes from the
     * picture or its evidence, so a picture its owner replaced or removed is blocked too). The same decision again is a
     * no-op; a report another decision or the account's deletion (CLOSED) closed meanwhile is 409. False for an unknown
     * report.
     * Locks the admin's account (403 if the flag went meanwhile), then the picture row or, once it is gone, its evidence
     * row.
     */
    @Transactional
    public boolean decide(UUID reportId, AppUser admin, AvatarReportStatus decision) {
        if (decision == AvatarReportStatus.OPEN) throw new IllegalArgumentException("Reports are never reopened");
        current.lockSiteAdmin(admin.getId());
        UUID avatarId = reports.avatarOf(reportId).orElse(null);
        if (avatarId == null) return false;
        Optional<UserAvatar> picture = avatars.lockById(avatarId);
        Optional<AvatarEvidence> kept = picture.isPresent() ? Optional.empty() : evidence.lockById(avatarId);
        AvatarReportStatus status = reports.statusOf(reportId).orElseThrow();
        if (status == decision) return true;
        if (status != AvatarReportStatus.OPEN)
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiErrorHandler.CHANGED_MEANWHILE);
        if (decision == AvatarReportStatus.ACTIONED && picture.isPresent()) {
            moderate(picture.get(), admin.getId());
            return true;
        }
        if (decision == AvatarReportStatus.ACTIONED) blockGone(avatarId, kept, admin.getId(), null);
        else reports.close(avatarId, decision, Instant.now(), admin.getId());
        return true;
    }

    /**
     * A ban with {@code blockPictures} (backend guide "Bans"): the remove decision for every picture of the account that
     * has an open report, the current one (removed and blocked) and earlier ones kept as evidence (blocked), all their
     * open reports ACTIONED. A picture whose reports were all closed meanwhile is skipped (reports never reopen, and a
     * dismissed picture stays unblocked). Inside the ban's transaction, after its account and image locks; the picture
     * and evidence rows are locked in ID order, and every other picture write locks only one of them.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void blockReportedOf(UUID userId, UUID adminId) {
        for (UUID avatarId : reports.openPicturesOf(userId)) {
            Optional<UserAvatar> picture = avatars.lockById(avatarId);
            Optional<AvatarEvidence> kept = picture.isPresent() ? Optional.empty() : evidence.lockById(avatarId);
            if (reports.statesOf(avatarId).stream().noneMatch(state -> state.getStatus() == AvatarReportStatus.OPEN)) continue;
            if (picture.isPresent()) moderate(picture.get(), adminId);
            else blockGone(avatarId, kept, adminId, userId);
        }
    }

    /** The remove decision on a picture that is gone: its evidence's fingerprint blocked, its open reports ACTIONED. */
    private void blockGone(UUID avatarId, Optional<AvatarEvidence> kept, UUID adminId, UUID ownerId) {
        Instant now = Instant.now();
        kept.map(AvatarEvidence::fingerprint).ifPresent(this::block);
        reports.close(avatarId, AvatarReportStatus.ACTIONED, now, adminId);
        actions.save(new AdminAction(adminId, AdminAction.Type.AVATAR_REMOVED, ownerId, avatarId, null, now));
        log.info("Site admin {} blocked the removed profile picture {}", adminId, avatarId);
    }

    /** The account's own picture for its {@code UserView}, or null. */
    @Transactional(readOnly = true)
    public UUID idOf(UUID userId) {
        return avatars.idOf(userId).orElse(null);
    }

    /** The pictures of every author of a published combination: one statement. */
    @Transactional(readOnly = true)
    public List<AuthorAvatarView> ofAuthors() {
        return avatars.ofAuthors().stream().map(row -> new AuthorAvatarView(row.getUserId(), row.getAvatarId())).toList();
    }

    /**
     * Removes the account's picture, if any, inside the account deletion, a ban or the pending purge
     * ({@code AccountRows}), after the account lock (a product decision): the current picture and every
     * evidence copy of the account's earlier pictures are deleted now (objects after commit), the fingerprints stay (a
     * reported picture's evidence row keeps its hashes, blocks are untouched), so a blocked picture stays blocked. Open
     * reports about the account's pictures are CLOSED and every report about them loses its text: what is left is the
     * status and the dates. Runs before the account row goes, while {@code owner_id} still names it; evidence rows are
     * released in ID order (as the purge and {@code blockPictures} lock them), then the reports are updated.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void removeOf(UUID userId) {
        avatars.lockByUserId(userId).ifPresent(avatar -> retire(avatar, AvatarPictureState.ACCOUNT_DELETED));
        List<String> keys = new ArrayList<>();
        for (var kept : evidence.keptOf(userId))
            if (evidence.release(kept.getAvatarId(), kept.getStorageKey()) == 1) keys.add(kept.getStorageKey());
        removal.deleteObjectsAfterCommit(keys);
        reports.closeWithAccount(userId, Instant.now());
        reports.forgetReasonsAbout(userId);
    }

    /**
     * Daily: deletes the objects of evidence whose every report has been closed for longer than the retention, and of
     * evidence left by a deleted account before V32 (the row with outcome and hashes stays, so the reports keep their
     * state and the block its fingerprint). Logs counts only.
     */
    @Scheduled(fixedDelay = 86_400_000, initialDelay = 3_600_000)
    public int purgeEvidence() {
        Integer purged = transactions.execute(status -> {
            List<String> keys = new ArrayList<>();
            for (var kept : evidence.expired(Instant.now().minus(retention)))
                if (evidence.release(kept.getAvatarId(), kept.getStorageKey()) == 1) keys.add(kept.getStorageKey());
            removal.deleteObjectsAfterCommit(keys);
            return keys.size();
        });
        if (purged != null && purged > 0) log.info("Deleted {} profile picture evidence copies past their retention", purged);
        return purged == null ? 0 : purged;
    }

    /**
     * Daily: reports opened longer than {@code glazelab.moderation.report-expiry} ago that nobody decided are CLOSED
     * (no {@code closed_by}), then the evidence purge deletes the objects of pictures left with neither an open report
     * nor a recent decision. One transaction per picture, which locks the picture row (or its evidence row once it is
     * gone) like a decision, so a decision waiting behind it finds the report closed (409). Logs counts only.
     */
    @Scheduled(fixedDelay = 86_400_000, initialDelay = 3_600_000)
    public int expireReports() {
        Instant cutoff = Instant.now().minus(reportExpiry);
        int closed = 0;
        for (UUID avatarId : reports.expiringPictures(cutoff)) {
            Integer count = transactions.execute(status -> {
                if (avatars.lockById(avatarId).isEmpty()) evidence.lockById(avatarId);
                return reports.expire(avatarId, cutoff, Instant.now());
            });
            closed += count == null ? 0 : count;
        }
        if (closed > 0) log.info("Closed {} profile picture reports nobody decided in time", closed);
        purgeEvidence();
        return closed;
    }

    /**
     * A site admin removes another account's picture: it becomes evidence, is blocked, its open reports ACTIONED, and the
     * removal is logged in {@code admin_action}.
     */
    private void moderate(UserAvatar avatar, UUID adminId) {
        Instant now = Instant.now();
        retire(avatar, AvatarPictureState.ADMIN_REMOVED);
        block(avatar.fingerprint());
        int closed = reports.close(avatar.getId(), AvatarReportStatus.ACTIONED, now, adminId);
        actions.save(new AdminAction(adminId, AdminAction.Type.AVATAR_REMOVED, avatar.getUserId(), avatar.getId(), null, now));
        log.info("Site admin {} removed and blocked the profile picture {} of account {} ({} reports closed)", adminId,
                avatar.getId(), avatar.getUserId(), closed);
    }

    private void block(AvatarFingerprint fingerprint) {
        if (!blocks.existsByUploadSha256AndStoredSha256(fingerprint.uploadSha256(), fingerprint.storedSha256()))
            blocks.save(new AvatarBlock(fingerprint, Instant.now()));
    }

    /**
     * Deletes a locked picture row. Without reports its objects go after commit. A reported one leaves an evidence row
     * (outcome and hashes) that keeps the objects while a report is open or was decided within the retention (the purge
     * deletes them later) and otherwise lets them go as well; a picture that goes with its account is never kept.
     * Read before any report is closed in the same transaction.
     */
    private void retire(UserAvatar avatar, AvatarPictureState outcome) {
        Instant now = Instant.now();
        List<AvatarReportRepository.State> states = reports.statesOf(avatar.getId());
        if (avatars.deleteOne(avatar.getId()) != 1) return;
        boolean keep = outcome != AvatarPictureState.ACCOUNT_DELETED && states.stream().anyMatch(state ->
                state.getStatus() == AvatarReportStatus.OPEN
                || state.getStatus() != AvatarReportStatus.CLOSED && state.getClosedAt().isAfter(now.minus(retention)));
        if (!states.isEmpty()) evidence.save(new AvatarEvidence(avatar, outcome, keep, now));
        if (!keep) removal.deleteObjectsAfterCommit(List.of(avatar.getStorageKey()));
    }
}
