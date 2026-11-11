package eu.glazelab.api.controller;

import eu.glazelab.api.model.Text;
import eu.glazelab.api.controller.ImageEncoder.Encoded;
import eu.glazelab.api.dto.ImageDtos.ImageDetailsEdit;
import eu.glazelab.api.dto.ImageDtos.ImageFocusEdit;
import eu.glazelab.api.dto.ImageDtos.ImageKindEdit;
import eu.glazelab.api.dto.ImageDtos.ImageReportInput;
import eu.glazelab.api.dto.ImageDtos.ImageView;
import eu.glazelab.api.dto.ImageDtos.ImageVisibilityEdit;
import eu.glazelab.api.model.AppUser;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import lombok.extern.slf4j.Slf4j;
import eu.glazelab.api.model.Club;
import eu.glazelab.api.model.Combination;
import eu.glazelab.api.model.Glaze;
import eu.glazelab.api.model.ImageKind;
import eu.glazelab.api.model.ImageRecord;
import eu.glazelab.api.model.ImageReport;
import eu.glazelab.api.model.Membership;
import eu.glazelab.api.model.ImageVisibility;
import eu.glazelab.api.model.PhotoDetails;
import eu.glazelab.api.repository.AppUserRepository;
import eu.glazelab.api.repository.ClubRepository;
import eu.glazelab.api.repository.ImageReportRepository;
import eu.glazelab.api.repository.MembershipRepository;
import eu.glazelab.api.service.ChangeRecorder;
import eu.glazelab.api.service.CombinationService;
import eu.glazelab.api.service.ImageRemovalService;
import eu.glazelab.api.repository.CombinationRepository;
import eu.glazelab.api.repository.GlazeRepository;
import eu.glazelab.api.repository.ImageRecordRepository;
import eu.glazelab.api.security.CurrentUser;
import eu.glazelab.api.security.CurrentUser.Viewer;
import eu.glazelab.api.security.RateLimitFilter;
import eu.glazelab.api.storage.ImageStorage;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController @RequestMapping("/api")
@RequiredArgsConstructor @Slf4j
public class ImageController {
    public static final String IMAGE_NOT_FOUND = "Image not found";
    public static final String GLAZE_NOT_FOUND = "Glaze not found";
    public static final String CHOOSE_CLUB = "Choose a club";
    public static final String OWNER_REQUIRED = "Only the person who uploaded a photo can change who sees it";
    public static final String DETAILS_OWNER_REQUIRED = "Only the person who uploaded a photo can change its details";
    public static final String PACKAGING_GLAZE_ONLY = "Packaging photos can only belong to glazes";
    public static final String IN_PROGRESS_COMBINATION_ONLY = "Work-in-progress photos can only belong to combinations";
    public static final String DELETE_RIGHTS_REQUIRED = "Only the uploader, a club admin or a site admin can delete this photo";
    public static final String KIND_RIGHTS_REQUIRED = "Only the uploader, a club admin or a site admin can change the type of this photo";
    public static final String FOCUS_RIGHTS_REQUIRED = "Only the uploader, a club admin or a site admin can change the focus of this photo";
    public static final String OWN_PHOTO_REPORT = "You cannot report your own photo; delete it instead";
    public static final String AUTHOR_MUST_SEE = "A photo on a combination by someone else must stay visible to its author";
    private final CombinationRepository combinations;
    private final GlazeRepository glazes;
    private final ClubRepository clubs;
    private final ImageRecordRepository images;
    private final ImageReportRepository reports;
    private final MembershipRepository memberships;
    private final AppUserRepository users;
    private final CurrentUser current;
    private final ImageStorage storage;
    private final ImageRemovalService removal;
    private final ChangeRecorder changes;
    private final TransactionTemplate transactions;

