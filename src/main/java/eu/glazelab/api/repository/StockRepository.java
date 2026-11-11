package eu.glazelab.api.repository;

import java.util.UUID;
import eu.glazelab.api.model.Stock;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface StockRepository extends JpaRepository<Stock, UUID> {
    void deleteByUser_Id(UUID userId);
    void deleteByClub_Id(UUID clubId);
    /** Listing finders fetch what {@code StockView} reads, so a listing is a constant number of statements. */
    @EntityGraph(attributePaths = {"glaze.manufacturer", "club"})
    List<Stock> findByUser_Id(UUID userId);
    @EntityGraph(attributePaths = {"glaze.manufacturer", "club"})
    List<Stock> findByClub_IdIn(List<UUID> clubIds);
    Optional<Stock> findByGlaze_IdAndUser_Id(UUID glazeId, UUID userId);
    Optional<Stock> findByGlaze_IdAndClub_Id(UUID glazeId, UUID clubId);
    @Modifying @Query("delete from Stock s where s.id = :id")
    int deleteStockById(UUID id);
    @Modifying @Query("delete from Stock s where s.glaze.id = :glazeId")
    void deleteByGlazeId(UUID glazeId);
    /** A fresh scalar read of who added an entry (empty for none or an unknown entry); call it after the club lock. */
    @Query("select a.id from Stock s join s.addedBy a where s.id = :id")
    Optional<UUID> adderOf(UUID id);
    /** A member leaving or removed from a club keeps no trace on its shelf: their entries stay, without their name. */
    @Modifying @Query("update Stock s set s.addedBy = null where s.club.id = :clubId and s.addedBy.id = :userId")
    int forgetAdder(UUID clubId, UUID userId);
}
