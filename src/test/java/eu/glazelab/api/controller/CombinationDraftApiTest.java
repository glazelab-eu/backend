package eu.glazelab.api.controller;

import eu.glazelab.api.controller.FavoriteController.FavoritesView;
import eu.glazelab.api.dto.GlazeDtos.GlazeInUseView;
import eu.glazelab.api.service.CombinationService;
import eu.glazelab.api.support.ApiTestBase;
import eu.glazelab.api.support.TestImages;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.client.RestTestClient;
import static org.junit.jupiter.api.Assertions.*;

/** Drafts exist only for their author: every listing and lookup answers others and guests as if unknown. */
class CombinationDraftApiTest extends ApiTestBase {

    private static Map<String, Object> glaze(UUID id) { return Map.of("glazeId", id, "coats", 2); }
    private static Map<String, Object> custom(String label) { return Map.of("type", "CUSTOM", "label", label); }
    private static Map<String, Object> body(String title, List<?> steps, Boolean draft) {
        var body = new HashMap<String, Object>(Map.of("title", title, "steps", steps));
        if (draft != null) body.put("draft", draft);
        return body;
    }
    private static RestTestClient.ResponseSpec create(RestTestClient client, String title, List<?> steps, Boolean draft) {
        return client.post().uri("/api/combinations").body(body(title, steps, draft)).exchange();
    }
    private static UUID draft(RestTestClient client, String title, List<?> steps) {
        return created(create(client, title, steps, true));
    }
    private static RestTestClient.ResponseSpec publish(RestTestClient client, UUID id) {
        return client.post().uri("/api/combinations/{id}/publish", id).exchange();
    }
    private static FavoritesView favorites(RestTestClient client) {
        return body(client.get().uri("/api/favorites").exchange().expectStatus().isOk(), FavoritesView.class);
    }
    private static void error(RestTestClient.ResponseSpec response, int status, String message) {
        response.expectStatus().isEqualTo(status).expectBody().jsonPath("$.error").isEqualTo(message);
    }

    @Test void aDraftIsListedOnlyForItsAuthorAndMarked() {
        var author = user("Author"); var other = user("Other");
        UUID g = addGlaze(author, unique("Draft"), "Blue", "PC-20");
        UUID id = draft(author, unique("Draft"), List.of(glaze(g)));
        UUID published = addCombination(author, unique("Published"), g);

        var own = combination(author, id).orElseThrow();
        assertTrue(own.draft()); assertTrue(own.own()); assertFalse(own.stepsLocked());
        assertFalse(combination(author, published).orElseThrow().draft());
        for (var client : List.of(other, guest())) {
            assertTrue(combination(client, id).isEmpty());
            assertFalse(combination(client, published).orElseThrow().draft());
        }
        // The default (no draft field, or false) keeps publishing directly.
        UUID explicit = created(create(author, unique("Explicit"), List.of(glaze(g)), false));
        assertFalse(combination(other, explicit).orElseThrow().draft());
    }

    @Test void othersAndGuestsGetNotFoundForEveryDraftEndpoint() {
        var author = user("Author"); var other = user("Other"); var guest = guest();
        UUID id = draft(author, unique("Secret"), List.of(custom("Firing")));
        var edit = body("Changed", List.of(custom("Changed")), null);
        var tags = Map.of("add", List.of("leak"));
        var photo = Map.of("visibility", "PUBLIC", "kind", "TEST_TILE");

        other.put().uri("/api/combinations/{id}", id).body(edit).exchange().expectStatus().isNotFound();
        other.delete().uri("/api/combinations/{id}", id).exchange().expectStatus().isNotFound();
        publish(other, id).expectStatus().isNotFound();
        other.patch().uri("/api/combinations/{id}/tags", id).body(tags).exchange().expectStatus().isNotFound();
        other.put().uri("/api/favorites/combinations/{id}", id).exchange().expectStatus().isNotFound();
        upload(other, id, photo, TestImages.png(20, 20)).expectStatus().isNotFound();
        assertEquals(List.of(), favorites(other).combinationIds());

        guest.put().uri("/api/combinations/{id}", id).body(edit).exchange().expectStatus().isUnauthorized();
        guest.delete().uri("/api/combinations/{id}", id).exchange().expectStatus().isUnauthorized();
        publish(guest, id).expectStatus().isUnauthorized();
        guest.patch().uri("/api/combinations/{id}/tags", id).body(tags).exchange().expectStatus().isUnauthorized();
        upload(guest, id, photo, TestImages.png(20, 20)).expectStatus().isUnauthorized();

        // Nothing changed: the draft is still the author's, untagged and unpublished.
        var own = combination(author, id).orElseThrow();
        assertTrue(own.draft()); assertEquals(List.of(), own.tags()); assertEquals("Firing", own.steps().get(0).label());
        // A published combination answers the same requests as before (403 for edits, tags for anyone).
        UUID shared = created(create(author, unique("Shared"), List.of(custom("Firing")), null));
        other.put().uri("/api/combinations/{id}", shared).body(edit).exchange().expectStatus().isForbidden();
        publish(other, shared).expectStatus().isForbidden();
    }

