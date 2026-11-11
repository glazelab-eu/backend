package eu.glazelab.api.model;

import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Entity @Table(name = ImageRecord.TABLE, indexes = {
        @Index(name = "image_combo_idx", columnList = ImageRecord.COMBINATION_ID),
        @Index(name = "image_glaze_idx", columnList = ImageRecord.GLAZE_ID)}, check = {
        @CheckConstraint(name = "ck_image_single_target", constraint = "(" + ImageRecord.COMBINATION_ID
                + " IS NULL) <> (" + ImageRecord.GLAZE_ID + " IS NULL)"),
        @CheckConstraint(name = "ck_image_visibility", constraint = ImageRecord.VISIBILITY + " IN ('PUBLIC','CLUB','PRIVATE')"
                + " AND ((" + ImageRecord.VISIBILITY + " = 'CLUB' AND " + ImageRecord.CLUB_ID + " IS NOT NULL)"
                + " OR (" + ImageRecord.VISIBILITY + " <> 'CLUB' AND " + ImageRecord.CLUB_ID + " IS NULL))"),
        @CheckConstraint(name = "ck_image_kind", constraint = "kind IN ('TEST_TILE','FINISHED','PACKAGING','IN_PROGRESS')"),
        @CheckConstraint(name = "ck_image_packaging_target", constraint = "kind <> 'PACKAGING' OR glaze_id IS NOT NULL"),
        @CheckConstraint(name = "ck_image_in_progress_target", constraint = "kind <> 'IN_PROGRESS' OR combination_id IS NOT NULL"),
        @CheckConstraint(name = "ck_image_fired_on", constraint = "fired_on IS NULL OR fired_on BETWEEN DATE '1900-01-01' AND DATE '2100-12-31'"),
        @CheckConstraint(name = "ck_image_focus", constraint = "(focus_x IS NULL AND focus_y IS NULL) OR (focus_x IS NOT NULL AND focus_y IS NOT NULL"
                + " AND focus_x BETWEEN 0 AND 100 AND focus_y BETWEEN 0 AND 100)") })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ImageRecord {
    static final String TABLE = "image";
    static final String COMBINATION_ID = "combination_id";
    static final String GLAZE_ID = "glaze_id";
    static final String UPLOADED_BY = "uploaded_by";
    static final String CLUB_ID = "club_id";
    static final String VISIBILITY = "visibility";
    static final String KIND = "kind";
    static final String STORAGE_KEY = "storage_key";
    static final String CREATED_AT = "created_at";
    @Id @EqualsAndHashCode.Include @Getter private UUID id;
    /** Exactly one of combination and glaze is set. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = COMBINATION_ID) @ToString.Exclude @Getter private Combination combination;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = GLAZE_ID) @ToString.Exclude @Getter private Glaze glaze;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = UPLOADED_BY) @ToString.Exclude @Getter private AppUser uploadedBy;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = CLUB_ID) @ToString.Exclude @Getter private Club club;
    @Column(name = VISIBILITY, nullable = false, length = 16) @Enumerated(EnumType.STRING) @Getter private ImageVisibility visibility;
    @Column(name = KIND, nullable = false, length = 16) @Enumerated(EnumType.STRING) @Getter private ImageKind kind;
    @Column(name = STORAGE_KEY, nullable = false, unique = true, length = 120) @Getter private String storageKey;
    @Column(nullable = false) @Getter private int width;
    @Column(nullable = false) @Getter private int height;
    @Column(name = CREATED_AT, nullable = false) private Instant createdAt;
    /** Optional, up to {@link PhotoDetails#MAX_CAPTION} characters; set through {@link #changeDetails}. */
    @Column(length = PhotoDetails.MAX_CAPTION) @Getter private String caption;
    /** The date the piece was fired, optional and within {@link PhotoDetails}'s range. */
    @Column(name = "fired_on") @Getter private LocalDate firedOn;
    /** Where covers centre their crop, in percent of the width and height; both null for the centre (see {@link #changeFocus}). */
    @Column(name = "focus_x") @Getter private Integer focusX;
    @Column(name = "focus_y") @Getter private Integer focusY;

    private ImageRecord(Combination combination, Glaze glaze, AppUser uploadedBy, Club club,
            ImageVisibility visibility, ImageKind kind, String storageKey, int width, int height) {
        if ((combination == null) == (glaze == null))
            throw new IllegalArgumentException("An image belongs to exactly one combination or glaze");
        this.id = UUID.randomUUID();
        this.combination = combination;
        this.glaze = glaze;
        this.uploadedBy = uploadedBy;
        this.club = club;
        this.visibility = visibility;
        this.kind = kind;
        this.storageKey = storageKey;
        this.width = width;
        this.height = height;
    }
    public static ImageRecord ofCombination(Combination combination, AppUser uploadedBy, Club club,
            ImageVisibility visibility, ImageKind kind, String storageKey, int width, int height) {
        return new ImageRecord(combination, null, uploadedBy, club, visibility, kind, storageKey, width, height);
    }
    public static ImageRecord ofGlaze(Glaze glaze, AppUser uploadedBy, Club club,
            ImageVisibility visibility, ImageKind kind, String storageKey, int width, int height) {
        return new ImageRecord(null, glaze, uploadedBy, club, visibility, kind, storageKey, width, height);
    }
    /**
     * Changes who may see the picture, keeping its ID. Only for changes that never leave PUBLIC: a public
     * image's URL may sit in browser and proxy caches for a year, so leaving PUBLIC needs {@link #movedTo}.
     */
    public void changeVisibility(ImageVisibility visibility, Club club) {
        if (this.visibility == ImageVisibility.PUBLIC && visibility != ImageVisibility.PUBLIC)
            throw new IllegalStateException("A public image leaves PUBLIC only under a new ID");
        this.visibility = visibility;
        this.club = club;
    }
    /**
     * The same picture under a new ID (so a new URL) with the given visibility. It shares the stored object
     * and keeps its creation time, caption and firing date, so listings keep their order; the caller deletes
     * this record first.
     */
    public ImageRecord movedTo(ImageVisibility visibility, Club club) {
        ImageRecord moved = new ImageRecord(combination, glaze, uploadedBy, club, visibility, kind, storageKey, width, height);
        moved.createdAt = createdAt;
        moved.caption = caption;
        moved.firedOn = firedOn;
        moved.focusX = focusX;
        moved.focusY = focusY;
        return moved;
    }
    /** Kind is metadata only: the ID, storage key, bytes, creation time and visibility stay unchanged. */
    public void changeKind(ImageKind kind) { this.kind = kind; }
    /** Caption and firing date are metadata like the kind; the caller passes them through {@link PhotoDetails}. */
    public void changeDetails(String caption, LocalDate firedOn) {
        this.caption = caption;
        this.firedOn = firedOn;
    }

    /**
     * The focus point is metadata like the kind: the stored bytes stay whole (covers crop them in the browser around it).
     * Null is the centre; the caller passes it through {@link PhotoDetails#focus}. Answers whether it changed.
     */
    public boolean changeFocus(PhotoDetails.Focus focus) {
        Integer x = focus == null ? null : focus.x(), y = focus == null ? null : focus.y();
        if (java.util.Objects.equals(x, focusX) && java.util.Objects.equals(y, focusY)) return false;
        focusX = x;
        focusY = y;
        return true;
    }

    public boolean uploadedBy(UUID userId) { return userId != null && uploadedBy != null && userId.equals(uploadedBy.getId()); }
    /** The club for CLUB images, without loading it; null otherwise. */
    public UUID getClubId() { return club == null ? null : club.getId(); }
    /** Whether the picture belongs to a combination that is still a draft (loads the combination if needed). */
    public boolean onDraft() { return combination != null && combination.isDraft(); }
    /** The visibility that decides access, audience and caching now: see {@link #effective}. */
    public ImageVisibility effectiveVisibility() { return effective(visibility, onDraft()); }
    /**
     * A draft's photo is PRIVATE whatever visibility its author chose (stored in {@code visibility} and shown to
     * them): only the author can see a draft or upload to it, so nobody else learns of it, and it is never served
     * as shared-cacheable. Publishing flips {@code published_at} and with it every photo at once, under its ID.
     */
    public static ImageVisibility effective(ImageVisibility stored, boolean onDraft) {
        return onDraft ? ImageVisibility.PRIVATE : stored;
    }
    /**
     * The single visibility rule for listings and downloads: public images for everyone, club images
     * for current members of that club and for their uploader (who may have left it), private images for their uploader only;
     * applied to the {@link #effectiveVisibility}.
     */
    public boolean visibleTo(UUID userId, Set<UUID> clubIds) {
        return visibleTo(effectiveVisibility(), uploadedBy == null ? null : uploadedBy.getId(), getClubId(), userId, clubIds);
    }
    /**
     * The same rule on scalars, for the {@code ImageRecordRepository.Target} projection (pass its effective
     * {@code getVisibility()}); {@code userId} is null for guests.
     */
    public static boolean visibleTo(ImageVisibility visibility, UUID uploaderId, UUID clubId, UUID userId, Set<UUID> clubIds) {
        return switch (visibility) {
            case PUBLIC -> true;
            case PRIVATE -> userId != null && userId.equals(uploaderId);
            case CLUB -> userId != null && (userId.equals(uploaderId) || clubId != null && clubIds.contains(clubId));
        };
    }
    /**
     * The single deletion rule: the uploader, a site admin, or an ADMIN of the club of a CLUB picture
     * ({@code clubId} is set only for those); {@code userId} is null for guests, who may delete nothing.
     */
    public static boolean deletableBy(UUID uploaderId, UUID clubId, UUID userId, Set<UUID> adminClubIds, boolean siteAdmin) {
        return userId != null && (siteAdmin || userId.equals(uploaderId) || clubId != null && adminClubIds.contains(clubId));
    }
    public UUID getUploaderId() { return uploadedBy == null ? null : uploadedBy.getId(); }
    @PrePersist void setCreatedAt() { if (createdAt == null) createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS); }
    /** The glaze ID without loading the glaze; null for combination pictures. */
    public UUID getGlazeId() { return glaze == null ? null : glaze.getId(); }
    /** The combination ID without loading the combination; null for glaze pictures. */
    public UUID getCombinationId() { return combination == null ? null : combination.getId(); }
}
