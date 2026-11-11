package eu.glazelab.api.repository;

import eu.glazelab.api.model.BuddyLink;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface BuddyLinkRepository extends JpaRepository<BuddyLink, UUID> {
    /** The owner of a link hash as a scalar: token lookups never load or lock a row before the account locks. */
    @Query("select l.userId from BuddyLink l where l.tokenHash = :tokenHash")
    Optional<UUID> ownerOf(String tokenHash);
    @Modifying @Query("delete from BuddyLink l where l.userId = :userId")
    int deleteOf(UUID userId);
}
