package eu.glazelab.api.controller;

import eu.glazelab.api.dto.ImageDtos.ImageView;
import eu.glazelab.api.model.ImageKind;
import eu.glazelab.api.support.ApiTestBase;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.client.RestTestClient;
import static org.junit.jupiter.api.Assertions.*;

/** PUT /api/images/{id}/kind: whoever may delete a photo (ImageRecord.deletableBy) may change its type, nobody else. */
class ImageKindApiTest extends ApiTestBase {

    /** Uploader and member of a club the club admin created; a plain member; an outsider; a site admin. */
    private record People(RestTestClient uploader, RestTestClient clubAdmin, RestTestClient member,
            RestTestClient other, RestTestClient siteAdmin, UUID club, UUID combination, UUID glaze) {
        Map<String, RestTestClient> byRole() {
            return Map.of("uploader", uploader, "clubAdmin", clubAdmin, "member", member, "other", other, "siteAdmin", siteAdmin);
        }
    }

    private People people() {
        var uploader = user("KindUploader");
        var member = user("KindMember");
        var clubAdmin = user("KindClubAdmin");
        UUID club = createClub(clubAdmin, unique("Kind club"));
        for (var joining : List.of(uploader, member)) joinClub(clubAdmin, club, joining);
        UUID glaze = addGlaze(uploader, unique("Kind maker"), "Blue", "K-1");
        return new People(uploader, clubAdmin, member, user("KindOther"), siteAdmin(), club,
                addCombination(uploader, unique("Kind combination"), glaze), glaze);
    }

    private static UUID photo(People p, String visibility) {
        return uploadTile(p.uploader(), p.combination(), visibility, visibility.equals("CLUB") ? p.club() : null);
    }

    static RestTestClient.ResponseSpec change(RestTestClient client, UUID id, String kind) {
        return client.put().uri("/api/images/{id}/kind", id).body(Map.of("kind", kind)).exchange();
    }

    private String kindOf(UUID image) {
        return jdbc.queryForObject("SELECT kind FROM image WHERE id = ?", String.class, image);
    }

    @Test void theDeletionRuleDecidesWhoMayChangeTheType() {
        var p = people();
        // Expected status per visibility, then per role: exactly the DELETE matrix of ImageRightsApiTest.
        Map<String, Map<String, Integer>> expected = Map.of(
                "PUBLIC", Map.of("uploader", 200, "clubAdmin", 403, "member", 403, "other", 403, "siteAdmin", 200),
                "CLUB", Map.of("uploader", 200, "clubAdmin", 200, "member", 403, "other", 404, "siteAdmin", 200),
                "PRIVATE", Map.of("uploader", 200, "clubAdmin", 404, "member", 404, "other", 404, "siteAdmin", 200));
        expected.forEach((visibility, statuses) -> statuses.forEach((role, status) -> {
            UUID id = photo(p, visibility);
            var response = change(p.byRole().get(role), id, "FINISHED");
            String cell = visibility + " by " + role;
            switch (status) {
                case 200 -> {
                    ImageView view = body(response.expectStatus().isOk(), ImageView.class);
                    assertEquals(ImageKind.FINISHED, view.kind(), cell);
                    assertEquals(id, view.id(), cell);
                    assertTrue(view.canDelete(), cell);
                    assertEquals("FINISHED", kindOf(id), cell);
                }
                case 403 -> {
                    assertError(response, 403, ImageController.KIND_RIGHTS_REQUIRED);
                    assertEquals("TEST_TILE", kindOf(id), cell);
                }
                default -> {
                    assertError(response, 404, ImageController.IMAGE_NOT_FOUND);
                    assertEquals("TEST_TILE", kindOf(id), cell);
                }
            }
        }));
    }

    @Test void guestsAndUnknownPhotosAreRefused() {
        var p = people();
        UUID id = photo(p, "PUBLIC");
        assertError(change(guest(), id, "FINISHED"), 401, eu.glazelab.api.security.CurrentUser.SIGN_IN_REQUIRED);
        assertError(change(p.siteAdmin(), UUID.randomUUID(), "FINISHED"), 404, ImageController.IMAGE_NOT_FOUND);
        assertError(change(p.uploader(), UUID.randomUUID(), "FINISHED"), 404, ImageController.IMAGE_NOT_FOUND);
        assertEquals("TEST_TILE", kindOf(id));
    }

