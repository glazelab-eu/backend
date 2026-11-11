package eu.glazelab.api.service;

import eu.glazelab.api.repository.AdminActionRepository;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The site admins' log ({@code admin_action}; backend guide "Bans"): its rows name accounts and reasons, so they expire
 * after {@code glazelab.admin.action-retention} (90 days; the terms promise this number).
 */
@Service @RequiredArgsConstructor @Slf4j
public class AdminLog {
    private final AdminActionRepository actions;
    private final TransactionTemplate transactions;

    @Value("${glazelab.admin.action-retention}") private Duration retention;

    /** Daily: deletes the rows older than the retention. Logs the count only. */
    @Scheduled(fixedDelay = 86_400_000, initialDelay = 3_600_000)
    public int purge() {
        Integer removed = transactions.execute(status -> actions.purge(Instant.now().minus(retention)));
        if (removed != null && removed > 0) log.info("Deleted {} admin log entries past their retention", removed);
        return removed == null ? 0 : removed;
    }
}
