package eu.glazelab.api.controller;

import eu.glazelab.api.dto.ImageDtos.ImageView;
import eu.glazelab.api.model.PhotoDetails;
import eu.glazelab.api.support.ApiTestBase;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.client.RestTestClient;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A photo's focus point (where covers centre their crop): optional on upload, changed by PUT /api/images/{id}/focus under
 * the kind change's rights (ImageRecord.deletableBy), recorded for sync.
 */
class ImageFocusApiTest extends ApiTestBase {

    private record People(RestTestClient uploader, RestTestClient clubAdmin, RestTestClient member,
            RestTestClient other, RestTestClient siteAdmin, UUID club, UUID combination, UUID glaze) {
        Map<String, RestTestClient> byRole() {
            return Map.of("uploader", uploader, "clubAdmin", clubAdmin, "member", member, "other", other, "siteAdmin", siteAdmin);
        }
    }

    private People people() {
        var uploader = user("FocusUploader");
        var member = user("FocusMember");
        var clubAdmin = user("FocusClubAdmin");
        UUID club = createClub(clubAdmin, unique("Focus club"));
        for (var joining : List.of(uploader, member)) joinClub(clubAdmin, club, joining);
        UUID glaze = addGlaze(uploader, unique("Focus maker"), "Blue", "F-1");
        return new People(uploader, clubAdmin, member, user("FocusOther"), siteAdmin(), club,
                addCombination(uploader, unique("Focus combination"), glaze), glaze);
    }

    private static UUID photo(People p, String visibility) {
        return uploadTile(p.uploader(), p.combination(), visibility, visibility.equals("CLUB") ? p.club() : null);
    }

    static RestTestClient.ResponseSpec focus(RestTestClient client, UUID id, Integer x, Integer y) {
        var body = new HashMap<String, Object>();
        body.put("focusX", x);
        body.put("focusY", y);
        return client.put().uri("/api/images/{id}/focus", id).body(body).exchange();
    }

    /** The stored focus as {x, y}; nulls for the centre. */
    private List<Integer> stored(UUID image) {
        var row = jdbc.queryForMap("SELECT focus_x, focus_y FROM image WHERE id = ?", image);
        return Arrays.asList(row.get("focus_x") == null ? null : ((Number) row.get("focus_x")).intValue(),
                row.get("focus_y") == null ? null : ((Number) row.get("focus_y")).intValue());
    }

    private static final List<Integer> CENTRE = Arrays.asList(null, null);

    @Test void anUploadTakesAnOptionalFocusAndAnOldPhotoHasNone() {
        var p = people();
        ImageView plain = body(upload(p.uploader(), p.combination(), Map.of("visibility", "PUBLIC", "kind", "TEST_TILE"), PNG)
                .expectStatus().isOk(), ImageView.class);
        assertNull(plain.focusX());
        assertNull(plain.focusY());
        ImageView focused = body(upload(p.uploader(), p.combination(), Map.of("visibility", "PUBLIC", "kind", "TEST_TILE",
                "focusX", "0", "focusY", "100"), PNG).expectStatus().isOk(), ImageView.class);
        assertEquals(List.of(0, 100), List.of(focused.focusX(), focused.focusY()));
        assertEquals(List.of(0, 100), stored(focused.id()));
        ImageView onGlaze = body(uploadGlazeImage(p.uploader(), p.glaze(), Map.of("visibility", "PUBLIC", "kind", "PACKAGING",
                "focusX", "35", "focusY", "62"), PNG).expectStatus().isOk(), ImageView.class);
        assertEquals(List.of(35, 62), List.of(onGlaze.focusX(), onGlaze.focusY()));
        // Every viewer who sees the photo gets its focus.
        var seen = combination(p.other(), p.combination()).orElseThrow().images().stream()
                .filter(image -> image.id().equals(focused.id())).findFirst().orElseThrow();
        assertEquals(List.of(0, 100), List.of(seen.focusX(), seen.focusY()));
    }