    @Test void anInvisiblePhotoAnswersLikeAnUnknownOneWhateverTheBody() {
        var p = people();
        UUID hidden = photo(p, "PRIVATE");
        String unknown = statusAndBody(change(p.other(), UUID.randomUUID(), "FINISHED"));
        assertEquals(unknown, statusAndBody(change(p.other(), hidden, "FINISHED")));
        assertEquals(unknown, statusAndBody(change(p.other(), hidden, "PACKAGING")));
    }

    @Test void anUnknownOrMissingKindIsABadRequest() {
        var p = people();
        UUID id = photo(p, "PUBLIC");
        change(p.uploader(), id, "JAR").expectStatus().isBadRequest();
        p.uploader().put().uri("/api/images/{id}/kind", id).body(Map.of()).exchange().expectStatus().isBadRequest();
        var nullKind = new HashMap<String, Object>();
        nullKind.put("kind", null);
        p.uploader().put().uri("/api/images/{id}/kind", id).body(nullKind).exchange().expectStatus().isBadRequest();
        p.uploader().put().uri("/api/images/{id}/kind", id).body(Map.of("kind", 1)).exchange().expectStatus().isBadRequest();
        assertEquals("TEST_TILE", kindOf(id));
    }

    @Test void packagingIsForAGlazesOwnPhotosOnly() {
        var p = people();
        UUID tile = photo(p, "CLUB");
        for (var actor : List.of(p.uploader(), p.clubAdmin(), p.siteAdmin()))
            assertError(change(actor, tile, "PACKAGING"), 400, ImageController.PACKAGING_GLAZE_ONLY);
        assertEquals("TEST_TILE", kindOf(tile));
        UUID glazePhoto = created(uploadGlazeImage(p.uploader(), p.glaze(), Map.of("visibility", "CLUB",
                "clubId", p.club().toString(), "kind", "TEST_TILE"), PNG));
        for (var actor : List.of(p.uploader(), p.clubAdmin(), p.siteAdmin())) {
            assertEquals(ImageKind.PACKAGING, body(change(actor, glazePhoto, "PACKAGING").expectStatus().isOk(), ImageView.class).kind());
            assertEquals(ImageKind.FINISHED, body(change(actor, glazePhoto, "FINISHED").expectStatus().isOk(), ImageView.class).kind());
        }
        assertError(change(p.member(), glazePhoto, "PACKAGING"), 403, ImageController.KIND_RIGHTS_REQUIRED);
    }

    @Test void workInProgressIsForACombinationsPhotosOnly() {
        var p = people();
        // Upload: a combination takes it, a glaze refuses it before anything is stored.
        ImageView piece = body(upload(p.uploader(), p.combination(), Map.of("visibility", "PUBLIC", "kind", "IN_PROGRESS"), PNG)
                .expectStatus().isOk(), ImageView.class);
        assertEquals(ImageKind.IN_PROGRESS, piece.kind());
        assertEquals(List.of(ImageKind.IN_PROGRESS), combination(p.member(), p.combination()).orElseThrow().images().stream()
                .filter(i -> i.id().equals(piece.id())).map(ImageView::kind).toList());
        int glazePhotos = jdbc.queryForObject("SELECT COUNT(*) FROM image WHERE glaze_id = ?", Integer.class, p.glaze());
        assertError(uploadGlazeImage(p.uploader(), p.glaze(), Map.of("visibility", "PUBLIC", "kind", "IN_PROGRESS"), PNG),
                400, ImageController.IN_PROGRESS_COMBINATION_ONLY);
        assertEquals(glazePhotos, jdbc.queryForObject("SELECT COUNT(*) FROM image WHERE glaze_id = ?", Integer.class, p.glaze()));
        // Type changes: every kind but packaging on a combination photo, every kind but work in progress on a glaze photo.
        UUID tile = photo(p, "CLUB");
        for (String kind : List.of("IN_PROGRESS", "FINISHED", "IN_PROGRESS", "TEST_TILE"))
            assertEquals(ImageKind.valueOf(kind), body(change(p.clubAdmin(), tile, kind).expectStatus().isOk(), ImageView.class).kind());
        UUID glazePhoto = created(uploadGlazeImage(p.uploader(), p.glaze(), Map.of("visibility", "PUBLIC", "kind", "TEST_TILE"), PNG));
        for (var actor : List.of(p.uploader(), p.siteAdmin()))
            assertError(change(actor, glazePhoto, "IN_PROGRESS"), 400, ImageController.IN_PROGRESS_COMBINATION_ONLY);
        assertError(p.uploader().patch().uri("/api/images/{id}", glazePhoto).body(Map.of("kind", "IN_PROGRESS")).exchange(),
                400, ImageController.IN_PROGRESS_COMBINATION_ONLY);
        assertEquals("TEST_TILE", kindOf(glazePhoto));
        assertEquals(ImageKind.IN_PROGRESS, body(p.uploader().patch().uri("/api/images/{id}", tile).body(Map.of("kind", "IN_PROGRESS"))
                .exchange().expectStatus().isOk(), ImageView.class).kind());
    }

