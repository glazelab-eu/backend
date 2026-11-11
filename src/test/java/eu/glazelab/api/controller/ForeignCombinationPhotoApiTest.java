package eu.glazelab.api.controller;

import eu.glazelab.api.dto.ImageDtos.ImageView;
import eu.glazelab.api.model.ImageVisibility;
import eu.glazelab.api.security.CurrentUser;
import eu.glazelab.api.service.CombinationService;
import eu.glazelab.api.storage.ImageStorage;
import eu.glazelab.api.support.ApiTestBase;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.test.web.servlet.client.RestTestClient.ResponseSpec;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A photo on a combination its uploader does not own stays visible to the combination's author: PUBLIC, or CLUB of
 * a club the author is in. Only me and other clubs are refused at every write that sets visibility or club.
 */
class ForeignCombinationPhotoApiTest extends ApiTestBase {
    @Autowired ImageStorage storage;
    /** Ada authors the combination; Bea shares the club "shared" with her and alone is in "own". */
    private RestTestClient ada, bea;
    private UUID shared, own, combo;

    private void people() {
        ada = user("Ada");
        bea = user("Bea");
        shared = createClub(ada, unique("Shared"));
        joinClub(ada, shared, bea);
        own = createClub(bea, unique("Own"));
        combo = addCombination(ada, unique("Ada's"), addGlaze(ada, unique("Maker"), "Blue", "PC-20"));
    }

    private static ResponseSpec tile(RestTestClient client, UUID combination, String visibility, UUID clubId) {
        var fields = new HashMap<>(Map.of("visibility", visibility, "kind", "TEST_TILE"));
        if (clubId != null) fields.put("clubId", clubId.toString());
        return upload(client, combination, fields, PNG);
    }

    private static ResponseSpec change(RestTestClient client, UUID image, String visibility, UUID clubId) {
        var body = new HashMap<String, Object>(Map.of("visibility", visibility));
        if (clubId != null) body.put("clubId", clubId);
        return client.put().uri("/api/images/{id}/visibility", image).body(body).exchange();
    }

    private List<UUID> listed(RestTestClient client) {
        return combination(client, combo).orElseThrow().images().stream().map(ImageView::id).toList();
    }

    @Test void uploadsToSomeoneElsesCombinationThatItsAuthorCannotSeeAreRefused() {
        people();
        assertError(tile(bea, combo, "PRIVATE", null), 400, ImageController.AUTHOR_MUST_SEE);
        assertError(tile(bea, combo, "CLUB", own), 400, ImageController.AUTHOR_MUST_SEE);
        // Each file of the multi-file dialog is its own request: a refused one stores nothing, the next still goes in.
        UUID kept = created(tile(bea, combo, "PUBLIC", null));
        assertError(tile(bea, combo, "PRIVATE", null), 400, ImageController.AUTHOR_MUST_SEE);
        assertEquals(List.of(kept), listed(bea));
        assertEquals(List.of(kept), listed(ada));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM image WHERE combination_id = ?", Integer.class, combo));
        // A club the uploader is not in stays the membership refusal, whatever the author's clubs.
        assertError(tile(user("Cyd"), combo, "CLUB", shared), 403, CurrentUser.CLUB_MEMBERSHIP_REQUIRED);
    }

    @Test void everythingTheAuthorCanSeeIsAllowedAndKeepsLockingTheSteps() {
        people();
        UUID everyone = created(tile(bea, combo, "PUBLIC", null));
        UUID club = created(tile(bea, combo, "CLUB", shared));
        assertEquals(List.of(club, everyone), listed(ada));
        var view = combination(ada, combo).orElseThrow();
        assertTrue(view.stepsLocked());
        // Each of them alone locks the steps.
        ada.delete().uri("/api/images/{id}", club).exchange().expectStatus().isNoContent();
        assertTrue(combination(ada, combo).orElseThrow().stepsLocked());
    }

    @Test void theAuthorKeepsEveryOptionOnTheirOwnCombination() {
        people();
        UUID mine = createClub(ada, unique("Mine"));
        UUID hidden = created(tile(ada, combo, "PRIVATE", null));
        UUID club = created(tile(ada, combo, "CLUB", mine));
        UUID everyone = created(tile(ada, combo, "PUBLIC", null));
        assertEquals(List.of(everyone, club, hidden), listed(ada));
        assertFalse(combination(ada, combo).orElseThrow().stepsLocked());
        assertEquals(ImageVisibility.PRIVATE, body(change(ada, club, "PRIVATE", null).expectStatus().isOk(), ImageView.class).visibility());
        // Leaving PUBLIC moves the photo to a new ID, and to Only me on the author's own combination is fine.
        UUID moved = body(change(ada, everyone, "PRIVATE", null).expectStatus().isOk(), ImageView.class).id();
        assertNotEquals(everyone, moved);
        body(change(ada, moved, "CLUB", mine).expectStatus().isOk(), ImageView.class);
    }

    @Test void visibilityChangesOnSomeoneElsesCombinationKeepThePhotoVisibleToItsAuthor() {
        people();
        UUID photo = created(tile(bea, combo, "CLUB", shared));
        assertError(change(bea, photo, "PRIVATE", null), 400, ImageController.AUTHOR_MUST_SEE);
        assertError(change(bea, photo, "CLUB", own), 400, ImageController.AUTHOR_MUST_SEE);
        assertEquals(List.of(photo), listed(ada));
        // Club to Everyone and back to the shared club keep the ID.
        assertEquals(photo, body(change(bea, photo, "PUBLIC", null).expectStatus().isOk(), ImageView.class).id());
        UUID moved = body(change(bea, photo, "CLUB", shared).expectStatus().isOk(), ImageView.class).id();
        assertNotEquals(photo, moved);

        // Leaving PUBLIC for Only me or a club the author is not in is refused before any move: the ID stays.
        UUID everyone = created(tile(bea, combo, "PUBLIC", null));
        assertError(change(bea, everyone, "PRIVATE", null), 400, ImageController.AUTHOR_MUST_SEE);
        assertError(change(bea, everyone, "CLUB", own), 400, ImageController.AUTHOR_MUST_SEE);
        assertEquals(List.of(everyone, moved), listed(ada));
        assertEquals("PUBLIC", jdbc.queryForObject("SELECT visibility FROM image WHERE id = ?", String.class, everyone));
    }

