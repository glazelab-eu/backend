package eu.glazelab.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * A photo of a {@link Feedback} (V35): two stored objects like every photo ({@code storageKey} at most 1600 px and its
 * card copy, {@code ImageStorage.cardKey}), re-encoded without metadata. Never a photo of the library: no kind, no
 * visibility, no listing but the site admins' feedback page.
 */
@Entity @Table(name = "feedback_photo")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class FeedbackPhoto {
    public static final int MAX_PHOTOS = 3;

    @Id @EqualsAndHashCode.Include private UUID id;
    @Column(name = "feedback_id", nullable = false) private UUID feedbackId;
    @Column(name = "photo_index", nullable = false) private int photoIndex;
    @Column(name = "storage_key", nullable = false, unique = true, length = 64) private String storageKey;
    @Column(nullable = false) private int width;
    @Column(nullable = false) private int height;

    public FeedbackPhoto(UUID feedbackId, int photoIndex, String storageKey, int width, int height) {
        this.id = UUID.randomUUID();
        this.feedbackId = feedbackId;
        this.photoIndex = photoIndex;
        this.storageKey = storageKey;
        this.width = width;
        this.height = height;
    }
}
