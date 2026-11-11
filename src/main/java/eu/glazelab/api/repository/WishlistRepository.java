package eu.glazelab.api.repository;

import eu.glazelab.api.model.WishlistEntry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface WishlistRepository extends JpaRepository<WishlistEntry, UUID> {
    /** One listed entry with its glaze's names and its adder's display name (null after they left or deleted their account). */
    interface Row {
        UUID getId();
        WishlistEntry.Type getType();
        UUID getGlazeId();
        String getManufacturer();
        String getCode();
        String getGlazeName();
        String getItemName();
        String getLink();
        String getNote();
        UUID getAddedById();
        String getAddedByName();
        Instant getCreatedAt();
        Instant getAcquiredAt();
    }

    String ROW = "select w.id as id, w.type as type, g.id as glazeId, mf.name as manufacturer, g.code as code, g.name as glazeName,"
            + " w.itemName as itemName, w.link as link, w.note as note, u.id as addedById, u.displayName as addedByName,"
            + " w.createdAt as createdAt, w.acquiredAt as acquiredAt from WishlistEntry w left join w.glaze g"
            + " left join g.manufacturer mf left join w.addedBy u";

    /** The club's whole wishlist in one statement, oldest first. */
    @Query(ROW + " where w.club.id = :clubId order by w.createdAt, w.id")
    List<Row> rowsOf(UUID clubId);

    @Query(ROW + " where w.id = :id")
    Optional<Row> rowOf(UUID id);

    Optional<WishlistEntry> findByIdAndClub_Id(UUID id, UUID clubId);

    long countByClub_IdAndAcquiredAtIsNull(UUID clubId);

    boolean existsByClub_IdAndGlaze_IdAndAcquiredAtIsNull(UUID clubId, UUID glazeId);

    /** A member left or was removed: their entries stay with the club, without their name or their rights. */
    @Modifying @Query("update WishlistEntry w set w.addedBy = null where w.club.id = :clubId and w.addedBy.id = :userId")
    int forgetAdder(UUID clubId, UUID userId);

    @Modifying @Query("delete from WishlistEntry w where w.id = :id")
    int deleteEntry(UUID id);
}
