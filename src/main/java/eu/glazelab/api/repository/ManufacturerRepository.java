package eu.glazelab.api.repository;

import eu.glazelab.api.model.Manufacturer;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface ManufacturerRepository extends JpaRepository<Manufacturer, UUID> {
    boolean existsByShortId(String shortId);
    boolean existsByNameKey(String nameKey);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Manufacturer m where m.id = :id")
    Optional<Manufacturer> lockById(UUID id);
}
