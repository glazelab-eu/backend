package eu.glazelab.api.service;

import eu.glazelab.api.dto.ReportDtos.DeviceInfo;
import eu.glazelab.api.dto.ReportDtos.ReportBatch;
import eu.glazelab.api.dto.ReportDtos.ReportEvent;
import eu.glazelab.api.model.AppUser;
import eu.glazelab.api.model.ClientReport;
import eu.glazelab.api.model.ReportScrubber;
import eu.glazelab.api.repository.ClientReportRepository;
import eu.glazelab.api.security.CurrentUser;
import eu.glazelab.api.security.TermsFilter;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Opt-in error reports (backend guide "Error reports"). The account's choice and every stored report are decided under
 * the account row lock (the actor account, first in every lock order; nothing is locked after it), so an opt-out or an
 * account deletion racing a send leaves no report: whichever comes second sees the other's outcome. Never logs a
 * report's content.
 */
@Service @RequiredArgsConstructor @Slf4j
public class ReportService {
    public static final String DIAGNOSTICS_OFF = "Error reports are turned off for this account.";

    private final ClientReportRepository reports;
    private final CurrentUser current;
    private final TransactionTemplate transactions;

    /** How long a report is kept (the terms promise this number, so a change needs a terms change). */
    @Value("${glazelab.reports.retention}") private Duration retention;
    /** At most this many reports per account in 24 hours; beyond it a batch is accepted and dropped. */
    @Value("${glazelab.reports.daily-cap}") private int dailyCap;

    /**
     * Turns the account's error reports on or off. Turning them on needs the current terms (428 like any other write;
     * the terms describe the reports); turning them off never does, so consent can always be withdrawn, and deletes
     * every report of the account in the same transaction.
     */
    @Transactional
    public void choose(UUID userId, boolean enabled) {
        AppUser account = current.lockUser(userId);
        if (enabled && !AccountService.TERMS_VERSION.equals(account.getAcceptedTermsVersion()))
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, TermsFilter.TERMS_REQUIRED);
        account.chooseDiagnostics(enabled);
        if (!enabled) reports.deleteOf(userId);
    }

    /**
     * Stores a batch for an account that has opted in, checked again under its row lock (409 {@link #DIAGNOSTICS_OFF}
     * otherwise, and nothing is stored). Over the daily cap the batch is dropped without an error (the app would only
     * try again).
     */
    @Transactional
    public void store(UUID userId, ReportBatch batch) {
        AppUser account = current.lockUser(userId);
        if (!account.isDiagnosticsOptIn()) throw new ResponseStatusException(HttpStatus.CONFLICT, DIAGNOSTICS_OFF);
        Instant now = Instant.now();
        long room = dailyCap - reports.countSince(userId, now.minus(Duration.ofDays(1)));
        if (room <= 0) return;
        List<ClientReport> rows = new ArrayList<>();
        for (ReportEvent event : batch.events().subList(0, (int) Math.min(batch.events().size(), room)))
            rows.add(report(userId, now, event, batch.device()));
        reports.saveAll(rows);
    }

    private static ClientReport report(UUID userId, Instant now, ReportEvent event, DeviceInfo device) {
        String message = ReportScrubber.scrub(event.message(), ReportScrubber.MESSAGE_LENGTH);
        // A device clock far off (more than the retention behind or a day ahead) is replaced by the receive time.
        Instant occurred = event.occurredAt();
        if (occurred.isBefore(now.minus(Duration.ofDays(31))) || occurred.isAfter(now.plus(Duration.ofDays(1)))) occurred = now;
        return ClientReport.builder()
                .userId(userId).receivedAt(now).occurredAt(occurred).kind(event.kind())
                .fingerprint(ReportScrubber.fingerprint(event.kind().name(), message)).message(message)
                .stack(ReportScrubber.scrub(event.stack(), ReportScrubber.STACK_LENGTH))
                .occurrences(event.count() == null ? 1 : event.count())
                .buildLabel(event.build()).routeName(event.route())
                .browser(device.browser()).browserVersion(device.browserVersion())
                .os(device.os()).osVersion(device.osVersion())
                .screenWidth(device.screenWidth()).screenHeight(device.screenHeight())
                .viewportWidth(device.viewportWidth()).viewportHeight(device.viewportHeight())
                .pixelRatio(device.pixelRatio()).pointer(device.pointer()).language(device.language())
                .standalone(device.standalone()).online(device.online()).connectionType(device.connection())
                .serviceWorker(device.serviceWorker()).updatePending(device.updatePending())
                .reducedMotion(device.reducedMotion()).colorScheme(device.colorScheme())
                .timezoneOffsetMinutes(device.timezoneOffsetMinutes()).deviceMemoryGb(device.deviceMemoryGb())
                .build();
    }

    /** Hourly: deletes reports older than the retention. Logs the count only. */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 900_000)
    public int purge() {
        Integer removed = transactions.execute(status -> reports.purge(Instant.now().minus(retention)));
        if (removed != null && removed > 0) log.info("Deleted {} error reports past their retention", removed);
        return removed == null ? 0 : removed;
    }
}
