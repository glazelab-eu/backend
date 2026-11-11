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
 * An account's buddy link (V30): the SHA-256 hash of the token its owner shares, never the token. One per account;
 * {@link #replace} overwrites the hash, so the previous link answers like an unknown one from then on.
 */
@Entity @Table(name = BuddyLink.TABLE)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class BuddyLink {
    static final String TABLE = "buddy_link";
    @Id @EqualsAndHashCode.Include @Column(name = "user_id") private UUID userId;
    @ToString.Exclude @Column(name = "token_hash", nullable = false, length = 64) private String tokenHash;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    public BuddyLink(UUID userId, String tokenHash, Instant now) {
        this.userId = userId;
        replace(tokenHash, now);
    }

    public void replace(String tokenHash, Instant now) {
        this.tokenHash = tokenHash;
        this.createdAt = now;
    }
}
