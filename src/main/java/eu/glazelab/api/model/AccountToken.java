package eu.glazelab.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Entity @Table(name = AccountToken.TABLE)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class AccountToken {
    static final String TABLE = "account_token";
    static final String USER_ID = "user_id";
    static final String TOKEN_HASH = "token_hash";
    static final String ISSUED_AT = "issued_at";
    static final String EXPIRES_AT = "expires_at";
    public enum Purpose { RESET_PASSWORD, VERIFY_EMAIL, MAGIC_LOGIN }
    @Id @EqualsAndHashCode.Include @Getter(AccessLevel.NONE) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = USER_ID, nullable = false) @ToString.Exclude private AppUser user;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Purpose purpose;
    @Column(name = TOKEN_HASH, nullable = false, length = 64) @ToString.Exclude private String tokenHash;
    @Column(name = ISSUED_AT, nullable = false) private LocalDateTime issuedAt;
    @Column(name = EXPIRES_AT, nullable = false) private LocalDateTime expiresAt;
    public AccountToken(AppUser user, Purpose purpose) {
        this.id = UUID.randomUUID(); this.user = user; this.purpose = purpose;
    }
    public void renew(String hash, LocalDateTime now) {
        tokenHash = hash; issuedAt = now;
        expiresAt = switch (purpose) {
            case RESET_PASSWORD -> now.plusMinutes(30);
            case VERIFY_EMAIL -> now.plusHours(24);
            case MAGIC_LOGIN -> now.plusMinutes(15);
        };
    }
    /** What a renewal replaced, so a failed send can put it back. */
    public record Previous(String tokenHash, LocalDateTime issuedAt, LocalDateTime expiresAt) {}
    public Previous previous() { return new Previous(tokenHash, issuedAt, expiresAt); }
    public void restore(Previous previous) {
        tokenHash = previous.tokenHash(); issuedAt = previous.issuedAt(); expiresAt = previous.expiresAt();
    }
}
