package eu.glazelab.api.repository;

import eu.glazelab.api.model.Buddy;
import eu.glazelab.api.model.BuddyId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface BuddyRepository extends JpaRepository<Buddy, BuddyId> {
    /** One row of {@link #of}: the other account of a pair the caller is in, with the pair's state. */
    interface Pair {
        UUID getId();
        String getDisplayName();
        Buddy.Status getStatus();
        UUID getRequestedBy();
        Instant getCreatedAt();
        Instant getAcceptedAt();
        /** The other account's profile picture, null without one. */
        UUID getAvatarId();
    }

    /** Every pair of {@code userId}, accepted and pending, with the other account's name and picture: one statement. */
    @Query("select u.id as id, u.displayName as displayName, b.status as status, b.requestedBy as requestedBy,"
            + " b.createdAt as createdAt, b.acceptedAt as acceptedAt, a.id as avatarId"
            + " from Buddy b join AppUser u on u.id = case when b.id.userA = :userId then b.id.userB else b.id.userA end"
            + " left join UserAvatar a on a.userId = u.id"
            + " where b.id.userA = :userId or b.id.userB = :userId")
    List<Pair> of(UUID userId);

    /**
     * SQL over a {@code buddy b} row: a pair of {@code :viewer} that is ACCEPTED (pending, declined and removed pairs
     * share nothing). The one place this condition lives, with {@link #OTHER_SIDE} and {@link #AVAILABLE_STOCK}: the
     * availability below and the activity feed ({@code FeedRepository}) both use these constants.
     */
    String ACCEPTED_PAIR = "(b.user_a = :viewer OR b.user_b = :viewer) AND b.status = 'ACCEPTED'";
    /** SQL: the other account of the pair {@code b}. */
    String OTHER_SIDE = "CASE WHEN b.user_a = :viewer THEN b.user_b ELSE b.user_a END";
    /**
     * SQL over a {@code stock s} row of a buddy: what buddies see of it (backend guide "Buddies"): personal stock
     * (never a club's), an amount above 0 and not kept private. Never an amount or jar size is selected with it.
     */
    String AVAILABLE_STOCK = "s.club_id IS NULL AND s.quantity_g > 0 AND s.keep_private = FALSE";

    /** One glaze an accepted buddy of the caller shows: see {@link #availableTo}. */
    record Available(UUID glazeId, UUID buddyId, String displayName) {}

    /**
     * What the buddies of {@code viewer} show them, as one statement: per accepted buddy, the glazes on that buddy's
     * personal shelf with an amount above 0 that they do not keep private ({@link #AVAILABLE_STOCK}). Never an
     * amount, a jar size, club stock or anything of a pending or removed buddy (no ACCEPTED row).
     */
    @Query(nativeQuery = true, value = "SELECT s.glaze_id, u.id, u.display_name FROM buddy b"
            + " JOIN app_user u ON u.id = " + OTHER_SIDE + " JOIN stock s ON s.user_id = u.id"
            + " WHERE " + ACCEPTED_PAIR + " AND " + AVAILABLE_STOCK
            + " ORDER BY s.glaze_id, u.display_name, u.id")
    List<Object[]> availableRows(UUID viewer);

    default List<Available> availableTo(UUID viewer) {
        return availableRows(viewer).stream().map(row -> new Available(FeedRepository.uuid(row[0]), FeedRepository.uuid(row[1]), (String) row[2])).toList();
    }

    /** Account deletion and the pending purge: every pair the account is in, accepted or pending. */
    @Modifying @Query("delete from Buddy b where b.id.userA = :userId or b.id.userB = :userId")
    int deleteOf(UUID userId);
}
