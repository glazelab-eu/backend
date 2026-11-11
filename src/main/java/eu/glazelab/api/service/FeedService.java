package eu.glazelab.api.service;

import eu.glazelab.api.dto.FeedDtos.FeedBadgeView;
import eu.glazelab.api.dto.FeedDtos.FeedEntryView;
import eu.glazelab.api.dto.FeedDtos.FeedPageView;
import eu.glazelab.api.dto.FeedDtos.FeedType;
import eu.glazelab.api.exception.ApiErrorHandler;
import eu.glazelab.api.model.AppUser;
import eu.glazelab.api.repository.AppUserRepository;
import eu.glazelab.api.repository.FeedRepository;
import eu.glazelab.api.repository.FeedRepository.Cursor;
import eu.glazelab.api.repository.FeedRepository.Row;
import eu.glazelab.api.security.CurrentUser;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The activity feed (backend guide "Activity feed"): additions by buddies and in the account's clubs that the account
 * can see now, newest first, read from the live rows ({@link FeedRepository}); the seen position and the two sources
 * live on the account row.
 */
@Service @RequiredArgsConstructor
public class FeedService {
    public static final int PAGE_SIZE = 30;
    /** The badge counts up to this many new entries (the app shows "99+" at the cap). */
    public static final int BADGE_CAP = 100;
    private static final Pattern CURSOR = Pattern.compile("(\\d{1,19})\\.([A-Z_]{1,20})\\.([0-9a-f-]{36,72})");

    private final FeedRepository feed;
    private final AppUserRepository users;
    private final CurrentUser current;
    @Value("${glazelab.feed.window}") private Duration window;

    @Transactional(readOnly = true)
    public FeedPageView page(AppUser user, String before) {
        Cursor cursor = before == null || before.isBlank() ? null : cursor(before);
        List<Row> rows = feed.page(user.getId(), floor(), user.isFeedBuddies(), user.isFeedClubs(), cursor, PAGE_SIZE + 1);
        List<Row> shown = rows.size() > PAGE_SIZE ? rows.subList(0, PAGE_SIZE) : rows;
        String next = rows.size() > PAGE_SIZE ? cursorOf(shown.getLast()) : null;
        return new FeedPageView(shown.stream().map(FeedService::view).toList(), next, user.getFeedSeenAt());
    }

    /** The badge: entries later than the seen position (every entry of the window before the first look), capped. */
    public int unread(AppUser user) {
        return badge(user).unread();
    }

    /** The badge, the newest unread entry's time and the newest unseen waiting request: one statement. */
    @Transactional(readOnly = true)
    public FeedBadgeView badge(AppUser user) {
        Instant floor = floor();
        Instant seen = user.getFeedSeenAt();
        // Every branch but the requests bounds itself by the window; a waiting request of any age is new until seen.
        Instant since = seen == null ? Instant.EPOCH : seen;
        var badge = feed.badge(user.getId(), floor, user.isFeedBuddies(), user.isFeedClubs(), since, BADGE_CAP);
        return new FeedBadgeView(badge.unread(), badge.newest(), badge.requesterId(), badge.requesterName(), badge.requestedAt(), badge.requests());
    }

    /**
     * Moves the seen position to {@code through}, at most the server's now; never back. A time before 1970 (older than
     * any entry, a waiting request included) writes nothing: an extreme one would not even fit the column on PostgreSQL.
     */
    @Transactional
    public void markSeen(UUID userId, Instant through) {
        Instant now = Instant.now();
        if (through.isBefore(Instant.EPOCH)) return;
        users.markFeedSeen(userId, (through.isAfter(now) ? now : through).truncatedTo(ChronoUnit.MICROS));
    }

    /** The account's two sources, for every device of the account. */
    @Transactional
    public void choose(UUID userId, boolean buddies, boolean clubs) {
        current.lockUser(userId).chooseFeed(buddies, clubs);
    }

    private Instant floor() { return Instant.now().minus(window); }

    private static FeedEntryView view(Row row) {
        return new FeedEntryView(row.type() + "." + row.key(), FeedType.valueOf(row.type()), row.at(), row.actorId(), row.actorName(),
                row.itemId(), row.title(), row.manufacturer(), row.glazeName(), row.glazeCode(), row.clubId(), row.clubName());
    }

    /** Opaque to clients: {@code <epoch microseconds>.<type>.<entry key>} of the last entry shown. */
    static String cursorOf(Row row) {
        long micros = ChronoUnit.MICROS.between(Instant.EPOCH, row.at());
        return micros + "." + row.type() + "." + row.key();
    }

    static Cursor cursor(String value) {
        var match = CURSOR.matcher(value);
        if (!match.matches()) throw invalid();
        try {
            FeedType.valueOf(match.group(2));
            return new Cursor(Instant.EPOCH.plus(Long.parseLong(match.group(1)), ChronoUnit.MICROS), match.group(2), match.group(3));
        } catch (IllegalArgumentException | ArithmeticException | java.time.DateTimeException unreadable) {
            throw invalid();
        }
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiErrorHandler.INVALID_INPUT);
    }
}
