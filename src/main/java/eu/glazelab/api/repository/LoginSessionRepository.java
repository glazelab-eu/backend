package eu.glazelab.api.repository;

import eu.glazelab.api.model.LoginSession;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

/** Bulk statements by key: none loads a row first, so none waits for more than the rows it changes. */
public interface LoginSessionRepository extends JpaRepository<LoginSession, String> {
    /** Moves the renewal time only if it is still before {@code due}: of concurrent requests, one renews. */
    @Transactional @Modifying
    @Query("update LoginSession s set s.renewedAt = :now where s.tokenHash = :hash and s.renewedAt < :due")
    int renew(String hash, Instant now, Instant due);
    @Transactional @Modifying
    @Query("delete from LoginSession s where s.tokenHash = :hash")
    int end(String hash);
    /** Every sign-in of the account; called under the account lock (the actor account comes first in the lock order). */
    @Transactional @Modifying
    @Query("delete from LoginSession s where s.userId = :userId")
    int endAll(UUID userId);
    @Transactional @Modifying
    @Query("delete from LoginSession s where s.renewedAt < :idleBefore or s.createdAt < :startedBefore")
    int purge(Instant idleBefore, Instant startedBefore);
}
