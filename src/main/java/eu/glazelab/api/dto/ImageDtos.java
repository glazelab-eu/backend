package eu.glazelab.api.dto;

import eu.glazelab.api.model.Text.Plain;
import static eu.glazelab.api.model.Text.Kind.MULTI_LINE;

import eu.glazelab.api.model.ImageDeletion;
import eu.glazelab.api.model.ImageKind;
import eu.glazelab.api.model.ImageRecord;
import eu.glazelab.api.model.ImageReport;
import eu.glazelab.api.model.ImageVisibility;
import eu.glazelab.api.security.CurrentUser.Viewer;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Image metadata shared by combination and glaze responses (the URL is checked again on download), and the deletion log and report queue. */
public final class ImageDtos {
    private ImageDtos() {}
    /**
     * One picture as the caller sees it. {@code own} marks pictures the caller uploaded (only they may change
     * the visibility); {@code clubId} names the club of a CLUB picture; {@code uploader} is the uploader's
     * display name, null once that account was deleted. {@code canDelete} applies {@code ImageRecord.deletableBy}
     * for this viewer (uploader, an admin of the picture's club, site admins). Only pictures the viewer may see
     * are described. {@code caption} and {@code firedOn} are the uploader's optional details; they ride on the
     * picture, so whoever may see it sees them. {@code url} is the full size (detail gallery), {@code cardUrl} the
     * card size for covers and thumbnails; both follow the same visibility and cache rules, and {@code width}
     * and {@code height} are the full size's (the card keeps its aspect ratio). {@code focusX} and {@code focusY} are where
     * a cover cropping the photo centres, in percent of its width and height; both null for the centre.
     */
    public record ImageView(UUID id,
            ImageVisibility visibility, ImageKind kind,
            String url, String cardUrl, int width, int height,
            @Schema(nullable = true) UUID clubId, boolean own,
            @Schema(nullable = true) String uploader, boolean canDelete,
            @Schema(nullable = true, maxLength = 200) String caption, @Schema(nullable = true) LocalDate firedOn,
            @Schema(nullable = true, minimum = "0", maximum = "100") Integer focusX,
            @Schema(nullable = true, minimum = "0", maximum = "100") Integer focusY) {
        /** The picture for this viewer. */
        public static ImageView of(ImageRecord image, Viewer viewer) {
            return new ImageView(image.getId(), image.getVisibility(), image.getKind(),
                    "/api/images/" + image.getId(), "/api/images/" + image.getId() + "/card", image.getWidth(), image.getHeight(),
                    image.getClubId(), image.uploadedBy(viewer.userId()),
                    image.getUploadedBy() == null ? null : image.getUploadedBy().getDisplayName(),
                    ImageRecord.deletableBy(image.getUploaderId(), image.getClubId(), viewer.userId(),
                            viewer.adminClubIds(), viewer.siteAdmin()),
                    image.getCaption(), image.getFiredOn(), image.getFocusX(), image.getFocusY());
        }
        /** The picture as its uploader sees it, who may always delete it (upload and edit answers). */
        public static ImageView forUploader(ImageRecord image, UUID uploaderId) {
            return of(image, new Viewer(uploaderId, Set.of(), Set.of(), false));
        }
        /** Keeps source order and applies the same visibility rule to every metadata listing. */
        public static List<ImageView> visibleViews(Collection<ImageRecord> images, Viewer viewer) {
            return images.stream().filter(image -> image.visibleTo(viewer.userId(), viewer.clubIds()))
                    .map(image -> of(image, viewer)).toList();
        }
    }
    /**
     * Only the fields present are changed. {@code kind}: absent (or null) keeps it. {@code caption} and
     * {@code firedOn}: absent keeps the stored value, null or a blank caption clears it (Jackson reads a missing
     * {@code Optional} as null and an explicit null as empty, see {@code NewCombination}).
     */
    public record ImageDetailsEdit(@Schema(nullable = true) ImageKind kind,
            @Schema(nullable = true, maxLength = 200) Optional<@Plain String> caption,
            @Schema(nullable = true) Optional<LocalDate> firedOn) {}

    /** What a picture shows from now on (PUT /api/images/{id}/kind); PACKAGING only for a glaze's own picture. */
    public record ImageKindEdit(@NotNull ImageKind kind) {}

    /** Where covers centre the picture from now on (PUT /api/images/{id}/focus): both 0 to 100, or both null for the centre. */
    public record ImageFocusEdit(@Schema(nullable = true, minimum = "0", maximum = "100") Integer focusX,
            @Schema(nullable = true, minimum = "0", maximum = "100") Integer focusY) {}

    /** Who may see a picture from now on; {@code clubId} is required for CLUB and ignored otherwise. */
    public record ImageVisibilityEdit(
            @NotNull ImageVisibility visibility,
            @Schema(nullable = true) UUID clubId) {}

    public record ImageDeletionView(UUID imageId, @Schema(nullable = true) UUID glazeId,
            @Schema(nullable = true) UUID combinationId, String title, String visibility,
            @Schema(nullable = true) UUID uploaderId, @Schema(nullable = true) UUID deletedBy,
            Instant deletedAt) {
        public static ImageDeletionView of(ImageDeletion row) {
            return new ImageDeletionView(row.getImageId(), row.getGlazeId(), row.getCombinationId(),
                    row.getTitle(), row.getVisibility(), row.getUploaderId(), row.getDeletedBy(), row.getDeletedAt());
        }
    }
    public record ImageDeletionPageView(List<ImageDeletionView> entries, boolean hasMore) {}

    /** A report of a photo the reporter can see; the reason is optional (blank means none). */
    public record ImageReportInput(@Schema(nullable = true) @Plain(MULTI_LINE) @Size(max = ImageReport.MAX_REASON) String reason) {}

    /**
     * One report in the site admins' queue: the photo's ID and target (exactly one of glaze and combination),
     * the target's current title (a glaze's name), the reporter's display name (null once that account was
     * deleted) and the reason, and the photo's uploader ({@code uploaderId}/{@code uploader} with the current display
     * name; null once that account was deleted), whom a site admin may ban (backend guide "Bans"). No photo URL: the
     * download keeps its own visibility rule.
     */
    public record ImageReportView(UUID id, UUID imageId, @Schema(nullable = true) UUID glazeId,
            @Schema(nullable = true) UUID combinationId, String title, ImageVisibility visibility,
            @Schema(nullable = true) String reporter, @Schema(nullable = true) String reason, Instant createdAt,
            @Schema(nullable = true) UUID uploaderId, @Schema(nullable = true) String uploader) {}
    public record ImageReportPageView(List<ImageReportView> entries, boolean hasMore) {}
}
