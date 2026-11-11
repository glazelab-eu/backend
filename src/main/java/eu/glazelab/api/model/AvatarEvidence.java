package eu.glazelab.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * A reported profile picture after it left its account (V31; backend guide "Profile pictures"): how it went, its
 * hashes and, while it is quarantined evidence, the key of its two objects (null once purged, deleted with the account
 * or never kept because no report was open). Never served but to site admins through the report picture endpoint.
 */
@Entity @Table(name = "avatar_evidence")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class AvatarEvidence {
    @Id @Column(name = "avatar_id") @EqualsAndHashCode.Include private UUID avatarId;
    @Column(name = "storage_key", unique = true, length = 120) private String storageKey;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private AvatarPictureState outcome;
    @Column(name = "upload_sha256", nullable = false, length = 64) private String uploadSha256;
    @Column(name = "stored_sha256", nullable = false, length = 64) private String storedSha256;
    @Column(nullable = false) private long dhash;
    @Column(name = "removed_at", nullable = false) private Instant removedAt;

    public AvatarEvidence(UserAvatar picture, AvatarPictureState outcome, boolean keep, Instant now) {
        if (outcome == AvatarPictureState.CURRENT) throw new IllegalArgumentException("A removed picture is not current");
        this.avatarId = picture.getId();
        this.storageKey = keep ? picture.getStorageKey() : null;
        this.outcome = outcome;
        this.uploadSha256 = picture.getUploadSha256();
        this.storedSha256 = picture.getStoredSha256();
        this.dhash = picture.getDhash();
        this.removedAt = now;
    }

    public AvatarFingerprint fingerprint() {
        return new AvatarFingerprint(uploadSha256, storedSha256, dhash);
    }
}
