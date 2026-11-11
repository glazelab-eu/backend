package eu.glazelab.api.controller;

import eu.glazelab.api.dto.CombinationDtos.TitleView;
import eu.glazelab.api.model.Text;
import eu.glazelab.api.service.CombinationService;
import eu.glazelab.api.support.ApiTestBase;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.client.RestTestClient;
import static org.junit.jupiter.api.Assertions.*;

/** {@code PATCH /api/combinations/{id}/title}: the author's quick rename and a site admin's correction. */
class CombinationTitleApiTest extends ApiTestBase {
    private static RestTestClient.ResponseSpec retitle(RestTestClient client, UUID id, Object title) {
        var body = new HashMap<String, Object>();
        body.put("title", title);
        return client.patch().uri("/api/combinations/{id}/title", id).body(body).exchange();
    }

    private static UUID combination(RestTestClient client, String title, boolean draft) {
        return created(client.post().uri("/api/combinations").body(Map.of("title", title, "draft", draft,
                "steps", List.of(Map.of("type", "CUSTOM", "label", "Glaze firing")))).exchange());
    }

    private String title(UUID id) { return jdbc.queryForObject("SELECT title FROM combination WHERE id = ?", String.class, id); }
    private int count(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }
    private int corrections(UUID id) { return count("SELECT COUNT(*) FROM admin_action WHERE target_id = ?", id); }
    private long changes(UUID id) { return count("SELECT COUNT(*) FROM change_log WHERE item_id = ?", id); }

    @Test void theAuthorRenamesTheirCombinationTrimmedAndEveryoneSeesIt() {
        var author = user("Author");
        UUID id = combination(author, "Celadon over shino", false);
        var answer = body(retitle(author, id, "  Celadon over Shino  ").expectStatus().isOk(), TitleView.class);
        assertEquals("Celadon over Shino", answer.title());
        assertEquals("Celadon over Shino", combination(guest(), id).orElseThrow().title());
        assertEquals(0, corrections(id), "the author's own rename is not an admin action");
    }

    @Test void otherAccountsMayNotRenameAndAForeignDraftIsLikeAnUnknownCombination() {
        RestTestClient author = user("Author"), other = user("Other");
        UUID published = combination(author, "Shared", false), draft = combination(author, "Secret draft", true);
        assertError(retitle(other, published, "Mine now"), 403, CombinationService.AUTHOR_REQUIRED);
        assertError(retitle(other, draft, "Mine now"), 404, CombinationService.NOT_FOUND);
        assertError(retitle(other, UUID.randomUUID(), "Mine now"), 404, CombinationService.NOT_FOUND);
        assertEquals(statusAndBody(retitle(other, UUID.randomUUID(), "Mine now")), statusAndBody(retitle(other, draft, "Mine now")));
        retitle(guest(), published, "Guest").expectStatus().isUnauthorized();
        assertEquals("Shared", title(published));
        assertEquals("Secret draft", title(draft));
    }

    @Test void aSiteAdminCorrectsSomeoneElsesTitleAndTheLogKeepsBothTitles() {
        var author = user("Author"); var admin = siteAdmin();
        UUID id = combination(author, "Tenmoku ovr ash", false);
        long before = changes(id);
        assertEquals("Tenmoku over ash", body(retitle(admin, id, "Tenmoku over ash").expectStatus().isOk(), TitleView.class).title());
        assertEquals("Tenmoku over ash", title(id));
        assertTrue(changes(id) > before, "the correction reaches the next delta");
        var row = jdbc.queryForMap("SELECT actor_id, action, target_user_id, reason, old_value, new_value FROM admin_action WHERE target_id = ?", id);
        assertEquals(me(admin).id(), row.get("actor_id"));
        assertEquals("TITLE_CORRECTED", row.get("action"));
        assertEquals(me(author).id(), row.get("target_user_id"));
        assertNull(row.get("reason"));
        assertEquals("Tenmoku ovr ash", row.get("old_value"));
        assertEquals("Tenmoku over ash", row.get("new_value"));
        // The author keeps the right to rename it, and that is no admin action.
        retitle(author, id, "Tenmoku over wood ash").expectStatus().isOk();
        assertEquals(1, corrections(id));
    }

