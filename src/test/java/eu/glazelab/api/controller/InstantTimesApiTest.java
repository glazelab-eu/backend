package eu.glazelab.api.controller;

import eu.glazelab.api.support.ApiTestBase;
import eu.glazelab.api.support.TestImages;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Every time the API answers is an instant with {@code Z}, whatever the server's zone (run with
 * {@code -Duser.timezone=Pacific/Auckland} to check a non-UTC JVM): the combination times, the photo deletion log and
 * the photo report queue were zone-less server-local times once and read in the reader's zone.
 */
class InstantTimesApiTest extends ApiTestBase {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode json(RestTestClient client, String uri) {
        return JSON.readTree(client.get().uri(uri).exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody());
    }

    private static JsonNode find(JsonNode list, String key, UUID id) {
        for (JsonNode entry : list) if (entry.get(key).asString().equals(id.toString())) return entry;
        throw new AssertionError(id + " not in " + list);
    }

    /** The field as an instant (with {@code Z}, so it parses), taken between {@code before} and {@code after}. */
    private static Instant instant(JsonNode entry, String field, Instant before, Instant after) {
        String text = entry.get(field).asString();
        assertTrue(text.endsWith("Z"), field + " has no zone: " + text);
        Instant value = Instant.parse(text);
        assertFalse(value.isBefore(before.truncatedTo(ChronoUnit.MICROS)), field + " " + value + " before " + before);
        assertFalse(value.isAfter(after), field + " " + value + " after " + after);
        return value;
    }

    @Test void combinationTimesAreInstantsForDraftsAndPublishedOnes() {
        var author = user("Times");
        UUID glaze = addGlaze(author, unique("Times maker"), "Blue", "1");
        Instant before = Instant.now();
        UUID published = addCombination(author, unique("Published"), glaze);
        UUID draft = created(author.post().uri("/api/combinations").body(Map.of("title", unique("Draft"),
                "steps", List.of(Map.of("glazeId", glaze, "coats", 1)), "draft", true)).exchange());
        Instant after = Instant.now();
        JsonNode listing = json(author, "/api/combinations");
        JsonNode shown = find(listing, "id", published);
        assertEquals(instant(shown, "createdAt", before, after), instant(shown, "publishedAt", before, after));
        JsonNode started = find(listing, "id", draft);
        instant(started, "createdAt", before, after);
        assertTrue(started.get("publishedAt").isNull());
        Instant publishing = Instant.now();
        author.post().uri("/api/combinations/{id}/publish", draft).exchange().expectStatus().isNoContent();
        instant(find(json(author, "/api/combinations"), "id", draft), "publishedAt", publishing, Instant.now());
    }

    @Test void theDeletionLogAndTheReportQueueAnswerInstants() {
        var uploader = user("TimesUploader");
        var reporter = user("TimesReporter");
        var admin = siteAdmin();
        UUID combination = addCombination(uploader, unique("Times photo"), addGlaze(uploader, unique("Times photo maker"), "Red", "1"));
        UUID reported = uploadTile(uploader, combination, "PUBLIC", null);
        Instant before = Instant.now();
        reporter.post().uri("/api/images/{id}/reports", reported).body(Map.of("reason", "Times")).exchange().expectStatus().isNoContent();
        instant(find(json(admin, "/api/admin/image-reports").get("entries"), "imageId", reported), "createdAt", before, Instant.now());
        UUID deleted = created(uploadGlazeImage(uploader, addGlaze(uploader, unique("Times log"), "Grey", "1"),
                Map.of("visibility", "PUBLIC", "kind", "TEST_TILE"), TestImages.png(24, 24)));
        before = Instant.now();
        uploader.delete().uri("/api/images/{id}", deleted).exchange().expectStatus().isNoContent();
        instant(find(json(admin, "/api/admin/image-deletions").get("entries"), "imageId", deleted), "deletedAt", before, Instant.now());
    }

    /** The listing is ordered in the query by publication (a draft by creation), newest first, then by ID. */
    @Test void theListingSortsPublicationsAndDraftStartsAsOneTimeline() {
        var author = user("Timeline");
        UUID glaze = addGlaze(author, unique("Timeline maker"), "Blue", "1");
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            ids.add(created(author.post().uri("/api/combinations").body(Map.of("title", unique("Timeline " + i),
                    "steps", List.of(Map.of("glazeId", glaze, "coats", 1)), "draft", i % 2 == 1)).exchange()));
        }
        // Published 0 and 2, drafts 1 and 3, interleaved an hour apart: 3 (draft), 2, 1 (draft), 0.
        OffsetDateTime base = OffsetDateTime.of(2001, 3, 4, 5, 0, 0, 0, ZoneOffset.UTC);
        for (int i = 0; i < 4; i++) {
            OffsetDateTime at = base.plusHours(i);
            jdbc.update("UPDATE combination SET created_at = ?, published_at = CASE WHEN published_at IS NULL THEN NULL ELSE ? END WHERE id = ?",
                    i % 2 == 0 ? at.minusDays(400) : at, at, ids.get(i));
        }
        List<UUID> order = new ArrayList<>();
        for (JsonNode entry : json(author, "/api/combinations")) {
            UUID id = UUID.fromString(entry.get("id").asString());
            if (ids.contains(id)) order.add(id);
        }
        assertEquals(List.of(ids.get(3), ids.get(2), ids.get(1), ids.get(0)), order);
        // A published one started long before a draft still sorts by its publication.
        assertEquals("2001-03-04T07:00:00Z", find(json(author, "/api/combinations"), "id", ids.get(2)).get("publishedAt").asString());
    }
}
