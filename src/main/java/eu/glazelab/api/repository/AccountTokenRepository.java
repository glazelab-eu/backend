package eu.glazelab.api.repository;

import eu.glazelab.api.model.AccountToken;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface AccountTokenRepository extends JpaRepository<AccountToken, UUID> {
    @Query("select t.user.id from AccountToken t where t.tokenHash = :hash")
    Optional<UUID> findUserIdByTokenHash(String hash);
    Optional<AccountToken> findByUser_IdAndPurpose(UUID userId, AccountToken.Purpose purpose);
    void deleteByUser_Id(UUID userId);
    /** Accounts with a token that no request can consume any more (expired at or before {@code now}). */
    @Query("select distinct t.user.id from AccountToken t where t.expiresAt <= :now")
    List<UUID> expiredUserIds(LocalDateTime now, Pageable page);
    @Modifying @Query("delete from AccountToken t where t.user.id = :userId and t.expiresAt <= :now")
    int deleteExpired(UUID userId, LocalDateTime now);
}
