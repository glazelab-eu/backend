package eu.glazelab.api.repository;

import eu.glazelab.api.model.EmailBlock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface EmailBlockRepository extends JpaRepository<EmailBlock, UUID> {
    /** The registration gate: one statement on the unique hash index, whatever the list holds. */
    boolean existsByEmailHash(String hash);

    /** The entry of exactly this hash (a second ban of the same mailbox reuses it). */
    @Query("select b.id from EmailBlock b where b.emailHash = :hash")
    Optional<UUID> idOf(String hash);

    /** One row of the admins' list: the entry's date and reason (it names nobody). */
    interface ListRow { UUID getId(); String getReason(); Instant getBannedAt(); }
    /** A slice, so no count query: one statement per page. */
    @Query("select b.id as id, b.reason as reason, b.bannedAt as bannedAt from EmailBlock b")
    Slice<ListRow> list(Pageable page);

    /** 0 when the entry is unknown or another unban removed it first. */
    @Modifying
    @Query("delete from EmailBlock b where b.id = :id")
    int deleteOne(UUID id);
}
