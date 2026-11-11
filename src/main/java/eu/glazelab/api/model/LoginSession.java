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

/** One signed-in browser (V29; {@code security.LoginSessions}): the hash of its cookie token, never the token. */
@Entity @Table(name = LoginSession.TABLE)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class LoginSession {
    static final String TABLE = "login_session";
    @Id @EqualsAndHashCode.Include @ToString.Exclude @Column(name = "token_hash", length = 64) private String tokenHash;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "session_version", nullable = false) private int sessionVersion;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "renewed_at", nullable = false) private Instant renewedAt;
    public LoginSession(String tokenHash, UUID userId, int sessionVersion, Instant now) {
        this.tokenHash = tokenHash; this.userId = userId; this.sessionVersion = sessionVersion;
        createdAt = now; renewedAt = now;
    }
}
