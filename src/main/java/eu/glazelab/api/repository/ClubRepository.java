package eu.glazelab.api.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import eu.glazelab.api.model.Club;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ClubRepository extends JpaRepository<Club, UUID> {
    List<Club> findByOwner_Id(UUID userId);
    @Modifying
    @Query("update Club c set c.owner = null where c.owner.id = :userId")
    void anonymiseOwner(UUID userId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Club c where c.id = :id")
    Optional<Club> lockById(UUID id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Club> findByInviteHash(String hash);
}
