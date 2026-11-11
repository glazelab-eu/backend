package eu.glazelab.api.repository;

import java.util.Collection;
import java.util.UUID;
import eu.glazelab.api.model.Glaze;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GlazeRepository extends JpaRepository<Glaze, UUID> {
    @Modifying
    @Query("update Glaze g set g.createdBy = null where g.createdBy.id = :userId")
    void anonymiseAuthor(UUID userId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from Glaze g where g.createdBy.id = :userId order by g.id")
    List<Glaze> lockByAuthor(UUID userId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from Glaze g where g.id in :ids order by g.id")
    List<Glaze> lockByIds(Collection<UUID> ids);
    boolean existsByManufacturer_IdAndCode(UUID manufacturerId, String code);
    boolean existsByManufacturer_Id(UUID manufacturerId);
    @EntityGraph(attributePaths = {"manufacturer", "tags", "barcodes", "createdBy"})
    List<Glaze> findAllByOrderByManufacturer_NameAscNameAsc();
    @EntityGraph(attributePaths = {"manufacturer", "tags", "barcodes", "createdBy"})
    List<Glaze> findByIdInOrderByManufacturer_NameAscNameAsc(Collection<UUID> ids);
    /** The glaze carrying a normalized barcode (unique in the library). */
    @Query("select g.id from Glaze g join g.barcodes b where b = :barcode")
    Optional<UUID> findIdByBarcode(String barcode);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from Glaze g where g.id = :id")
    Optional<Glaze> lockById(UUID id);
}
