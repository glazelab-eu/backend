package eu.glazelab.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * An account's profile picture (V31; backend guide "Profile pictures"): at most one per account, never a photo. Its ID
 * is part of its URL and is never reused: a replacement is a new row, so an old URL answers 404. The storage key
 * names the two objects like a photo's ({@code <uuid>.jpg} square, {@code ImageStorage.cardKey} the small copy); the
 * hashes ({@link AvatarFingerprint}) move to the evidence or a block when the picture is reported or removed by a site admin.
 */
@Entity @Table(name = "user_avatar")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class UserAvatar {
    @Id @EqualsAndHashCode.Include private UUID id;
    @Column(name = "user_id", nullable = false, unique = true) private UUID userId;
    @Column(name = "storage_key", nullable = false, unique = true, length = 120) private String storageKey;
    @Column(name = "upload_sha256", nullable = false, length = 64) private String uploadSha256;
    @Column(name = "stored_sha256", nullable = false, length = 64) private String storedSha256;
    @Column(nullable = false) private long dhash;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    public UserAvatar(UUID userId, String storageKey, AvatarFingerprint fingerprint, Instant now) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.storageKey = storageKey;
        this.uploadSha256 = fingerprint.uploadSha256();
        this.storedSha256 = fingerprint.storedSha256();
        this.dhash = fingerprint.dhash();
        this.createdAt = now;
    }

    public AvatarFingerprint fingerprint() {
        return new AvatarFingerprint(uploadSha256, storedSha256, dhash);
    }

    public boolean ownedBy(UUID accountId) {
        return userId.equals(accountId);
    }
}