    /**
     * The uploader, an ADMIN of a CLUB picture's club and site admins may delete ({@link #lockManaged}). Log and
     * record removal commit together; storage is touched only after a successful commit.
     */
    @DeleteMapping("/images/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteImage(@PathVariable UUID id, Authentication auth) {
        AppUser actor = current.user(auth);
        transactions.executeWithoutResult(status -> {
            Managed managed = lockManaged(id, actor, DELETE_RIGHTS_REQUIRED);
            removal.remove(List.of(managed.image()), actor.getId());
            if (managed.siteAdmin() && !managed.image().uploadedBy(actor.getId()))
                log.info("Site admin {} removed photo {}", actor.getId(), id);
        });
    }

    /**
     * Whoever may delete a picture (ImageRecord.deletableBy: the uploader, an ADMIN of a CLUB picture's club, site
     * admins) may change what it shows; others who can see it get 403, everyone else 404, before the body counts.
     * PACKAGING stays a glaze's own (400). The same kind answers unchanged and records nothing. Kind is metadata:
     * ID, URL, bytes, visibility and details stay, so only the parent's synced view changes.
     */
    @PutMapping("/images/{id}/kind") @Transactional
    ImageView changeImageKind(@PathVariable UUID id, @Valid @RequestBody ImageKindEdit body, Authentication auth) {
        AppUser actor = current.user(auth);
        Managed managed = lockManaged(id, actor, KIND_RIGHTS_REQUIRED);
        ImageRecord image = managed.image();
        requireKindTarget(body.kind(), image.getGlazeId() != null);
        if (image.getKind() != body.kind()) {
            image.changeKind(body.kind());
            changes.picture(image);
            if (managed.siteAdmin() && !image.uploadedBy(actor.getId()))
                log.info("Site admin {} changed the type of photo {}", actor.getId(), id);
        }
        return ImageView.of(image, current.viewer(auth));
    }

    /**
     * Where covers centre the picture: the same rights, locks and answers as the kind change ({@link #lockManaged}); the
     * focus is checked after the rights (a hidden picture answers 404 whatever the body). Both null resets to the centre;
     * the same focus answers unchanged and records nothing. The stored bytes stay whole, so ID and URL stay.
     */
    @PutMapping("/images/{id}/focus") @Transactional
    ImageView changeImageFocus(@PathVariable UUID id, @Valid @RequestBody ImageFocusEdit body, Authentication auth) {
        AppUser actor = current.user(auth);
        Managed managed = lockManaged(id, actor, FOCUS_RIGHTS_REQUIRED);
        ImageRecord image = managed.image();
        if (image.changeFocus(PhotoDetails.focus(body.focusX(), body.focusY()))) {
            changes.picture(image);
            if (managed.siteAdmin() && !image.uploadedBy(actor.getId()))
                log.info("Site admin {} changed the focus of photo {}", actor.getId(), id);
        }
        return ImageView.of(image, current.viewer(auth));
    }

    /** A locked picture its actor may manage (delete, change the kind or focus); {@code siteAdmin} as read under the account lock. */
    private record Managed(ImageRecord image, boolean siteAdmin) {}