    @Test void anUploadWithHalfAFocusOrOneOutOfRangeIsRefusedBeforeAnythingIsStored() {
        var p = people();
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM image WHERE combination_id = ?", Integer.class, p.combination());
        for (var fields : List.of(Map.of("focusX", "50"), Map.of("focusY", "50"), Map.of("focusX", "-1", "focusY", "50"),
                Map.of("focusX", "50", "focusY", "101"))) {
            var all = new HashMap<>(Map.of("visibility", "PUBLIC", "kind", "TEST_TILE"));
            all.putAll(fields);
            assertError(upload(p.uploader(), p.combination(), all, PNG), 400, PhotoDetails.FOCUS_INVALID);
            assertError(uploadGlazeImage(p.uploader(), p.glaze(), all, PNG), 400, PhotoDetails.FOCUS_INVALID);
        }
        upload(p.uploader(), p.combination(), Map.of("visibility", "PUBLIC", "kind", "TEST_TILE", "focusX", "half", "focusY", "1"), PNG)
                .expectStatus().isBadRequest();
        assertEquals(before, jdbc.queryForObject("SELECT COUNT(*) FROM image WHERE combination_id = ?", Integer.class, p.combination()));
    }

    @Test void theDeletionRuleDecidesWhoMayChangeTheFocus() {
        var p = people();
        Map<String, Map<String, Integer>> expected = Map.of(
                "PUBLIC", Map.of("uploader", 200, "clubAdmin", 403, "member", 403, "other", 403, "siteAdmin", 200),
                "CLUB", Map.of("uploader", 200, "clubAdmin", 200, "member", 403, "other", 404, "siteAdmin", 200),
                "PRIVATE", Map.of("uploader", 200, "clubAdmin", 404, "member", 404, "other", 404, "siteAdmin", 200));
        expected.forEach((visibility, statuses) -> statuses.forEach((role, status) -> {
            UUID id = photo(p, visibility);
            var response = focus(p.byRole().get(role), id, 20, 80);
            String cell = visibility + " by " + role;
            switch (status) {
                case 200 -> {
                    ImageView view = body(response.expectStatus().isOk(), ImageView.class);
                    assertEquals(List.of(20, 80), List.of(view.focusX(), view.focusY()), cell);
                    assertEquals(id, view.id(), cell);
                    assertEquals(List.of(20, 80), stored(id), cell);
                }
                case 403 -> {
                    assertError(response, 403, ImageController.FOCUS_RIGHTS_REQUIRED);
                    assertEquals(CENTRE, stored(id), cell);
                }
                default -> {
                    assertError(response, 404, ImageController.IMAGE_NOT_FOUND);
                    assertEquals(CENTRE, stored(id), cell);
                }
            }
        }));
    }

    @Test void aDraftsPhotoIsTheAuthorsOnly() {
        var p = people();
        UUID draft = created(p.uploader().post().uri("/api/combinations").body(Map.of(
                "title", unique("Focus draft"), "draft", true, "steps", List.of())).exchange());
        UUID photo = uploadTile(p.uploader(), draft, "PUBLIC", null);
        for (var actor : List.of(p.siteAdmin(), p.clubAdmin(), p.other()))
            assertError(focus(actor, photo, 10, 10), 404, ImageController.IMAGE_NOT_FOUND);
        assertEquals(List.of(10, 10), stored(body(focus(p.uploader(), photo, 10, 10).expectStatus().isOk(), ImageView.class).id()));
    }

    @Test void guestsUnknownPhotosAndBadBodiesAreRefused() {
        var p = people();
        UUID id = photo(p, "PUBLIC");
        assertError(focus(guest(), id, 1, 1), 401, eu.glazelab.api.security.CurrentUser.SIGN_IN_REQUIRED);
        assertError(focus(p.uploader(), UUID.randomUUID(), 1, 1), 404, ImageController.IMAGE_NOT_FOUND);
        for (var half : List.of(Arrays.asList(5, null), Arrays.asList(null, 5), List.of(-1, 5), List.of(5, 101), List.of(101, 101)))
            assertError(focus(p.uploader(), id, half.get(0), half.get(1)), 400, PhotoDetails.FOCUS_INVALID);
        p.uploader().put().uri("/api/images/{id}/focus", id).body(Map.of("focusX", "left", "focusY", 3)).exchange().expectStatus().isBadRequest();
        assertEquals(CENTRE, stored(id));
        // A hidden photo answers like an unknown one whatever the body says.
        UUID hidden = photo(p, "PRIVATE");
        String unknown = statusAndBody(focus(p.other(), UUID.randomUUID(), 1, 1));
        assertEquals(unknown, statusAndBody(focus(p.other(), hidden, 1, 1)));
        assertEquals(unknown, statusAndBody(focus(p.other(), hidden, 500, null)));
    }

