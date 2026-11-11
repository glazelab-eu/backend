package eu.glazelab.api.repository;

import eu.glazelab.api.model.UserAvatar;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface UserAvatarRepository extends JpaRepository<UserAvatar, UUID> {
    /** The account's current picture, locked (after the account row): replacements and account deletion. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from UserAvatar a where a.userId = :userId")
    Optional<UserAvatar> lockByUserId(UUID userId);

    /** The account's current picture as a scalar (null in views when it has none). */
    @Query("select a.id from UserAvatar a where a.userId = :userId")
    Optional<UUID> idOf(UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from UserAvatar a where a.id = :id")
    Optional<UserAvatar> lockById(UUID id);

    /** One picture by ID as a bulk delete (0 when another transaction removed it first); its reports stay. */
    @Modifying @Query("delete from UserAvatar a where a.id = :id")
    int deleteOne(UUID id);

    /** An account with a picture, as {@code GET /api/combinations/author-avatars} answers it. */
    interface Owned { UUID getUserId(); UUID getAvatarId(); }
    /**
     * The pictures of every account that authored a published combination: the authors any caller's listing can name
     * (published combinations are visible to everyone, a draft only to its author, who has their own picture in
     * {@code UserView}). One statement, whatever the number of authors.
     */
    @Query("select a.userId as userId, a.id as avatarId from UserAvatar a where exists (select 1 from Combination c"
            + " where c.createdBy.id = a.userId and c.publishedAt is not null)")
    List<Owned> ofAuthors();
}