    @Test void theAuthorTagsFavouritesEditsAndDeletesTheirDraft() {
        var author = user("Author");
        UUID id = draft(author, "", List.of());
        author.patch().uri("/api/combinations/{id}/tags", id).body(Map.of("add", List.of("wip")))
                .exchange().expectStatus().isOk();
        assertEquals(List.of("wip"), combination(author, id).orElseThrow().tags());
        author.put().uri("/api/favorites/combinations/{id}", id).exchange().expectStatus().is2xxSuccessful();
        assertEquals(List.of(id), favorites(author).combinationIds());
        // Drafts stay drafts on edit and may stay incomplete.
        author.put().uri("/api/combinations/{id}", id).body(body(" ", List.of(), null)).exchange().expectStatus().isNoContent();
        author.put().uri("/api/combinations/{id}", id).body(body("Halfway", List.of(custom("Bisque")), false))
                .exchange().expectStatus().isNoContent();
        var own = combination(author, id).orElseThrow();
        // The edit sent no tags, so it replaced them like any edit.
        assertTrue(own.draft()); assertEquals("Halfway", own.title()); assertEquals(List.of(), own.tags());
        author.delete().uri("/api/combinations/{id}", id).exchange().expectStatus().isNoContent();
        assertTrue(combination(author, id).isEmpty());
        assertEquals(List.of(), favorites(author).combinationIds());
    }

    @Test void everySavedStepSatisfiesItsTypeAndPublishingNeedsATitleAndAStep() {
        var author = user("Author");
        UUID g = addGlaze(author, unique("Steps"), "Blue", "PC-20");
        // A draft may lack a title and steps, never a valid step shape.
        create(author, "", List.of(Map.of("coats", 2)), true).expectStatus().isBadRequest();
        create(author, "", List.of(Map.of("type", "CUSTOM")), true).expectStatus().isBadRequest();
        create(author, "", List.of(Map.of("type", "CUSTOM", "label", "Firing", "glazeId", g)), true).expectStatus().isBadRequest();
        error(create(author, " ", List.of(glaze(g)), null), 400, CombinationService.TITLE_REQUIRED);
        error(create(author, "Title", List.of(), false), 400, CombinationService.STEP_REQUIRED);

        UUID untitled = draft(author, "", List.of(glaze(g)));
        error(publish(author, untitled), 400, CombinationService.TITLE_REQUIRED);
        UUID empty = draft(author, "Empty", List.of());
        error(publish(author, empty), 400, CombinationService.STEP_REQUIRED);
        assertTrue(combination(author, untitled).orElseThrow().draft());
        assertTrue(combination(author, empty).orElseThrow().draft());

        // A custom-only sequence is complete.
        UUID firing = draft(author, unique("Firing only"), List.of(custom("Bisque"), custom("Glaze firing")));
        publish(author, firing).expectStatus().isNoContent();
        publish(author, firing).expectStatus().isNoContent();
        var seen = combination(user("Viewer"), firing).orElseThrow();
        assertFalse(seen.draft()); assertEquals(2, seen.steps().size());
        // Once published, the normal rules apply again: no blank title, no empty steps.
        author.put().uri("/api/combinations/{id}", firing).body(body("", List.of(custom("Bisque")), true))
                .exchange().expectStatus().isBadRequest();
        author.put().uri("/api/combinations/{id}", firing).body(body("Firing", List.of(), true))
                .exchange().expectStatus().isBadRequest();
        assertFalse(combination(author, firing).orElseThrow().draft());
    }

    @Test void glazeRemovalIsRefusedByADraftWithoutNamingIt() {
        var author = user("Author");
        String email = email("Admin");
        var admin = signUp(email);
        jdbc.update("update app_user set site_admin = true where email = ?", email);
        UUID g = addGlaze(author, unique("Drafted"), "Blue", "PC-20");
        draft(author, unique("Hidden"), List.of(glaze(g)));
        var conflict = body(admin.delete().uri("/api/glazes/{id}", g).exchange().expectStatus().isEqualTo(409), GlazeInUseView.class);
        assertEquals(List.of(), conflict.combinations());
    }

