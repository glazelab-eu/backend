package eu.glazelab.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * One user's report of a photo (V24). The database removes it with its photo (ON DELETE CASCADE) and nulls a
 * deleted reporter (ON DELETE SET NULL); at most one report per photo and reporter.
 */
@Entity @Table(name = "image_report")
@NoArgsConstructor(access = AccessLevel.PROTECTED) @Getter
@ToString @EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ImageReport {
    public static final int MAX_REASON = 500;
    @Id @EqualsAndHashCode.Include private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "image_id", nullable = false) @ToString.Exclude private ImageRecord image;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reporter_id") @ToString.Exclude private AppUser reporter;
    @Column(length = MAX_REASON) private String reason;
    @Column(nullable = false) private Instant createdAt;

    public ImageReport(ImageRecord image, AppUser reporter, String reason) {
        this(UUID.randomUUID(), image, reporter, reason, Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    /** The same report for the successor of a photo that left PUBLIC (see {@link ImageRecord#movedTo}). */
    public ImageReport(UUID id, ImageRecord image, AppUser reporter, String reason, Instant createdAt) {
        this.id = id;
        this.image = image;
        this.reporter = reporter;
        this.reason = reason;
        this.createdAt = createdAt;
    }
}