    @Test void theDetailsEditCannotChangeVisibilityOrClub() {
        people();
        UUID photo = created(tile(bea, combo, "PUBLIC", null));
        var edited = body(bea.patch().uri("/api/images/{id}", photo)
                .body(Map.of("caption", "Glossy", "visibility", "PRIVATE", "clubId", own.toString()))
                .exchange().expectStatus().isOk(), ImageView.class);
        assertEquals(ImageVisibility.PUBLIC, edited.visibility());
        assertNull(edited.clubId());
        assertEquals(List.of(photo), listed(ada));
    }

    @Test void aCombinationWhoseAuthorDeletedTheirAccountKeepsEveryOption() {
        people();
        var dee = user("Dee");
        UUID orphan = addCombination(dee, unique("Dee's"), addGlaze(dee, unique("Maker"), "Red", "PC-40"));
        dee.post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange().expectStatus().is2xxSuccessful();
        UUID photo = created(tile(bea, orphan, "PRIVATE", null));
        assertEquals(ImageVisibility.CLUB, body(change(bea, photo, "CLUB", own).expectStatus().isOk(), ImageView.class).visibility());
    }

    @Test void glazePhotosKeepEveryOption() {
        people();
        UUID glaze = addGlaze(ada, unique("Maker"), "Green", "PC-30");
        uploadGlazeImage(bea, glaze, Map.of("visibility", "PRIVATE", "kind", "TEST_TILE"), PNG).expectStatus().isOk();
        uploadGlazeImage(bea, glaze, Map.of("visibility", "CLUB", "clubId", own.toString(), "kind", "TEST_TILE"), PNG)
                .expectStatus().isOk();
    }

    /**
     * {@code hasForeignPhotos} counts only the foreign photos the author can see. The rule above keeps every foreign
     * photo visible to the author today; a row that is not (written here with SQL) neither locks nor leaks.
     */
    @Test void aForeignPhotoTheAuthorCannotSeeDoesNotLockTheSteps() {
        people();
        UUID photo = created(tile(bea, combo, "PUBLIC", null));
        assertTrue(combination(ada, combo).orElseThrow().stepsLocked());
        jdbc.update("UPDATE image SET visibility = 'CLUB', club_id = ? WHERE id = ?", own, photo);
        var seen = combination(ada, combo).orElseThrow();
        assertFalse(seen.stepsLocked());
        assertTrue(seen.images().isEmpty());
        var steps = List.of(Map.of("type", "CUSTOM", "label", "Changed"));
        ada.put().uri("/api/combinations/{id}", combo).body(Map.of("title", "Changed", "steps", steps)).exchange()
                .expectStatus().isNoContent();
    }

    /**
     * 23-61: the author leaves the club a foreign CLUB photo was shared with. The photo stays with its club and stops
     * locking the author's steps; deleting the combination then removes it with a log entry like any photo.
     */
    @Test void aClubPhotoStopsLockingWhenTheAuthorLeavesAndGoesWithTheDeletedCombination() {
        people();
        UUID photo = created(tile(bea, combo, "CLUB", shared));
        assertTrue(combination(ada, combo).orElseThrow().stepsLocked());
        ada.put().uri("/api/clubs/{id}/members/{userId}", shared, me(bea).id()).body(Map.of("role", "ADMIN")).exchange().expectStatus().isOk();
        ada.delete().uri("/api/clubs/{id}/members/{userId}", shared, me(ada).id()).exchange().expectStatus().isNoContent();

        var seen = combination(ada, combo).orElseThrow();
        assertFalse(seen.stepsLocked());
        assertTrue(seen.images().isEmpty());
        assertEquals(List.of(photo), listed(bea));
        String key = jdbc.queryForObject("SELECT storage_key FROM image WHERE id = ?", String.class, photo);
        ada.delete().uri("/api/combinations/{id}", combo).exchange().expectStatus().isNoContent();
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM image WHERE id = ?", Integer.class, photo));
        assertStored(storage, key, false);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM image_deletion WHERE image_id = ? AND combination_id = ?"
                + " AND visibility = 'CLUB' AND uploader_id = ? AND deleted_by = ?", Integer.class, photo, combo, me(bea).id(), me(ada).id()));
    }

    /** The uploader leaving changes nothing for the author: the photo stays with the club the author is in. */
    @Test void aClubPhotoKeepsLockingWhenItsUploaderLeaves() {
        people();
        UUID photo = created(tile(bea, combo, "CLUB", shared));
        bea.delete().uri("/api/clubs/{id}/members/{userId}", shared, me(bea).id()).exchange().expectStatus().isNoContent();
        var seen = combination(ada, combo).orElseThrow();
        assertTrue(seen.stepsLocked());
        assertEquals(List.of(photo), listed(ada));
        // The uploader keeps seeing their own club photo after leaving.
        assertEquals(List.of(photo), listed(bea));
        assertError(ada.delete().uri("/api/combinations/{id}", combo).exchange(), 409, CombinationService.DELETE_LOCKED);
    }
}
