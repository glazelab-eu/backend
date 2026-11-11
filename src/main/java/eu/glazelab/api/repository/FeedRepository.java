package eu.glazelab.api.repository;

import static eu.glazelab.api.repository.BuddyRepository.ACCEPTED_PAIR;
import static eu.glazelab.api.repository.BuddyRepository.AVAILABLE_STOCK;
import static eu.glazelab.api.repository.BuddyRepository.OTHER_SIDE;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * The activity feed's one statement (backend guide "Activity feed"): a UNION ALL of the additions the viewer can see
 * NOW, read from the live rows that describe them, so an entry whose item, buddy pair, membership or actor is gone, or
 * whose glaze is kept private or used up, is simply not found (no events are stored, nothing is deleted). Every branch
 * excludes the viewer's own actions, needs a verified actor and an addition after the window's floor and after the
 * viewer became the buddy or member (so nothing older is announced, and memberships older than V31, which all carry
 * the migration's time, never show as new).
 */
@Repository @RequiredArgsConstructor
public class FeedRepository {
    /** A row: when, type, entry key, actor ID and name, item ID, title, manufacturer, glaze name and code, club ID and name. */
    public record Row(Instant at, String type, String key, UUID actorId, String actorName, UUID itemId, String title,
            String manufacturer, String glazeName, String glazeCode, UUID clubId, String clubName) {}

    /** Where a page starts: the entry after which it continues (newest first). */
    public record Cursor(Instant at, String type, String key) {}

    private static final String NO_TEXT = "CAST(NULL AS VARCHAR(160))";
    private static final String NO_ID = "CAST(NULL AS UUID)";
    private static final String NO_GLAZE = NO_TEXT + ", " + NO_TEXT + ", " + NO_TEXT;
    private static final String VERIFIED = "u.email_verified = TRUE AND u.id <> :viewer";

    private static String type(String type) { return "CAST('" + type + "' AS VARCHAR(20))"; }
    private static String key(String expression) { return "CAST(" + expression + " AS VARCHAR(80))"; }

    /** A combination published by an accepted buddy after the pair was accepted. */
    private static final String BUDDY_PUBLISHED = ACCEPTED_PAIR + " AND " + OTHER_SIDE + " = c.created_by AND c.published_at > b.accepted_at";
    private static final String BY_BUDDY = "EXISTS (SELECT 1 FROM buddy b WHERE " + BUDDY_PUBLISHED + ")";

    /**
     * Read from the viewer's pairs into {@code combination_author_published_idx} (a pair is one row per buddy, so no
     * repeats): starting from the combinations of the window would read every publication of the library.
     */
    private static final String BUDDY_COMBINATION = "SELECT c.published_at, " + type("BUDDY_COMBINATION") + ", " + key("c.id")
            + ", u.id, u.display_name, c.id, CAST(c.title AS VARCHAR(160)), " + NO_GLAZE + ", " + NO_ID + ", " + NO_TEXT
            + " FROM buddy b JOIN combination c ON c.created_by = " + OTHER_SIDE + " JOIN app_user u ON u.id = c.created_by"
            + " WHERE " + BUDDY_PUBLISHED + " AND c.published_at > :floor AND " + VERIFIED;

    /** The smallest club the viewer shares with the author in which the publication came after both joined. */
    private static final String SHARED_CLUB = "(SELECT vm.club_id FROM membership vm JOIN membership am"
            + " ON am.club_id = vm.club_id AND am.user_id = c.created_by WHERE vm.user_id = :viewer"
            + " AND c.published_at > vm.joined_at AND c.published_at > am.joined_at ORDER BY vm.club_id FETCH FIRST 1 ROWS ONLY)";

    /**
     * Only the viewer's co-members' publications (implied by {@link #SHARED_CLUB}, stated for the plan): without it
     * PostgreSQL runs the club subquery for every publication of the window in the whole library.
     */
    private static final String CO_MEMBER = "c.created_by IN (SELECT am.user_id FROM membership vm JOIN membership am"
            + " ON am.club_id = vm.club_id WHERE vm.user_id = :viewer)";

    /** {@code notBuddy}: with buddies on, a buddy's publication is that entry only (listed once). */
    private static String clubCombination(boolean notBuddy) {
        return "SELECT c.published_at, " + type("CLUB_COMBINATION") + ", " + key("c.id") + ", u.id, u.display_name, c.id,"
                + " CAST(c.title AS VARCHAR(160)), " + NO_GLAZE + ", k.id, CAST(k.name AS VARCHAR(160))"
                + " FROM combination c JOIN app_user u ON u.id = c.created_by JOIN club k ON k.id = " + SHARED_CLUB
                + " WHERE c.published_at > :floor AND " + CO_MEMBER + " AND " + VERIFIED + (notBuddy ? " AND NOT " + BY_BUDDY : "");
    }

    /** Keyed by glaze and owner (one entry each), never the stock entry's ID, which no answer names to anyone but its owner. */
    private static final String BUDDY_GLAZE = "SELECT s.created_at, " + type("BUDDY_GLAZE") + ", "
            + key("CAST(s.glaze_id AS VARCHAR(36)) || CAST(s.user_id AS VARCHAR(36))")
            + ", u.id, u.display_name, g.id, " + NO_TEXT + ", CAST(m.name AS VARCHAR(160)), CAST(g.name AS VARCHAR(160)),"
            + " CAST(g.code AS VARCHAR(160)), " + NO_ID + ", " + NO_TEXT
            + " FROM buddy b JOIN app_user u ON u.id = " + OTHER_SIDE + " JOIN stock s ON s.user_id = u.id"
            + " JOIN glaze g ON g.id = s.glaze_id JOIN manufacturer m ON m.id = g.manufacturer_id"
            + " WHERE " + ACCEPTED_PAIR + " AND " + AVAILABLE_STOCK + " AND s.created_at > b.accepted_at"
            + " AND s.created_at > :floor AND " + VERIFIED;

    /** The other account accepted the viewer's request (accepting oneself is the viewer's own action). */
    private static final String NEW_BUDDY = "SELECT b.accepted_at, " + type("NEW_BUDDY") + ", " + key("u.id")
            + ", u.id, u.display_name, u.id, " + NO_TEXT + ", " + NO_GLAZE + ", " + NO_ID + ", " + NO_TEXT
            + " FROM buddy b JOIN app_user u ON u.id = " + OTHER_SIDE
            + " WHERE " + ACCEPTED_PAIR + " AND b.requested_by = :viewer AND b.accepted_at > :floor AND " + VERIFIED;

    /**
     * A pending request addressed to the viewer (never one they sent): the asking account's name, as the buddy list's incoming
     * requests show it. Answered (accepted or declined), withdrawn or with the account gone, the row is gone or ACCEPTED.
     * No window: it is shown for as long as it waits, like the incoming list (the only branch without {@code :floor}).
     */
    private static final String BUDDY_REQUEST = "SELECT b.created_at, " + type("BUDDY_REQUEST") + ", " + key("u.id")
            + ", u.id, u.display_name, u.id, " + NO_TEXT + ", " + NO_GLAZE + ", " + NO_ID + ", " + NO_TEXT
            + " FROM buddy b JOIN app_user u ON u.id = " + OTHER_SIDE
            + " WHERE (b.user_a = :viewer OR b.user_b = :viewer) AND b.status = 'PENDING' AND b.requested_by <> :viewer"
            + " AND " + VERIFIED;

    private static final String CLUB_MEMBER = "SELECT m.joined_at, " + type("CLUB_MEMBER") + ", "
            + key("CAST(m.club_id AS VARCHAR(36)) || CAST(m.user_id AS VARCHAR(36))")
            + ", u.id, u.display_name, u.id, " + NO_TEXT + ", " + NO_GLAZE + ", k.id, CAST(k.name AS VARCHAR(160))"
            + " FROM membership vm JOIN membership m ON m.club_id = vm.club_id JOIN app_user u ON u.id = m.user_id"
            + " JOIN club k ON k.id = vm.club_id"
            + " WHERE vm.user_id = :viewer AND m.joined_at > vm.joined_at AND m.joined_at > :floor AND " + VERIFIED;

    /** A NULL adder (left, removed, deleted, or older than V27) names nobody and gives no entry. */
    private static final String CLUB_GLAZE = "SELECT s.created_at, " + type("CLUB_GLAZE") + ", " + key("s.id")
            + ", u.id, u.display_name, g.id, " + NO_TEXT + ", CAST(m.name AS VARCHAR(160)), CAST(g.name AS VARCHAR(160)),"
            + " CAST(g.code AS VARCHAR(160)), k.id, CAST(k.name AS VARCHAR(160))"
            + " FROM membership vm JOIN stock s ON s.club_id = vm.club_id JOIN app_user u ON u.id = s.added_by"
            + " JOIN glaze g ON g.id = s.glaze_id JOIN manufacturer m ON m.id = g.manufacturer_id JOIN club k ON k.id = s.club_id"
            + " WHERE vm.user_id = :viewer AND s.created_at > vm.joined_at AND s.created_at > :floor AND " + VERIFIED;

    private static final String AFTER = " WHERE f.happened_at < :beforeAt OR (f.happened_at = :beforeAt AND (f.entry_type > :beforeType"
            + " OR (f.entry_type = :beforeType AND f.entry_key > :beforeKey)))";
    private static final String ORDER = " ORDER BY f.happened_at DESC, f.entry_type, f.entry_key";

    private final EntityManager entities;

    /** The column names of every branch (a derived column list, so no branch needs aliases). */
    private static final String COLUMNS = " f(happened_at, entry_type, entry_key, actor_id, actor_name, item_id, title,"
            + " manufacturer, glaze_name, glaze_code, club_id, club_name)";

    /** The union of the enabled sources; requests addressed to the viewer always take part. */
    static String union(boolean buddies, boolean clubs) {
        List<String> branches = new ArrayList<>(List.of(BUDDY_REQUEST));
        if (buddies) branches.addAll(List.of(BUDDY_COMBINATION, BUDDY_GLAZE, NEW_BUDDY));
        if (clubs) branches.addAll(List.of(clubCombination(buddies), CLUB_MEMBER, CLUB_GLAZE));
        return "(" + String.join(" UNION ALL ", branches) + ")" + COLUMNS;
    }

    /** Up to {@code limit} entries after {@code cursor} (null: the newest), newest first. */
    @SuppressWarnings("unchecked")
    public List<Row> page(UUID viewer, Instant floor, boolean buddies, boolean clubs, Cursor cursor, int limit) {
        String union = union(buddies, clubs);
        Query query = withFloor(entities.createNativeQuery("SELECT * FROM " + union + (cursor == null ? "" : AFTER) + ORDER)
                .setParameter("viewer", viewer), buddies || clubs, floor).setMaxResults(limit);
        if (cursor != null)
            query.setParameter("beforeAt", utc(cursor.at())).setParameter("beforeType", cursor.type()).setParameter("beforeKey", cursor.key());
        return ((List<Object[]>) query.getResultList()).stream().map(FeedRepository::row).toList();
    }

    /**
     * The badge: how many entries are later than {@code since} (at most {@code cap}) and the newest of them; and the newest
     * waiting request addressed to the viewer later than {@code since} (its asking account's ID and name, its time; all null
     * without one) with how many such requests wait (FEED-2: the app's toast).
     */
    public record Badge(int unread, Instant newest, UUID requesterId, String requesterName, Instant requestedAt, int requests) {}

    /**
     * The newest waiting request later than {@code :since} with the count of all of them (a window count, taken before the
     * fetch): the {@link #BUDDY_REQUEST} branch exactly, so its visibility is the feed's.
     */
    private static final String NEWEST_REQUEST = "SELECT q.actor_id, q.actor_name, q.happened_at, COUNT(*) OVER () AS waiting FROM ("
            + BUDDY_REQUEST + ")" + COLUMNS.replace(" f(", " q(") + " WHERE q.happened_at > :since"
            + " ORDER BY q.happened_at DESC, q.actor_id FETCH FIRST 1 ROWS ONLY";

    /** {@link Badge} in one statement: the newest {@code cap} entries later than {@code since}, counted, beside the newest request. */
    public Badge badge(UUID viewer, Instant floor, boolean buddies, boolean clubs, Instant since, int cap) {
        Object[] row = (Object[]) withFloor(entities.createNativeQuery("SELECT n.unread, n.newest, r.actor_id, r.actor_name, r.happened_at, r.waiting"
                        + " FROM (SELECT COUNT(*) AS unread, MAX(x.happened_at) AS newest FROM (SELECT f.happened_at FROM " + union(buddies, clubs)
                        + " WHERE f.happened_at > :since ORDER BY f.happened_at DESC FETCH FIRST " + cap + " ROWS ONLY) x) n"
                        + " LEFT JOIN (" + NEWEST_REQUEST + ") r ON 1 = 1")
                .setParameter("viewer", viewer).setParameter("since", utc(since)), buddies || clubs, floor).getSingleResult();
        return new Badge(((Number) row[0]).intValue(), row[1] == null ? null : instant(row[1]), uuid(row[2]), (String) row[3],
                row[4] == null ? null : instant(row[4]), row[5] == null ? 0 : ((Number) row[5]).intValue());
    }

    /** With both sources off only the requests remain, which have no window (and so no {@code :floor} to bind). */
    private static Query withFloor(Query query, boolean windowed, Instant floor) {
        return windowed ? query.setParameter("floor", utc(floor)) : query;
    }

    /** Bound with its offset, so the comparison with TIMESTAMP WITH TIME ZONE columns is exact on H2 and PostgreSQL. */
    private static OffsetDateTime utc(Instant instant) { return instant.atOffset(java.time.ZoneOffset.UTC); }

    private static Row row(Object[] r) {
        return new Row(instant(r[0]), (String) r[1], (String) r[2], uuid(r[3]), (String) r[4], uuid(r[5]), (String) r[6],
                (String) r[7], (String) r[8], (String) r[9], uuid(r[10]), (String) r[11]);
    }

    /** A native UUID column: PostgreSQL answers a UUID, H2 through Hibernate its 16 bytes (big-endian). */
    public static UUID uuid(Object value) {
        return switch (value) {
            case null -> null;
            case UUID id -> id;
            case byte[] bytes when bytes.length == 16 -> {
                var buffer = java.nio.ByteBuffer.wrap(bytes);
                yield new UUID(buffer.getLong(), buffer.getLong());
            }
            default -> throw new IllegalStateException("Unexpected UUID type " + value.getClass().getName());
        };
    }

    /** Drivers answer a TIMESTAMP WITH TIME ZONE as one of these, depending on the database. */
    static Instant instant(Object value) {
        return switch (value) {
            case Instant instant -> instant;
            case OffsetDateTime offset -> offset.toInstant();
            case ZonedDateTime zoned -> zoned.toInstant();
            case Timestamp timestamp -> timestamp.toInstant();
            default -> throw new IllegalStateException("Unexpected time type " + value.getClass().getName());
        };
    }
}
