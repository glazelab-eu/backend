package eu.glazelab.api.controller;

import eu.glazelab.api.dto.FeedDtos.FeedBadgeView;
import eu.glazelab.api.dto.FeedDtos.FeedPageView;
import eu.glazelab.api.dto.FeedDtos.FeedSeenInput;
import eu.glazelab.api.dto.FeedDtos.FeedSettingsInput;
import eu.glazelab.api.security.CurrentUser;
import eu.glazelab.api.service.FeedService;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The activity feed (backend guide "Activity feed"): what buddies and the account's clubs added, as far as the account
 * can see it now. Signed-in accounts only; the badge's count rides on {@code UserView.feedUnread}.
 */
@RestController @RequestMapping("/api") @RequiredArgsConstructor
public class FeedController {
    private final CurrentUser current;
    private final FeedService feed;

    /** A page of entries, newest first, at most 30 days back; {@code before} is the previous page's {@code next}. */
    @GetMapping("/feed")
    FeedPageView feed(Authentication auth,
            @Parameter(description = "The previous page's next cursor; absent for the newest entries") @RequestParam(required = false) String before) {
        return feed.page(current.user(auth), before);
    }

    /**
     * The badge alone (count, newest unread entry time and the newest unseen waiting buddy request, for the toast), for the
     * app's light poll while it is open and visible: one statement after the session's, nothing else read.
     */
    @GetMapping("/feed/unread")
    FeedBadgeView feedBadge(Authentication auth) {
        return feed.badge(current.user(auth));
    }

    /** The client showed entries up to {@code through}: later ones stay new. */
    @PostMapping("/feed/seen") @ResponseStatus(HttpStatus.NO_CONTENT)
    void markFeedSeen(Authentication auth, @Valid @RequestBody FeedSeenInput body) {
        feed.markSeen(current.user(auth).getId(), body.through());
    }

    /** The account's two sources (buddies, clubs), for every device. */
    @PutMapping("/account/feed") @ResponseStatus(HttpStatus.NO_CONTENT)
    void chooseFeed(Authentication auth, @Valid @RequestBody FeedSettingsInput body) {
        feed.choose(current.user(auth).getId(), body.buddies(), body.clubs());
    }
}
