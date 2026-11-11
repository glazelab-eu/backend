package eu.glazelab.api.controller;

import eu.glazelab.api.model.Text;
import eu.glazelab.api.dto.AvatarDtos.AuthorAvatarView;
import eu.glazelab.api.dto.AvatarDtos.AvatarReportPageView;
import eu.glazelab.api.dto.AvatarDtos.AvatarReportView;
import eu.glazelab.api.dto.CommonDtos.IdView;
import eu.glazelab.api.dto.ImageDtos.ImageReportInput;
import eu.glazelab.api.model.AppUser;
import eu.glazelab.api.model.AvatarEvidence;
import eu.glazelab.api.model.AvatarFingerprint;
import eu.glazelab.api.model.AvatarPictureState;
import eu.glazelab.api.model.AvatarReportStatus;
import eu.glazelab.api.model.UserAvatar;
import eu.glazelab.api.repository.AvatarEvidenceRepository;
import eu.glazelab.api.repository.AvatarReportRepository;
import eu.glazelab.api.repository.UserAvatarRepository;
import eu.glazelab.api.security.CurrentUser;
import eu.glazelab.api.service.AvatarService;
import eu.glazelab.api.storage.ImageStorage;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Profile pictures (backend guide "Profile pictures"): never photos, never public. Every endpoint needs a signed-in,
 * verified account (the security chain answers 401 to guests and to sessions of unverified accounts); every such
 * account may fetch any account's current picture by its ID, which changes with every new picture. A picture that is
 * no longer current (replaced, removed, its account deleted) is never served here, kept evidence included: only site
 * admins see it, through the report it belongs to ({@code GET /api/admin/avatar-reports/{id}/picture}).
 */
@RestController @RequestMapping("/api") @RequiredArgsConstructor
public class AvatarController {
    private final CurrentUser current;
    private final AvatarService avatars;
    private final UserAvatarRepository records;
    private final AvatarReportRepository reports;
    private final AvatarEvidenceRepository evidence;
    private final ImageStorage storage;

    /**
     * Sets or replaces the caller's picture and answers its new ID. The client crops a square; the server cuts any other
     * shape to its centre square and stores it re-encoded without metadata (photo limits, sniffing and encoder permit).
     * A picture that matches a block (a site admin removed it) is 409 {@code AvatarService.PICTURE_REFUSED} for everyone.
     */
    @PostMapping(path = "/avatars", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    IdView uploadAvatar(@RequestPart("file") MultipartFile file, Authentication auth) throws IOException {
        AppUser user = current.user(auth);
        ImageEncoder.Encoded encoded = ImageEncoder.encodeAvatar(file);
        AvatarFingerprint fingerprint;
        try (InputStream upload = file.getInputStream()) {
            fingerprint = AvatarFingerprint.of(upload, encoded.jpeg());
        }
        avatars.refuseBlocked(fingerprint);
        return new IdView(ImageController.storeObjects(storage, encoded, key -> avatars.replace(user.getId(), key, fingerprint)));
    }

    /**
     * Removes a picture: its owner (also with pending terms), or a site admin (moderation: blocked, its open reports
     * ACTIONED); 403 for other accounts, 404 when it is gone.
     */
    @DeleteMapping("/avatars/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeAvatar(@PathVariable UUID id, Authentication auth) {
        avatars.remove(id, current.user(auth));
    }

    /** Reports another account's picture once; the site admins see it at {@code GET /api/admin/avatar-reports}. */
    @PostMapping("/avatars/{id}/reports") @ResponseStatus(HttpStatus.NO_CONTENT)
    void reportAvatar(@PathVariable UUID id, @Valid @RequestBody ImageReportInput body, Authentication auth) {
        AppUser actor = current.user(auth);
        avatars.report(id, actor, Text.optional(body.reason(), Text.Kind.MULTI_LINE));
    }

    /** The picture, a square of at most {@code ImageEncoder.AVATAR_SIDE} px. */
    @GetMapping("/avatars/{id}")
    ResponseEntity<byte[]> avatar(@PathVariable UUID id, Authentication auth) throws IOException {
        return download(id, auth, false);
    }

    /** The small copy (at most {@code ImageEncoder.AVATAR_SMALL_SIDE} px) for member rows and author lines. */
    @GetMapping("/avatars/{id}/small")
    ResponseEntity<byte[]> smallAvatar(@PathVariable UUID id, Authentication auth) throws IOException {
        return download(id, auth, true);
    }

    /**
     * The current pictures of the authors of published combinations, for the cards and pages that name an author
     * (the synced {@code CombinationView} never carries one, see the guide). Members and buddies carry theirs in their views.
     */
    @GetMapping("/combinations/author-avatars")
    List<AuthorAvatarView> authorAvatars(Authentication auth) {
        current.user(auth);
        return avatars.ofAuthors();
    }

    /**
     * Newest first, in pages of {@link ImageDeletionController#PAGE_SIZE}, like the photo report queue; reports stay
     * after they are closed, so {@code status} narrows the page to one state (the open queue: {@code OPEN}).
     */
    @GetMapping("/admin/avatar-reports") @Transactional(readOnly = true)
    AvatarReportPageView avatarReports(@RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) AvatarReportStatus status, Authentication auth) {
        current.siteAdmin(auth);
        var request = PageRequest.of(ImageDeletionController.page(page), ImageDeletionController.PAGE_SIZE,
                Sort.by("createdAt", "id").descending());
        var result = status == null ? reports.queue(request) : reports.queue(status, request);
        return new AvatarReportPageView(result.stream().map(AvatarController::view).toList(), result.hasNext());
    }

