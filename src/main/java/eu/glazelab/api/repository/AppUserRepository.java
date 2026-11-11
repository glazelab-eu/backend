package eu.glazelab.api.repository;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import eu.glazelab.api.model.AppUser;

import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {
    @Query("select u.id from AppUser u where u.emailVerified = false"
            + " and (u.verificationExpiresAt is null or u.verificationExpiresAt <= :now) order by u.id")
    List<UUID> expiredUnverifiedIds(LocalDateTime now, Pageable page);
    /** By {@code EmailAddresses.key} of the address: one statement on the unique key (V37). */
    Optional<AppUser> findByEmailKey(String emailKey);
    /** Never waits for a lock: a mail task may hold the row while it issues a link (registration timing). */
    boolean existsByEmailKey(String emailKey);
    @Query("select u.id from AppUser u where u.emailKey = :emailKey and u.emailVerified = false"
            + " and (u.verificationExpiresAt is null or u.verificationExpiresAt <= :now)")
    Optional<UUID> expiredUnverifiedIdByEmailKey(String emailKey, LocalDateTime now);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from AppUser u where u.emailKey = :emailKey")
    Optional<AppUser> lockByEmailKey(String emailKey);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from AppUser u where u.id = :id")
    Optional<AppUser> lockById(UUID id);

    /**
     * Moves the account's activity-feed seen position forward to {@code seen} (never back): one conditional statement,
     * the account row its only lock, so two devices never undo each other.
     */
    @Modifying @Query(nativeQuery = true, value = "UPDATE app_user SET feed_seen_at = :seen WHERE id = :id"
            + " AND (feed_seen_at IS NULL OR feed_seen_at < :seen)")
    int markFeedSeen(UUID id, Instant seen);

    /** One row of the site admins' account list: email but no hash or session data is selected. */
    interface AdminRow { UUID getId(); String getDisplayName(); String getEmail(); boolean getEmailVerified(); boolean getSiteAdmin(); Instant getCreatedAt(); }
    /** A slice (no count query), one statement per page. */
    @Query("select u.id as id, u.displayName as displayName, u.email as email, u.emailVerified as emailVerified, u.siteAdmin as siteAdmin,"
            + " u.createdAt as createdAt from AppUser u where lower(u.displayName) like :pattern escape '\\' or lower(u.email) like :pattern escape '\\'"
            + " order by lower(u.displayName), u.id")
    Slice<AdminRow> adminList(String pattern, Pageable page);
}
