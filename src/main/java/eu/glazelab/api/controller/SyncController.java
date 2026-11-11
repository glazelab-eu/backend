package eu.glazelab.api.controller;

import eu.glazelab.api.dto.SyncDtos.CombinationPageView;
import eu.glazelab.api.dto.SyncDtos.SyncView;
import eu.glazelab.api.service.SyncService;
import io.swagger.v3.oas.annotations.Parameter;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The incremental sync of the glaze and combination listings (service.SyncService); public like the listings. */
@RestController @RequiredArgsConstructor
public class SyncController {
    private final SyncService sync;

    /**
     * The glazes and combinations changed since {@code since} as this caller's listings show them, the removed or
     * hidden IDs and the listing sizes; or a {@code resync} answer (no cursor yet, another epoch or format, too old,
     * too many changes) after which the client loads the listings in full. Take a cursor this way before a full load,
     * so nothing changed during it is missed.
     */
    @GetMapping("/api/sync") SyncView sync(
            @Parameter(description = "The cursor of the previous answer, unchanged") @RequestParam(required = false) String since,
            @Parameter(description = "The epoch of the previous answer") @RequestParam(required = false) String epoch,
            @Parameter(description = "The data format the client's stored views have") @RequestParam(required = false) Integer format,
            Authentication auth) {
        return sync.sync(since, epoch, format, auth);
    }

    /**
     * The caller's combination listing in pages by ID (published ones and their own drafts), for a resumable first
     * sync started with a cursor from {@code GET /api/sync}; {@code limit} is clamped to 1 to 1000.
     */
    @GetMapping("/api/sync/combinations") CombinationPageView combinationPage(
            @Parameter(description = "The previous page's next, absent for the first page") @RequestParam(required = false) UUID after,
            @RequestParam(defaultValue = "500") int limit, Authentication auth) {
        return sync.page(after, limit, auth);
    }
}