    @Test void theSameKindAnswersUnchangedAndRecordsNothing() {
        var p = people();
        UUID id = photo(p, "PUBLIC");
        var before = jdbc.queryForMap("SELECT kind, storage_key, created_at, caption, fired_on, visibility FROM image WHERE id = ?", id);
        long top = top();
        ImageView same = body(change(p.uploader(), id, "TEST_TILE").expectStatus().isOk(), ImageView.class);
        assertEquals(ImageKind.TEST_TILE, same.kind());
        assertEquals(before, jdbc.queryForMap("SELECT kind, storage_key, created_at, caption, fired_on, visibility FROM image WHERE id = ?", id));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM change_log WHERE item_id = ? AND revision > ?", Integer.class,
                p.combination(), top));
    }

    @Test void aChangeKeepsIdUrlBytesVisibilityAndDetails() {
        var p = people();
        UUID id = created(upload(p.uploader(), p.combination(), Map.of("visibility", "PUBLIC", "kind", "TEST_TILE",
                "caption", "Two coats", "firedOn", "2026-10-18"), PNG));
        var before = jdbc.queryForMap("SELECT storage_key, created_at, caption, fired_on, visibility FROM image WHERE id = ?", id);
        ImageView changed = body(change(p.siteAdmin(), id, "FINISHED").expectStatus().isOk(), ImageView.class);
        assertEquals("/api/images/" + id, changed.url());
        assertEquals("Two coats", changed.caption());
        assertEquals(before, jdbc.queryForMap("SELECT storage_key, created_at, caption, fired_on, visibility FROM image WHERE id = ?", id));
        assertFalse(changed.own());
    }

    @Test void theNextDeltaOfEveryoneWhoSeesThePhotoCarriesTheNewType() {
        var p = people();
        UUID publicPhoto = photo(p, "PUBLIC");
        UUID clubPhoto = photo(p, "CLUB");
        var memberFrom = SyncApiTest.start(p.member());
        var guestFrom = SyncApiTest.start(guest());
        change(p.siteAdmin(), publicPhoto, "FINISHED").expectStatus().isOk();
        change(p.clubAdmin(), clubPhoto, "FINISHED").expectStatus().isOk();
        var memberDelta = SyncApiTest.delta(p.member(), memberFrom);
        var images = SyncApiTest.upserted(memberDelta, p.combination()).orElseThrow().images();
        assertEquals(Map.of(publicPhoto, ImageKind.FINISHED, clubPhoto, ImageKind.FINISHED),
                Map.of(images.get(0).id(), images.get(0).kind(), images.get(1).id(), images.get(1).kind()));
        var guestImages = SyncApiTest.upserted(SyncApiTest.delta(guest(), guestFrom), p.combination()).orElseThrow().images();
        assertEquals(List.of(publicPhoto), guestImages.stream().map(ImageView::id).toList());
        assertEquals(ImageKind.FINISHED, guestImages.getFirst().kind());
    }

    private long top() {
        Long revision = jdbc.queryForObject("SELECT MAX(revision) FROM change_log", Long.class);
        return revision == null ? 0 : revision;
    }
}