    @Test void aSiteAdminCannotSeeOrCorrectSomeoneElsesDraftButRenamesTheirOwnUnlogged() {
        var author = user("Author"); var admin = siteAdmin();
        UUID draft = combination(author, "Hidden", true);
        assertEquals(statusAndBody(retitle(admin, UUID.randomUUID(), "Seen")), statusAndBody(retitle(admin, draft, "Seen")));
        assertEquals("Hidden", title(draft));
        UUID own = combination(admin, "Own", false);
        retitle(admin, own, "Own, renamed").expectStatus().isOk();
        assertEquals(0, corrections(own) + corrections(draft));
    }

    @Test void aSiteAdminCorrectsACombinationWhoseAuthorDeletedTheirAccount() {
        var author = user("Leaver"); var admin = siteAdmin();
        UUID id = combination(author, "Orphand", false);
        jdbc.update("UPDATE combination SET created_by = NULL WHERE id = ?", id);
        retitle(admin, id, "Orphaned").expectStatus().isOk();
        assertEquals("Orphaned", title(id));
        assertNull(jdbc.queryForObject("SELECT target_user_id FROM admin_action WHERE target_id = ?", UUID.class, id));
    }

    @Test void aPublishedCombinationKeepsATitleAndADraftMayLoseIt() {
        var author = user("Author"); var admin = siteAdmin();
        UUID published = combination(author, "Kept", false), draft = combination(author, "Draft title", true);
        for (String blank : List.of("", "   ", " "))
            assertError(retitle(author, published, blank), 400, CombinationService.TITLE_REQUIRED);
        assertError(retitle(admin, published, " "), 400, CombinationService.TITLE_REQUIRED);
        assertEquals("Kept", title(published));
        assertEquals("", body(retitle(author, draft, "  ").expectStatus().isOk(), TitleView.class).title());
        assertEquals("", title(draft));
        assertEquals(0, corrections(published));
    }

    @Test void theTitleFollowsTheFreeTextRulesAndTheLengthLimit() {
        var author = user("Author");
        UUID id = combination(author, "Plain", false);
        assertError(retitle(author, id, "Bad\u0000title"), 400, Text.INVALID_TEXT);
        assertError(retitle(author, id, "Line\nbreak"), 400, Text.INVALID_TEXT);
        retitle(author, id, "x".repeat(161)).expectStatus().isBadRequest();
        retitle(author, id, null).expectStatus().isBadRequest();
        // Refused before any lookup: an unknown ID with a bad title answers 400 too.
        assertError(retitle(author, UUID.randomUUID(), "Bad\u0007"), 400, Text.INVALID_TEXT);
        assertEquals("Plain", title(id));
        retitle(author, id, "x".repeat(160)).expectStatus().isOk();
    }

    @Test void theSameTitleAnswersUnchangedAndWritesNothing() {
        var author = user("Author"); var admin = siteAdmin();
        UUID id = combination(author, "Same", false);
        long before = changes(id);
        assertEquals("Same", body(retitle(author, id, "Same").expectStatus().isOk(), TitleView.class).title());
        assertEquals("Same", body(retitle(admin, id, " Same ").expectStatus().isOk(), TitleView.class).title());
        assertEquals(before, changes(id));
        assertEquals(0, corrections(id));
    }

    @Test void theRenameReachesEveryClientInTheNextDelta() {
        var author = user("Author"); var reader = user("Reader");
        UUID id = combination(author, "Before", false);
        UUID draft = combination(author, "Draft before", true);
        var guestFrom = SyncApiTest.start(guest());
        var authorFrom = SyncApiTest.start(author);
        var readerFrom = SyncApiTest.start(reader);
        retitle(author, id, "After").expectStatus().isOk();
        retitle(author, draft, "Draft after").expectStatus().isOk();
        assertEquals("After", SyncApiTest.upserted(SyncApiTest.delta(guest(), guestFrom), id).orElseThrow().title());
        var own = SyncApiTest.delta(author, authorFrom);
        assertEquals("After", SyncApiTest.upserted(own, id).orElseThrow().title());
        assertEquals("Draft after", SyncApiTest.upserted(own, draft).orElseThrow().title());
        var other = SyncApiTest.delta(reader, readerFrom);
        assertEquals("After", SyncApiTest.upserted(other, id).orElseThrow().title());
        assertTrue(SyncApiTest.upserted(other, draft).isEmpty(), "a draft's rename reaches only its author");
    }
}