    @Test void deletingTheAccountRemovesItsDraftsAndKeepsPublishedCombinations() {
        var author = user("Leaving");
        UUID id = me(author).id();
        UUID g = addGlaze(author, unique("Leaving"), "Blue", "PC-20");
        UUID draft = draft(author, unique("Unfinished"), List.of(glaze(g), custom("Bisque")));
        UUID published = addCombination(author, unique("Finished"), g);
        author.post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange().expectStatus().is2xxSuccessful();
        assertEquals(0, jdbc.queryForObject("select count(*) from combination where id = ?", Integer.class, draft));
        assertEquals(0, jdbc.queryForObject("select count(*) from combination_step where combination_id = ?", Integer.class, draft));
        var kept = combination(guest(), published).orElseThrow();
        assertNull(kept.authorId());
        assertEquals(0, jdbc.queryForObject("select count(*) from combination where created_by = ?", Integer.class, id));
    }

    @Test void publishedAtIsNullForDraftsAndEqualsCreationForADirectCreate() {
        var author = user("Author");
        UUID g = addGlaze(author, unique("Dated"), "Blue", "PC-20");
        var draft = combination(author, draft(author, unique("Draft"), List.of(glaze(g)))).orElseThrow();
        assertNull(draft.publishedAt());
        var direct = combination(author, addCombination(author, unique("Direct"), g)).orElseThrow();
        assertNotNull(direct.publishedAt());
        assertEquals(direct.createdAt(), direct.publishedAt());
    }

    @Test void publishingSetsPublishedAtOnceAndLaterPublishesAndEditsKeepIt() {
        var author = user("Author");
        UUID g = addGlaze(author, unique("Once"), "Blue", "PC-20");
        UUID id = draft(author, unique("Once"), List.of(glaze(g)));
        jdbc.update("update combination set created_at = ? where id = ?",
                java.time.OffsetDateTime.now().minusDays(30), id);
        publish(author, id).expectStatus().isNoContent();
        var first = combination(author, id).orElseThrow();
        assertNotNull(first.publishedAt());
        // Started 30 days ago, published now: the publication is the new entry.
        assertTrue(first.publishedAt().isAfter(first.createdAt().plus(java.time.Duration.ofDays(29))));
        publish(author, id).expectStatus().isNoContent();
        author.put().uri("/api/combinations/{id}", id).body(body("Edited", List.of(glaze(g)), null))
                .exchange().expectStatus().isNoContent();
        var later = combination(author, id).orElseThrow();
        assertEquals("Edited", later.title());
        assertEquals(first.publishedAt(), later.publishedAt());
        assertEquals(first.createdAt(), later.createdAt());
        // Every viewer sees the same date.
        assertEquals(first.publishedAt(), combination(guest(), id).orElseThrow().publishedAt());
    }

    @Test void theListingIsNewestFirstByPublicationAndDraftsByCreation() {
        var author = user("Author");
        UUID g = addGlaze(author, unique("Order"), "Blue", "PC-20");
        UUID early = addCombination(author, unique("Early"), g);
        UUID lateDraft = draft(author, unique("Late draft"), List.of(glaze(g)));
        UUID olderDraft = draft(author, unique("Older draft"), List.of(glaze(g)));
        var now = java.time.OffsetDateTime.now();
        jdbc.update("update combination set created_at = ? where id = ?", now.minusDays(60), olderDraft);
        jdbc.update("update combination set created_at = ? where id = ?", now.minusDays(30), lateDraft);
        // Drafts stay where their creation puts them: after the published one.
        assertEquals(List.of(early, lateDraft, olderDraft), ids(author, early, lateDraft, olderDraft));
        // A draft started long ago and published now is the newest entry.
        publish(author, olderDraft).expectStatus().isNoContent();
        assertEquals(List.of(olderDraft, early, lateDraft), ids(author, early, lateDraft, olderDraft));
        assertEquals(List.of(olderDraft, early), ids(guest(), early, lateDraft, olderDraft));
    }

    private static List<UUID> ids(RestTestClient client, UUID... wanted) {
        var keep = List.of(wanted);
        return combinations(client).stream().map(view -> view.id()).filter(keep::contains).toList();
    }
}