    @Test void nullsResetToTheCentreAndTheSameFocusRecordsNothing() {
        var p = people();
        UUID id = photo(p, "PUBLIC");
        focus(p.uploader(), id, 30, 40).expectStatus().isOk();
        var before = jdbc.queryForMap("SELECT storage_key, created_at, kind, caption, fired_on, visibility, focus_x, focus_y FROM image WHERE id = ?", id);
        long top = top();
        ImageView same = body(focus(p.uploader(), id, 30, 40).expectStatus().isOk(), ImageView.class);
        assertEquals(List.of(30, 40), List.of(same.focusX(), same.focusY()));
        assertEquals(before, jdbc.queryForMap("SELECT storage_key, created_at, kind, caption, fired_on, visibility, focus_x, focus_y FROM image WHERE id = ?", id));
        assertEquals(0, changesOf(p.combination(), top));
        ImageView reset = body(focus(p.siteAdmin(), id, null, null).expectStatus().isOk(), ImageView.class);
        assertNull(reset.focusX());
        assertNull(reset.focusY());
        assertEquals("/api/images/" + id, reset.url());
        assertEquals(CENTRE, stored(id));
        assertTrue(changesOf(p.combination(), top) > 0);
        long afterReset = top();
        p.uploader().put().uri("/api/images/{id}/focus", id).body(Map.of()).exchange().expectStatus().isOk();
        assertEquals(0, changesOf(p.combination(), afterReset));
    }

    @Test void leavingPublicKeepsTheFocus() {
        var p = people();
        UUID id = photo(p, "PUBLIC");
        focus(p.uploader(), id, 12, 88).expectStatus().isOk();
        ImageView moved = body(p.uploader().put().uri("/api/images/{id}/visibility", id).body(Map.of("visibility", "PRIVATE")).exchange()
                .expectStatus().isOk(), ImageView.class);
        assertNotEquals(id, moved.id());
        assertEquals(List.of(12, 88), List.of(moved.focusX(), moved.focusY()));
        assertEquals(List.of(12, 88), stored(moved.id()));
    }

    @Test void theNextDeltaOfEveryoneWhoSeesThePhotoCarriesTheNewFocus() {
        var p = people();
        UUID publicPhoto = photo(p, "PUBLIC");
        UUID clubPhoto = photo(p, "CLUB");
        var memberFrom = SyncApiTest.start(p.member());
        var guestFrom = SyncApiTest.start(guest());
        focus(p.uploader(), publicPhoto, 25, 75).expectStatus().isOk();
        focus(p.clubAdmin(), clubPhoto, 60, 10).expectStatus().isOk();
        var images = SyncApiTest.upserted(SyncApiTest.delta(p.member(), memberFrom), p.combination()).orElseThrow().images();
        Map<UUID, List<Integer>> seen = new HashMap<>();
        images.forEach(image -> seen.put(image.id(), List.of(image.focusX(), image.focusY())));
        assertEquals(Map.of(publicPhoto, List.of(25, 75), clubPhoto, List.of(60, 10)), seen);
        var guestImages = SyncApiTest.upserted(SyncApiTest.delta(guest(), guestFrom), p.combination()).orElseThrow().images();
        assertEquals(List.of(publicPhoto), guestImages.stream().map(ImageView::id).toList());
        assertEquals(List.of(25, 75), List.of(guestImages.getFirst().focusX(), guestImages.getFirst().focusY()));
    }

    private int changesOf(UUID item, long after) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM change_log WHERE item_id = ? AND revision > ?", Integer.class, item, after);
    }

    private long top() {
        Long revision = jdbc.queryForObject("SELECT MAX(revision) FROM change_log", Long.class);
        return revision == null ? 0 : revision;
    }
}
