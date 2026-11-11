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
 * A profile picture a site admin removed, as hashes only (V31; backend guide "Profile pictures"): no account and no
 * picture ID, kept without expiry. An upload that {@link AvatarFingerprint#matches} it is refused for every account.
 */
@Entity @Table(name = "avatar_block")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class AvatarBlock {
    @Id @EqualsAndHashCode.Include private UUID id;
    @Column(name = "upload_sha256", nullable = false, length = 64) private String uploadSha256;
    @Column(name = "stored_sha256", nullable = false, length = 64) private String storedSha256;
    @Column(nullable = false) private long dhash;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    public AvatarBlock(AvatarFingerprint fingerprint, Instant now) {
        this.id = UUID.randomUUID();
        this.uploadSha256 = fingerprint.uploadSha256();
        this.storedSha256 = fingerprint.storedSha256();
        this.dhash = fingerprint.dhash();
        this.createdAt = now;
    }
}
