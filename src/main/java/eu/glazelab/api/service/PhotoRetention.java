package eu.glazelab.api.service;

import eu.glazelab.api.repository.ImageDeletionRepository;
import eu.glazelab.api.repository.ImageReportRepository;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Daily purges of photo moderation rows (backend guide "Photos"): the deletion log ({@code image_deletion}) keeps its
 * rows for {@code glazelab.image-deletion-log.retention}, and a photo report nobody decided is deleted like a dismissal
 * {@code glazelab.moderation.report-expiry} after it was opened (the photo stays). One statement each, on the time
 * index, locking only those rows. Logs counts only.
 */
@Service @RequiredArgsConstructor @Slf4j
public class PhotoRetention {
    private final ImageDeletionRepository deletions;
    private final ImageReportRepository reports;
    private final TransactionTemplate transactions;

    @Value("${glazelab.image-deletion-log.retention}") private Duration deletionLogRetention;
    @Value("${glazelab.moderation.report-expiry}") private Duration reportExpiry;

    @Scheduled(fixedDelay = 86_400_000, initialDelay = 3_600_000)
    public int purgeDeletionLog() {
        Integer removed = transactions.execute(status -> deletions.purge(Instant.now().minus(deletionLogRetention)));
        if (removed != null && removed > 0) log.info("Deleted {} photo deletion log entries past their retention", removed);
        return removed == null ? 0 : removed;
    }

    @Scheduled(fixedDelay = 86_400_000, initialDelay = 3_600_000)
    public int expireReports() {
        Integer removed = transactions.execute(status -> reports.expire(Instant.now().minus(reportExpiry)));
        if (removed != null && removed > 0) log.info("Deleted {} photo reports nobody decided in time", removed);
        return removed == null ? 0 : removed;
    }
}
