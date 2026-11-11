package eu.glazelab.api.repository;

import eu.glazelab.api.model.AdminAction;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface AdminActionRepository extends JpaRepository<AdminAction, UUID> {
    /** The retention purge: one statement on {@code admin_action_time_idx}. */
    @Modifying
    @Query("delete from AdminAction a where a.createdAt < :cutoff")
    int purge(Instant cutoff);
}
