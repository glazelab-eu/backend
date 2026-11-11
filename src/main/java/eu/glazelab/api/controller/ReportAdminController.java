package eu.glazelab.api.controller;

import eu.glazelab.api.dto.ReportDtos.ReportBuildsView;
import eu.glazelab.api.dto.ReportDtos.ReportGroupPageView;
import eu.glazelab.api.dto.ReportDtos.ReportOccurrencePageView;
import eu.glazelab.api.model.ClientReport;
import eu.glazelab.api.security.CurrentUser;
import eu.glazelab.api.service.ReportAdminService;
import eu.glazelab.api.service.ReportAdminService.Filter;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reading the error reports (backend guide "Error reports"), site admins only: 401 for guests and 403 for everyone
 * else, writes with CSRF and the current terms like every admin write. No answer carries an account ID or name.
 */
@RestController @RequestMapping("/api/admin/error-reports") @RequiredArgsConstructor
public class ReportAdminController {
    private final CurrentUser current;
    private final ReportAdminService admin;

    /**
     * The list of messages: one row per fingerprint, the most recently received first, in pages of
     * {@link ImageDeletionController#PAGE_SIZE}; filters {@code kind}, {@code build} (exact label) and {@code q} (text
     * in the message, case-insensitive, at most 100 characters).
     */
    @GetMapping
    ReportGroupPageView errorReportGroups(@RequestParam(defaultValue = "0") int page, @RequestParam(required = false) ClientReport.Kind kind,
            @RequestParam(required = false) String build, @RequestParam(required = false) String q, Authentication auth) {
        current.siteAdmin(auth);
        return admin.groups(new Filter(kind, build == null || build.isBlank() ? null : build.strip(), ReportAdminService.text(q)),
                ImageDeletionController.page(page), ImageDeletionController.PAGE_SIZE);
    }

    /** The build labels in the stored reports, for the filter. */
    @GetMapping("/builds")
    ReportBuildsView errorReportBuilds(Authentication auth) {
        current.siteAdmin(auth);
        return admin.builds();
    }

    /** One message's reports with stack and device facts, newest first, in pages of {@link ReportAdminService#OCCURRENCE_PAGE_SIZE}; 404 unknown. */
    @GetMapping("/{fingerprint}/occurrences")
    ReportOccurrencePageView errorReportOccurrences(@PathVariable String fingerprint, @RequestParam(defaultValue = "0") int page,
            Authentication auth) {
        current.siteAdmin(auth);
        return admin.occurrences(fingerprint, ImageDeletionController.page(page));
    }

    /** Deletes a whole message (204); 404 unknown. */
    @DeleteMapping("/{fingerprint}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteErrorReportGroup(@PathVariable String fingerprint, Authentication auth) {
        current.siteAdmin(auth);
        admin.deleteGroup(fingerprint);
    }

    /** Deletes one report (204); 404 unknown. */
    @DeleteMapping("/occurrences/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteErrorReport(@PathVariable UUID id, Authentication auth) {
        current.siteAdmin(auth);
        admin.deleteOne(id);
    }
}
