package eu.glazelab.api.controller;

import eu.glazelab.api.dto.ImageDtos.ImageReportPageView;
import eu.glazelab.api.dto.ImageDtos.ImageReportView;
import eu.glazelab.api.repository.ImageReportRepository;
import eu.glazelab.api.repository.ImageReportRepository.QueueRow;
import eu.glazelab.api.security.CurrentUser;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** The site admins' queue of photo reports; reports are created at {@code POST /api/images/{id}/reports}. */
@RestController @RequiredArgsConstructor
public class ImageReportController {
    public static final String REPORT_NOT_FOUND = "Report not found";
    private final CurrentUser current;
    private final ImageReportRepository reports;

    /** Newest first, in pages of {@link ImageDeletionController#PAGE_SIZE}. */
    @GetMapping("/api/admin/image-reports") @Transactional(readOnly = true)
    ImageReportPageView imageReports(@RequestParam(defaultValue = "0") int page, Authentication auth) {
        current.siteAdmin(auth);
        var result = reports.queue(PageRequest.of(ImageDeletionController.page(page), ImageDeletionController.PAGE_SIZE,
                Sort.by("createdAt", "id").descending()));
        return new ImageReportPageView(result.stream().map(ImageReportController::view).toList(), result.hasNext());
    }

    /** Removes a report without touching its photo; the reporter may report the photo again. */
    @PostMapping("/api/admin/image-reports/{id}/dismiss") @ResponseStatus(HttpStatus.NO_CONTENT) @Transactional
    void dismissImageReport(@PathVariable UUID id, Authentication auth) {
        current.lockUser(current.siteAdmin(auth).getId());
        if (reports.dismiss(id) == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, REPORT_NOT_FOUND);
    }

    private static ImageReportView view(QueueRow row) {
        return new ImageReportView(row.getId(), row.getImageId(), row.getGlazeId(), row.getCombinationId(),
                row.getGlazeId() == null ? row.getCombinationTitle() : row.getGlazeName(), row.getVisibility(),
                row.getReporter(), row.getReason(), row.getCreatedAt(), row.getUploaderId(), row.getUploader());
    }
}