    /**
     * Locks account -> the picture's club -> target -> image, as uploads and visibility changes, and applies
     * ImageRecord.deletableBy: others who can see the picture get 403 with {@code message}, everyone else 404. Only a
     * member takes the club lock (a scalar read first): an outsider can never be its admin, and neither waits for nor
     * holds it. The club role is read after the club lock (a demotion commits under it).
     */
    private Managed lockManaged(UUID id, AppUser actor, String message) {
        AppUser locked = current.lockUser(actor.getId());
        var target = images.target(id).orElseThrow(ImageController::imageNotFound);
        // A draft's photo exists only for its author: site and club admins get the unknown ID's answer, before any club lock.
        if (target.isOnDraft() && !actor.getId().equals(target.getUploaderId())) throw imageNotFound();
        UUID clubId = target.getClubId();
        boolean clubAdmin = clubId != null && current.inClub(actor.getId(), clubId) && clubs.lockById(clubId).isPresent()
                && memberships.roleOf(clubId, actor.getId()).filter(role -> role == Membership.Role.ADMIN).isPresent();
        lockTarget(id);
        ImageRecord image = images.lockById(id).orElseThrow(ImageController::imageNotFound);
        // The picture may have moved to another club before its lock: only the locked club's role counts.
        Set<UUID> adminClubs = clubAdmin ? Set.of(clubId) : Set.of();
        if (!ImageRecord.deletableBy(image.getUploaderId(), image.getClubId(), actor.getId(), adminClubs, locked.isSiteAdmin())) {
            if (image.visibleTo(actor.getId(), current.clubIds(actor.getId())))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, message);
            throw imageNotFound();
        }
        return new Managed(image, locked.isSiteAdmin());
    }

    /**
     * A signed-in user reports a picture they can see, once: a repeated report keeps the first one and answers
     * 204 again. The uploader deletes instead (400). Locks account -> target -> image like other image writes.
     */
    @PostMapping("/images/{id}/reports") @ResponseStatus(HttpStatus.NO_CONTENT)
    void reportImage(@PathVariable UUID id, @Valid @RequestBody ImageReportInput body, Authentication auth) {
        AppUser actor = current.user(auth);
        String reason = Text.optional(body.reason(), Text.Kind.MULTI_LINE);
        transactions.executeWithoutResult(status -> {
            AppUser locked = current.lockUser(actor.getId());
            lockTarget(id);
            ImageRecord image = images.lockById(id).orElseThrow(ImageController::imageNotFound);
            if (!image.visibleTo(actor.getId(), current.clubIds(actor.getId()))) throw imageNotFound();
            if (image.uploadedBy(actor.getId())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, OWN_PHOTO_REPORT);
            if (!reports.existsByImage_IdAndReporter_Id(id, actor.getId()))
                reports.save(new ImageReport(image, locked, reason));
        });
    }

    @PostMapping(path="/combinations/{id}/images", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    ImageView upload(@PathVariable UUID id, @RequestPart("file") MultipartFile file,
            @RequestParam ImageVisibility visibility, @RequestParam @Parameter(schema = @Schema(type = "string", allowableValues = {"TEST_TILE", "FINISHED", "IN_PROGRESS"})) ImageKind kind,
            @RequestParam(required=false) UUID clubId, @RequestParam(required=false) String caption,
            @RequestParam(required=false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate firedOn,
            @RequestParam(required=false) @Parameter(description = "Where covers centre the photo, percent of its width (0 to 100, with focusY)") Integer focusX,
            @RequestParam(required=false) @Parameter(description = "Percent of its height (0 to 100, with focusX)") Integer focusY,
            Authentication auth) throws IOException {
        AppUser user = current.user(auth);
        var owner = combinations.ownership(id).filter(found -> found.visibleTo(user.getId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, CombinationService.NOT_FOUND));
        preflightSettings(user, visibility, clubId);
        requireVisibleToAuthor(owner.getAuthorId(), user.getId(), visibility, clubId);
        requireKindTarget(kind, false);
        String normalCaption = PhotoDetails.caption(caption);
        LocalDate normalFiredOn = PhotoDetails.firedOn(firedOn);
        PhotoDetails.Focus focus = PhotoDetails.focus(focusX, focusY);
        Encoded encoded = ImageEncoder.encode(file);
        return store(user, encoded, key -> {
            Club club = settings(user, visibility, clubId);
            Combination combination = combinations.lockById(id)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, CombinationService.NOT_FOUND));
            // Repeated under the destination club lock: a leave or removal from that club takes the same lock, so
            // the author's membership read here is fresh (see requireVisibleToAuthor).
            requireVisibleToAuthor(combination.getCreatedBy() == null ? null : combination.getCreatedBy().getId(),
                    user.getId(), visibility, clubId);
            ImageRecord image = ImageRecord.ofCombination(combination, user, club, visibility, kind,
                    key, encoded.width(), encoded.height());
            image.changeDetails(normalCaption, normalFiredOn);
            image.changeFocus(focus);
            return image;
        });
    }
    @PostMapping(path="/glazes/{id}/images", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    ImageView uploadGlazeImage(@PathVariable UUID id, @RequestPart("file") MultipartFile file,
            @RequestParam ImageVisibility visibility,
            @RequestParam @Parameter(schema = @Schema(type = "string", allowableValues = {"TEST_TILE", "FINISHED", "PACKAGING"})) ImageKind kind,
            @RequestParam(required=false) UUID clubId, @RequestParam(required=false) String caption,
            @RequestParam(required=false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate firedOn,
            @RequestParam(required=false) @Parameter(description = "Where covers centre the photo, percent of its width (0 to 100, with focusY)") Integer focusX,
            @RequestParam(required=false) @Parameter(description = "Percent of its height (0 to 100, with focusX)") Integer focusY,
            Authentication auth) throws IOException {
        AppUser user = current.user(auth);
        if (!glazes.existsById(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, GLAZE_NOT_FOUND);
        preflightSettings(user, visibility, clubId);
        requireKindTarget(kind, true);
        String normalCaption = PhotoDetails.caption(caption);
        LocalDate normalFiredOn = PhotoDetails.firedOn(firedOn);
        PhotoDetails.Focus focus = PhotoDetails.focus(focusX, focusY);
        Encoded encoded = ImageEncoder.encode(file);
        return store(user, encoded, key -> {
            Club club = settings(user, visibility, clubId);
            Glaze glaze = glazes.lockById(id)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, GLAZE_NOT_FOUND));
            ImageRecord image = ImageRecord.ofGlaze(glaze, user, club, visibility, kind,
                    key, encoded.width(), encoded.height());
            image.changeDetails(normalCaption, normalFiredOn);
            image.changeFocus(focus);
            return image;
        });
    }
    /** Pictures of one glaze that the caller may see, newest first. */
    @GetMapping("/glazes/{id}/images") @Transactional(readOnly = true)
    List<ImageView> glazeImages(@PathVariable UUID id, Authentication auth) {
        if (!glazes.existsById(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, GLAZE_NOT_FOUND);
        return ImageView.visibleViews(images.findByGlaze_IdOrderByCreatedAtDesc(id), current.viewer(auth));
    }

    /**
     * The uploader changes who may see a picture, in any direction; ImageRecord.visibleTo keeps deciding
     * every listing and download. Leaving PUBLIC moves the picture to a new ID, because browsers and the
     * Nginx image cache may keep the old public URL for a year: the old ID then answers 404 and the new one
     * is never cached outside the browser. The stored object is shared, so nothing in storage changes and
     * a failure rolls back completely. Other changes keep the ID, as nothing outside the browser has cached it.
     * Others get 403 for pictures they can see and 404 otherwise, so private pictures stay unrevealed.
     */
    @PutMapping("/images/{id}/visibility") @Transactional
    ImageView changeVisibility(@PathVariable UUID id, @Valid @RequestBody ImageVisibilityEdit body, Authentication auth) {
        AppUser user = current.user(auth);
        current.lockUser(user.getId());
        requireOwner(id, user, OWNER_REQUIRED);
        // Destination club before target/image (including a PUBLIC ID rotation's new FK).
        if (body.visibility() == ImageVisibility.CLUB) lockClub(user, body.clubId());
        ImageRecord image = lockOwnedImage(id, user, OWNER_REQUIRED);
        Club club = settings(user, body.visibility(), body.clubId());
        if (image.getCombinationId() != null)
            requireVisibleToAuthor(combinations.ownership(image.getCombinationId()).map(CombinationRepository.Ownership::getAuthorId)
                    .orElse(null), user.getId(), body.visibility(), body.clubId());
        // The union of who saw the photo before and who sees it after (a PUBLIC side reaches everyone; a draft's
        // photo stays effectively PRIVATE until publication).
        changes.picture(image);
        changes.picture(image.getGlazeId(), image.getCombinationId(), ImageRecord.effective(body.visibility(), image.onDraft()),
                club == null ? null : club.getId(), user.getId());
        if (image.getVisibility() == ImageVisibility.PUBLIC && body.visibility() != ImageVisibility.PUBLIC) {
            ImageRecord moved = image.movedTo(body.visibility(), club);
            // Reports follow the picture, so leaving PUBLIC never clears the site admins' queue.
            var carried = reports.carriedOf(id);
            images.delete(image);
            images.flush(); // the storage key is unique: remove the old row before inserting its successor
            moved = images.saveAndFlush(moved);
            for (var report : carried)
                reports.save(new ImageReport(report.getId(), moved, report.getReporterId() == null ? null
                        : users.getReferenceById(report.getReporterId()), report.getReason(), report.getCreatedAt()));
            return ImageView.forUploader(moved, user.getId());
        }
        image.changeVisibility(body.visibility(), club);
        return ImageView.forUploader(image, user.getId());
    }

    /**
     * The uploader edits a picture's kind, caption and firing date; only the fields present change (see
     * {@code ImageDetailsEdit}). These are metadata: the ID, URL, bytes, creation time and visibility stay, so
     * cached bytes remain valid. The request is validated after the uploader check, so a hidden picture
     * answers 404 whatever the body says.
     */
    @PatchMapping("/images/{id}") @Transactional
    ImageView editImageDetails(@PathVariable UUID id, @Valid @RequestBody ImageDetailsEdit body, Authentication auth) {
        AppUser user = current.user(auth);
        current.lockUser(user.getId());
        requireOwner(id, user, DETAILS_OWNER_REQUIRED);
        ImageRecord image = lockOwnedImage(id, user, DETAILS_OWNER_REQUIRED);
        ImageKind kind = body.kind() == null ? image.getKind() : body.kind();
        String caption = body.caption() == null ? image.getCaption() : PhotoDetails.caption(body.caption().orElse(null));
        LocalDate firedOn = body.firedOn() == null ? image.getFiredOn() : PhotoDetails.firedOn(body.firedOn().orElse(null));
        requireKindTarget(kind, image.getGlazeId() != null);
        image.changeKind(kind);
        image.changeDetails(caption, firedOn);
        changes.picture(image);
        return ImageView.forUploader(image, user.getId());
    }

    private static void requireKindTarget(ImageKind kind, boolean glaze) {
        if (!kind.allowedOn(glaze))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, glaze ? IN_PROGRESS_COMBINATION_ONLY : PACKAGING_GLAZE_ONLY);
    }

    /** Scalar pre-check before locking: the uploader passes, others get 403 if they can see the image, else 404. */
    private void requireOwner(UUID id, AppUser user, String message) {
        var target = images.target(id).orElseThrow(ImageController::imageNotFound);
        requireUploader(target.getVisibility(), target.getUploaderId(), target.getClubId(), user, message);
    }

    private ImageRecord lockOwnedImage(UUID id, AppUser user, String message) {
        lockTarget(id);
        ImageRecord image = images.lockById(id).orElseThrow(ImageController::imageNotFound);
        requireUploader(image.effectiveVisibility(), image.getUploadedBy() == null ? null : image.getUploadedBy().getId(),
                image.getClubId(), user, message);
        return image;
    }

    /** Uploader-only: 403 with {@code message} for a visible image, 404 for a hidden one, never revealing it. */
    private void requireUploader(ImageVisibility visibility, UUID uploaderId, UUID clubId, AppUser user, String message) {
        UUID userId = user.getId();
        if (userId.equals(uploaderId)) return;
        if (ImageRecord.visibleTo(visibility, uploaderId, clubId, userId, current.clubIds(userId)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, message);
        throw imageNotFound();
    }

    private static ResponseStatusException imageNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, IMAGE_NOT_FOUND);
    }

    private void preflightSettings(AppUser user, ImageVisibility visibility, UUID clubId) {
        if (visibility == ImageVisibility.CLUB) {
            if (clubId == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, CHOOSE_CLUB);
            current.requireClub(user.getId(), clubId);
        }
    }

    /**
     * A photo on someone else's combination must stay visible to the combination's author (ImageRecord.visibleTo:
     * PUBLIC, or CLUB of a club the author is in), so it never locks steps the author cannot see. No author (a
     * deleted account) or the author's own photo: every option. Only CLUB depends on the author's clubs, and only on
     * the destination club: the binding call runs after that club's lock (upload, visibility change), and leaving or
     * removal from a club deletes the membership under the same lock, so the read is fresh. A leave that commits
     * first refuses the photo; one that follows hides it from the author, who then neither sees it nor is locked by it
     * ({@code CombinationService.hasForeignPhotos}). The call before encoding is only an early refusal.
     */
    private void requireVisibleToAuthor(UUID authorId, UUID uploaderId, ImageVisibility visibility, UUID clubId) {
        if (authorId == null || authorId.equals(uploaderId)) return;
        if (!ImageRecord.visibleTo(visibility, uploaderId, clubId, authorId, current.clubIds(authorId)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, AUTHOR_MUST_SEE);
    }

    private Club lockClub(AppUser user, UUID clubId) {
        if (clubId == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, CHOOSE_CLUB);
        // An early refusal before the lock (repeated under it): an outsider neither waits for nor holds the club.
        current.requireClub(user.getId(), clubId);
        Club club = clubs.lockById(clubId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, CurrentUser.CLUB_MEMBERSHIP_REQUIRED));
        current.requireClub(user.getId(), clubId);
        return club;
    }

    private Club settings(AppUser user, ImageVisibility visibility, UUID clubId) {
        return visibility == ImageVisibility.CLUB ? lockClub(user, clubId) : null;
    }

    /** Scalar lookup only; the subsequent locked row must be fresh, never a preloaded image. */
    private void lockTarget(UUID id) {
        var target = images.target(id)
                .orElseThrow(ImageController::imageNotFound);
        if (target.getCombinationId() != null) combinations.lockById(target.getCombinationId())
                .orElseThrow(ImageController::imageNotFound);
        else glazes.lockById(target.getGlazeId())
                .orElseThrow(ImageController::imageNotFound);
    }

    /**
     * Stores both encoded objects (full and card size) and answers with the picture as its uploader sees it, so
     * clients need not reload. A failed second write or failed DB work removes what was written.
     */
    private ImageView store(AppUser uploader, Encoded encoded, Function<String, ImageRecord> record) throws IOException {
        return storeObjects(storage, encoded, key -> transactions.execute(status -> {
            current.lockUser(uploader.getId());
            ImageRecord image = images.saveAndFlush(record.apply(key));
            changes.picture(image);
            return ImageView.forUploader(image, uploader.getId());
        }));
    }

    /**
     * Writes both objects of an encoded picture under a new key ({@code <uuid>.jpg} and its card key) outside any DB
     * lock, then runs {@code record} with the key (its own transaction). A failed write or a failed {@code record}
     * removes both objects again. Photos and profile pictures share it.
     */
    static <T> T storeObjects(ImageStorage storage, Encoded encoded, Function<String, T> record) throws IOException {
        return storeObjects(storage, List.of(encoded), keys -> record.apply(keys.getFirst()));
    }

    /** {@link #storeObjects(ImageStorage, Encoded, Function)} for several pictures at once (feedback): all or none stay. */
    static <T> T storeObjects(ImageStorage storage, List<Encoded> encoded, Function<List<String>, T> record) throws IOException {
        List<String> keys = encoded.stream().map(picture -> UUID.randomUUID() + ".jpg").toList();
        try {
            for (int i = 0; i < keys.size(); i++) {
                storage.put(keys.get(i), encoded.get(i).jpeg());
                storage.put(ImageStorage.cardKey(keys.get(i)), encoded.get(i).card());
            }
            return record.apply(keys);
        } catch (IOException | RuntimeException error) {
            for (String key : keys)
                for (String written : List.of(key, ImageStorage.cardKey(key))) {
                    try { storage.delete(written); }
                    catch (IOException cleanup) { log.warn("Could not delete failed upload {}", written); }
                }
            throw error;
        }
    }

    /** The full-size picture (at most {@code ImageEncoder.MAX_SIDE} px), for the detail gallery. */
    @GetMapping("/images/{id}")
    ResponseEntity<byte[]> view(@PathVariable UUID id, Authentication auth) throws IOException {
        return download(id, auth, false);
    }

    /**
     * The card-size picture (at most {@code ImageEncoder.CARD_SIDE} px) for covers and thumbnails, under exactly
     * the visibility, 404 and cache rules of the full size. A picture stored before card copies existed answers
     * with its full-size bytes.
     */
    @GetMapping("/images/{id}/card")
    ResponseEntity<byte[]> viewCard(@PathVariable UUID id, Authentication auth) throws IOException {
        return download(id, auth, true);
    }

    /** What a download needs from the record, read in a short transaction that ends before the object store is read. */
    private record Download(String key, ImageVisibility visibility, CacheControl cache) {}

    private ResponseEntity<byte[]> download(UUID id, Authentication auth, boolean card) throws IOException {
        Viewer viewer = current.viewer(auth);
        Download found = transactions.execute(status -> images.findById(id)
                .filter(image -> {
                    boolean visible = image.visibleTo(viewer.userId(), viewer.clubIds());
                    // A picture that exists but is hidden from this viewer never counts towards the tarpit.
                    if (!visible) RateLimitFilter.hiddenPicture();
                    return visible;
                })
                .map(image -> new Download(image.getStorageKey(), image.effectiveVisibility(), cacheControl(image)))
                .orElse(null));
        if (found == null) throw imageNotFound();
        // A record whose stored object is gone answers like an unknown image, for every storage adapter; the card URL
        // of a picture uploaded before card copies were stored answers with the full size. Nginx stores a PUBLIC answer
        // for at most 60 s; 0 forbids it to store any other (besides "private" and the configs' own refusal).
        String[] keys = card ? new String[] {ImageStorage.cardKey(found.key()), found.key()} : new String[] {found.key()};
        return Pictures.answer(storage, found.cache(), found.visibility() == ImageVisibility.PUBLIC,
                ImageController::imageNotFound, keys);
    }

    /**
     * The bytes behind an image ID never change (a storage key is never rewritten), so every successful download is
     * {@code immutable}. PUBLIC images are visible to everyone: browsers and shared caches (the Nginx image cache, capped
     * at 60 s by {@code X-Accel-Expires}) may keep them for a year, and leaving PUBLIC gives the image a new ID and URL
     * (see {@code ImageRecord.movedTo}). CLUB and PRIVATE images depend on the session: only the viewer's own browser may
     * keep them, for {@link #BROWSER_ONLY_DAYS} days (a product decision: a lost access, deletion or visibility change
     * reaches a copy already in that browser within this window; after sign-out and account deletion the app asks
     * {@code GET /api/auth/clear-cache}, whose {@code Clear-Site-Data} clears it, see {@code AuthController.clearBrowserCache}). Error answers keep Spring Security's {@code no-store}.
     * A draft's photo is effectively PRIVATE, so it never enters a shared cache and may keep its ID when publishing makes it PUBLIC.
     */
    static CacheControl cacheControl(ImageRecord image) {
        return image.effectiveVisibility() == ImageVisibility.PUBLIC
                ? CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable() : browserOnly();
    }

    /** The viewer's own browser only, for {@link #BROWSER_ONLY_DAYS} days: CLUB and PRIVATE photos and profile pictures. */
    static CacheControl browserOnly() {
        return CacheControl.maxAge(Duration.ofDays(BROWSER_ONLY_DAYS)).cachePrivate().immutable();
    }
    static final int BROWSER_ONLY_DAYS = 7;
}
