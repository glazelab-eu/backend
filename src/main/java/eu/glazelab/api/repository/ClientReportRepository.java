package eu.glazelab.api.repository;

import eu.glazelab.api.model.ClientReport;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Error reports (backend guide "Error reports"); bulk statements, so nothing loads a report before deleting it. */
public interface ClientReportRepository extends JpaRepository<ClientReport, UUID> {
    /** Every report of the account (opt-out); called under the account lock. */
    @Modifying @Query("delete from ClientReport r where r.userId = :userId")
    int deleteOf(UUID userId);

    /** How many reports the account sent since {@code since} (the daily cap), on the user index. */
    @Query("select count(r) from ClientReport r where r.userId = :userId and r.receivedAt >= :since")
    long countSince(UUID userId, Instant since);

    /** The retention purge. */
    @Modifying @Query("delete from ClientReport r where r.receivedAt < :before")
    int purge(Instant before);

    /** The reports of one message, in the order of {@code page}'s sort (fingerprint index). */
    Slice<ClientReport> findByFingerprint(String fingerprint, Pageable page);

    /** Deletes a whole message (admin). */
    @Modifying @Query("delete from ClientReport r where r.fingerprint = :fingerprint")
    int deleteGroup(String fingerprint);

    /** Deletes one report (admin). */
    @Modifying @Query("delete from ClientReport r where r.id = :id")
    int deleteOne(UUID id);

    /** The build labels, most recently received first. */
    @Query("select r.buildLabel from ClientReport r group by r.buildLabel order by max(r.receivedAt) desc, r.buildLabel")
    java.util.List<String> builds(Pageable limit);
}
