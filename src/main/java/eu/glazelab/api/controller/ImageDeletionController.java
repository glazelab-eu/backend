package eu.glazelab.api.controller;

import eu.glazelab.api.dto.ImageDtos.ImageDeletionPageView;
import eu.glazelab.api.dto.ImageDtos.ImageDeletionView;
import eu.glazelab.api.repository.ImageDeletionRepository;
import eu.glazelab.api.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController @RequiredArgsConstructor
public class ImageDeletionController {
    static final int PAGE_SIZE = 50;
    /** Highest page number accepted, so a huge offset never reaches the database. */
    private static final int MAX_PAGE = 100000;
    private static final String INVALID_PAGE = "Invalid page";
    private final CurrentUser current;
    private final ImageDeletionRepository deletions;

    @GetMapping("/api/admin/image-deletions")
    ImageDeletionPageView imageDeletions(@RequestParam(defaultValue = "0") int page, Authentication auth) {
        current.siteAdmin(auth);
        var result = deletions.findAll(PageRequest.of(page(page), PAGE_SIZE, Sort.by("deletedAt", "id").descending()));
        return new ImageDeletionPageView(result.stream().map(ImageDeletionView::of).toList(), result.hasNext());
    }

    /** The page number of an admin listing (this log, the report queue), or 400. */
    static int page(int page) {
        if (page < 0 || page > MAX_PAGE) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, INVALID_PAGE);
        return page;
    }
}
