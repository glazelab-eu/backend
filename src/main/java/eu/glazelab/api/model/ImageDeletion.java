package eu.glazelab.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/** Metadata snapshots survive removal of the picture and its target. Account deletion must null user IDs. */
@Entity @Table(name = "image_deletion")
@NoArgsConstructor(access = AccessLevel.PROTECTED) @Getter
@ToString @EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ImageDeletion {
    @Id @EqualsAndHashCode.Include private UUID id;
    @Column(nullable = false) private UUID imageId;
    private UUID glazeId;
    private UUID combinationId;
    @Column(nullable = false, columnDefinition = "text") private String title;
    @Column(nullable = false, length = 16) private String visibility;
    private UUID uploaderId;
    private UUID deletedBy;
    @Column(nullable = false) private Instant deletedAt;

    public ImageDeletion(ImageRecord image, UUID actor) {
        id = UUID.randomUUID();
        imageId = image.getId();
        glazeId = image.getGlazeId();
        combinationId = image.getCombination() == null ? null : image.getCombination().getId();
        title = image.getGlaze() == null ? image.getCombination().getTitle() : image.getGlaze().getName();
        visibility = image.getVisibility().name();
        uploaderId = image.getUploadedBy() == null ? null : image.getUploadedBy().getId();
        deletedBy = actor;
        deletedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
