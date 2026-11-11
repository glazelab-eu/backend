package eu.glazelab.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Entity @Table(name = AppUser.TABLE)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class AppUser {
    static final String TABLE = "app_user";
    static final String PASSWORD_HASH = "password_hash";
    static final String DISPLAY_NAME = "display_name";
    static final String EMAIL_VERIFIED = "email_verified";
    static final String SESSION_VERSION = "session_version";
    static final String ACCEPTED_TERMS_VERSION = "accepted_terms_version";
    static final String TERMS_ACCEPTED_AT = "terms_accepted_at";
    static final String SITE_ADMIN = "site_admin";
    static final String EMAIL_KEY = "email_key";
    @Id @EqualsAndHashCode.Include private UUID id;
    @Column(nullable = false, unique = true, length = 255) private String email;
    /** {@link EmailAddresses#key} of the address (V37, unique): every lookup by address uses it. */
    @Column(name = EMAIL_KEY, nullable = false, unique = true, length = 510, updatable = false)
    @Getter(AccessLevel.NONE) @ToString.Exclude private String emailKey;
    @Column(name = PASSWORD_HASH, nullable = false, length = 255) @ToString.Exclude private String passwordHash;
    @Column(name = DISPLAY_NAME, nullable = false, length = 100) private String displayName;

    @Column(name = EMAIL_VERIFIED, nullable = false) private boolean emailVerified = true;
    @Column(name = "verification_expires_at") private LocalDateTime verificationExpiresAt;
    public void awaitVerification(LocalDateTime expiresAt) {
        emailVerified = false;
        verificationExpiresAt = expiresAt;
    }
    public boolean verificationExpired(LocalDateTime now) {
        return !emailVerified && (verificationExpiresAt == null || !verificationExpiresAt.isAfter(now));
    }
    public void activate(String passwordHash, String displayName) {
        changePassword(passwordHash);
        this.displayName = displayName;
        emailVerified = true;
        verificationExpiresAt = null;
    }
    @Column(name = SESSION_VERSION, nullable = false) private int sessionVersion;
    public void changePassword(String hash) { this.passwordHash = hash; this.sessionVersion++; }

    @Column(name = ACCEPTED_TERMS_VERSION, length = 32) private String acceptedTermsVersion;
    @Column(name = TERMS_ACCEPTED_AT) private LocalDateTime termsAcceptedAt;
    public void acceptTerms(String version, LocalDateTime acceptedAt) {
        this.acceptedTermsVersion = version;
        this.termsAcceptedAt = acceptedAt;
    }

    /** Set only with SQL (V12, docs/guide/security.md); no endpoint changes it, so it is never self-assigned. */
    @Column(name = SITE_ADMIN, nullable = false, updatable = false) private boolean siteAdmin;

    /** Whether the account sends error reports (V32, backend guide "Error reports"): off unless the owner turns it on. */
    @Column(name = "diagnostics_opt_in", nullable = false) private boolean diagnosticsOptIn;
    public void chooseDiagnostics(boolean enabled) { this.diagnosticsOptIn = enabled; }

    /**
     * The activity feed (V34, backend guide "Activity feed"): the newest entry time the account has seen, null before
     * its first look. Written only by the conditional update {@code AppUserRepository.markFeedSeen} (it only moves
     * forward), never by a flush of this entity, so a stale copy of the row can never move it back.
     */
    @Column(name = "feed_seen_at", insertable = false, updatable = false) private Instant feedSeenAt;
    /** The feed's two sources, on by default: entries from buddies, entries from the account's clubs. */
    @Column(name = "feed_buddies", nullable = false) private boolean feedBuddies = true;
    @Column(name = "feed_clubs", nullable = false) private boolean feedClubs = true;
    public void chooseFeed(boolean buddies, boolean clubs) {
        this.feedBuddies = buddies;
        this.feedClubs = clubs;
    }

    /** When the account was registered (V33); null for accounts that existed before the column, never changed afterwards. */
    @Column(name = "created_at", updatable = false) private Instant createdAt;

    public AppUser(String email, String passwordHash, String displayName) {
        this.id = UUID.randomUUID();
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        this.email = email;
        this.emailKey = EmailAddresses.key(email);
        this.passwordHash = passwordHash;
        this.displayName = displayName;
    }
}