    private static AvatarReportView view(AvatarReportRepository.QueueRow row) {
        // Every removal of a reported picture writes its evidence row, so one of the two is always there.
        AvatarPictureState picture = row.getCurrent() != null ? AvatarPictureState.CURRENT
                : row.getOutcome() != null ? row.getOutcome() : AvatarPictureState.REPLACED;
        return new AvatarReportView(row.getId(), row.getAvatarId(), row.getOwnerId(), row.getOwner(), row.getReporter(),
                row.getReason(), row.getCreatedAt(), row.getStatus(), row.getClosedAt(), row.getClosedBy(), picture,
                row.getCurrent() != null || row.getEvidenceKey() != null);
    }

    /** Closes every open report of the report's picture as DISMISSED and leaves the picture (409 when it was ACTIONED). */
    @PostMapping("/admin/avatar-reports/{id}/dismiss") @ResponseStatus(HttpStatus.NO_CONTENT)
    void dismissAvatarReport(@PathVariable UUID id, Authentication auth) {
        decide(id, auth, AvatarReportStatus.DISMISSED);
    }

    /**
     * Closes every open report of the report's picture as ACTIONED: removes the picture if it is still the account's
     * and blocks it from being uploaded again by any account, also when it was replaced or removed (409 when it was
     * DISMISSED or CLOSED with its account).
     */
    @PostMapping("/admin/avatar-reports/{id}/remove") @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeReportedAvatar(@PathVariable UUID id, Authentication auth) {
        decide(id, auth, AvatarReportStatus.ACTIONED);
    }

    private void decide(UUID report, Authentication auth, AvatarReportStatus decision) {
        if (!avatars.decide(report, current.siteAdmin(auth), decision))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ImageReportController.REPORT_NOT_FOUND);
    }

    /**
     * The reported picture for site admins only: the account's current picture while it is one, else its quarantined
     * evidence while kept (404 {@code AVATAR_NOT_FOUND} once purged or never kept, {@code REPORT_NOT_FOUND} for an
     * unknown report). {@code no-store}: no browser or proxy keeps a copy, so a purge is final.
     */
    @GetMapping("/admin/avatar-reports/{id}/picture")
    ResponseEntity<byte[]> reportedAvatar(@PathVariable UUID id, Authentication auth) throws IOException {
        current.siteAdmin(auth);
        UUID avatarId = reports.avatarOf(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ImageReportController.REPORT_NOT_FOUND));
        String key = records.findById(avatarId).map(UserAvatar::getStorageKey)
                .or(() -> evidence.findById(avatarId).map(AvatarEvidence::getStorageKey))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, AvatarService.AVATAR_NOT_FOUND));
        return Pictures.answer(storage, CacheControl.noStore().cachePrivate(), false, AvatarController::avatarNotFound, key);
    }

    /**
     * Any signed-in account, for any account's current picture. Only the viewer's browser may keep it, for the photos'
     * week ({@code ImageController.browserOnly}; the app's request after sign-out clears it with {@code Clear-Site-Data}, {@code AuthController.clearBrowserCache}); {@code X-Accel-Expires: 0}
     * keeps it out of every Nginx cache. Errors keep Spring Security's {@code no-store}.
     */
    private ResponseEntity<byte[]> download(UUID id, Authentication auth, boolean small) throws IOException {
        current.user(auth);
        String key = records.findById(id).map(UserAvatar::getStorageKey).orElseThrow(AvatarController::avatarNotFound);
        return Pictures.answer(storage, ImageController.browserOnly(), false, AvatarController::avatarNotFound,
                small ? ImageStorage.cardKey(key) : key);
    }

    private static ResponseStatusException avatarNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, AvatarService.AVATAR_NOT_FOUND);
    }
}
