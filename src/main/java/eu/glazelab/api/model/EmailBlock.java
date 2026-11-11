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
 * A banned address (V31, minimised by V32; backend guide "Bans"): only the keyed hash of its
 * {@link EmailAddresses#canonical} form (a pseudonymous fingerprint: it cannot be turned back into the address), never
 * the address, with the ban date and the short reason. No name and no admin: the admin log names those for 90 days.
 */
@Entity @Table(name = "email_block")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@ToString(exclude = "emailHash")
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class EmailBlock {
    @Id @EqualsAndHashCode.Include private UUID id;
    @Column(name = "email_hash", nullable = false, length = 64, updatable = false) private String emailHash;
    @Column(nullable = false, length = 500, updatable = false) private String reason;
    @Column(name = "banned_at", nullable = false, updatable = false) private Instant bannedAt;

    public EmailBlock(String emailHash, String reason, Instant now) {
        this.id = UUID.randomUUID();
        this.emailHash = emailHash;
        this.reason = reason;
        this.bannedAt = now;
    }
}
