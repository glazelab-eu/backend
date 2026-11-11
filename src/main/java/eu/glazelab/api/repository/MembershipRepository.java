package eu.glazelab.api.repository;

import java.util.UUID;
import eu.glazelab.api.model.Membership;
import eu.glazelab.api.model.MembershipId;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface MembershipRepository extends JpaRepository<Membership, MembershipId> {
    void deleteByUser_Id(UUID userId);
    @EntityGraph(attributePaths = "club")
    List<Membership> findByUser_Id(UUID userId);
    @EntityGraph(attributePaths = "user")
    List<Membership> findByClub_Id(UUID clubId);
    /** One row of {@link #membersOf}: a member's ID, display name, role and profile picture (null without one). */
    interface Member { UUID getId(); String getDisplayName(); Membership.Role getRole(); UUID getAvatarId(); }
    /** The club's member list with each member's picture: one statement, whatever the number of members. */
    @Query("select u.id as id, u.displayName as displayName, m.role as role, a.id as avatarId from Membership m"
            + " join m.user u left join UserAvatar a on a.userId = u.id where m.club.id = :clubId")
    List<Member> membersOf(UUID clubId);
    @Query("select m.club.id from Membership m where m.user.id = :userId")
    List<UUID> clubIdsOf(UUID userId);
    interface ClubRole { UUID getClubId(); Membership.Role getRole(); }
    /** Every club of the account with its role, as scalars: one statement per request for the image rights. */
    @Query("select m.club.id as clubId, m.role as role from Membership m where m.user.id = :userId")
    List<ClubRole> rolesOf(UUID userId);
    /** A fresh scalar role; call it after the club lock when the role gates a write. */
    @Query("select m.role from Membership m where m.club.id = :clubId and m.user.id = :userId")
    Optional<Membership.Role> roleOf(UUID clubId, UUID userId);
    long countByClub_IdAndRole(UUID clubId, Membership.Role role);
    /** One membership as a bulk delete (0 when a concurrent purge removed it first); call it under the club lock. */
    @Modifying @Query("delete from Membership m where m.club.id = :clubId and m.user.id = :userId")
    int deleteMembership(UUID clubId, UUID userId);
    /** Makes one member an admin as a bulk update (a ban's promotion); call it under the club lock. */
    @Modifying @Query("update Membership m set m.role = eu.glazelab.api.model.Membership.Role.ADMIN"
            + " where m.club.id = :clubId and m.user.id = :userId")
    int promote(UUID clubId, UUID userId);
    /** Every membership of the clubs, before they are deleted (a ban's leftover pending members); under their locks. */
    @Modifying @Query("delete from Membership m where m.club.id in :clubIds")
    int deleteByClubs(Collection<UUID> clubIds);
}
