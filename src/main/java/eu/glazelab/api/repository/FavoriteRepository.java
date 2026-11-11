package eu.glazelab.api.repository;

import eu.glazelab.api.model.Favorite;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface FavoriteRepository extends JpaRepository<Favorite, UUID> {
    interface Count {
        UUID getItemId();
        long getTotal();
    }
    @Query("select f.glaze.id as itemId, count(f) as total from Favorite f where f.glaze is not null group by f.glaze.id order by f.glaze.id")
    List<Count> glazeCounts();
    /** A draft's ID must never be exposed through popularity, even if its author starred it. */
    @Query("select c.id as itemId, count(f) as total from Favorite f join f.combination c where c.publishedAt is not null group by c.id order by c.id")
    List<Count> combinationCounts();
    void deleteByUser_Id(UUID userId);
    /** The account's favourites with their combination loaded in the same query, for the visibility check. */
    @Query("select f from Favorite f left join fetch f.combination where f.user.id = :userId")
    List<Favorite> findWithCombinationByUser_Id(UUID userId);
    Optional<Favorite> findByUser_IdAndGlaze_Id(UUID userId, UUID glazeId);
    Optional<Favorite> findByUser_IdAndCombination_Id(UUID userId, UUID combinationId);
    @Modifying @Query("delete from Favorite f where f.combination.id = :combinationId")
    void deleteByCombinationId(UUID combinationId);
    @Modifying @Query("delete from Favorite f where f.glaze.id = :glazeId")
    void deleteByGlazeId(UUID glazeId);
}
