package eu.glazelab.api.controller;

import eu.glazelab.api.model.ImageVisibility;
import eu.glazelab.api.model.ImageKind;
import eu.glazelab.api.dto.ImageDtos.ImageView;
import eu.glazelab.api.repository.ImageRecordRepository;
import eu.glazelab.api.support.ApiTestBase;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.test.web.servlet.client.RestTestClient.ResponseSpec;

import static org.junit.jupiter.api.Assertions.*;

class ImageVisibilityApiTest extends ApiTestBase {
    private static final String PUBLIC_CACHE = "max-age=31536000, public, immutable";
    private static final String PRIVATE_CACHE = "max-age=604800, private, immutable";

    @Autowired ImageRecordRepository records;

    /** Ada owns the pictures; Bea shares Ada's club; Cyd is signed in elsewhere. */
    private RestTestClient ada, bea, cyd, guest;
    private UUID club;

    private void people() {
        ada = user("Ada");
        bea = user("Bea");
        cyd = user("Cyd");
        guest = guest();
        club = createClub(ada, unique("Kiln"));
        joinClub(ada, club, bea);
    }

    private static ResponseSpec change(RestTestClient client, UUID image, String visibility, UUID club) {
        var body = new HashMap<String, Object>(Map.of("visibility", visibility));
        if (club != null) body.put("clubId", club);
        return client.put().uri("/api/images/{id}/visibility", image).body(body).exchange();
    }

    private static ImageView changed(RestTestClient client, UUID image, String visibility, UUID club) {
        return body(change(client, image, visibility, club).expectStatus().isOk(), ImageView.class);
    }

    private static List<ImageView> listed(RestTestClient client, UUID combo) {
        return combination(client, combo).orElseThrow().images();
    }

    private static ResponseSpec download(RestTestClient client, UUID id) {
        return client.get().uri("/api/images/{id}", id).exchange();
    }

    /** Lists and downloads match the visibility for the owner, a club member, another account and a guest. */
    private void assertSeenBy(UUID combo, ImageView image, boolean member, boolean everyone) {
        assertEquals(List.of(image), listed(ada, combo));
        download(ada, image.id()).expectStatus().isOk().expectHeader()
                .valueEquals("Cache-Control", everyone ? PUBLIC_CACHE : PRIVATE_CACHE);
        for (RestTestClient viewer : List.of(bea, cyd, guest)) {
            boolean sees = everyone || (member && viewer == bea);
            assertEquals(sees ? List.of(image.id()) : List.of(),
                    listed(viewer, combo).stream().map(ImageView::id).toList());
            if (sees) assertFalse(listed(viewer, combo).getFirst().own());
            download(viewer, image.id()).expectStatus().isEqualTo(sees ? 200 : 404);
        }
    }

    @Test void theUploaderChangesVisibilityInEveryDirectionAndLeavingPublicMovesTheUrl() {
        people();
        UUID combo = addCombination(ada, unique("Moving"), addGlaze(ada, unique("Moving"), "Blue", "PC-20"));
        UUID first = uploadTile(ada, combo, "PUBLIC", null);
        String key = records.findById(first).orElseThrow().getStorageKey();
        byte[] jpeg = download(ada, first).expectBody(byte[].class).returnResult().getResponseBody();
        ImageView current = listed(ada, combo).getFirst();
        assertTrue(current.own());
        assertNull(current.clubId());
        assertSeenBy(combo, current, false, true);

        // PUBLIC -> CLUB -> PRIVATE -> PUBLIC -> PRIVATE -> CLUB -> PUBLIC covers all six transitions.
        ImageVisibility[] steps = {ImageVisibility.CLUB, ImageVisibility.PRIVATE, ImageVisibility.PUBLIC,
                ImageVisibility.PRIVATE, ImageVisibility.CLUB, ImageVisibility.PUBLIC};
        for (ImageVisibility visibility : steps) {
            UUID clubId = visibility == ImageVisibility.CLUB ? club : null;
            ImageView next = changed(ada, current.id(), visibility.name(), clubId);
            assertEquals(new ImageView(next.id(), visibility, ImageKind.TEST_TILE, "/api/images/" + next.id(), "/api/images/" + next.id() + "/card", 24, 24, clubId, true, me(ada).displayName(), true, null, null, null, null), next);
            if (current.visibility() == ImageVisibility.PUBLIC) {
                assertNotEquals(current.id(), next.id(), "leaving PUBLIC needs a new URL");
                // The old public URL is gone for everyone, and its 404 is never cached.
                for (RestTestClient client : List.of(ada, bea, cyd, guest))
                    download(client, current.id()).expectStatus().isNotFound().expectHeader()
                            .value("Cache-Control", value -> assertTrue(value.contains("no-store"), value));
                assertTrue(records.findById(current.id()).isEmpty());
            } else {
                assertEquals(current.id(), next.id(), "nothing outside the browser cached a non-public URL");
            }
            assertSeenBy(combo, next, visibility == ImageVisibility.CLUB, visibility == ImageVisibility.PUBLIC);
            // One record, one stored object: the picture itself never changes.
            assertEquals(key, records.findById(next.id()).orElseThrow().getStorageKey());
            assertArrayEquals(jpeg, download(ada, next.id()).expectBody(byte[].class).returnResult().getResponseBody());
            current = next;
        }
        assertEquals(1, listed(ada, combo).size());
    }

    @Test void glazePicturesMoveTooAndKeepTheirPlace() {
        people();
        UUID glaze = addGlaze(ada, unique("Glaze move"), "Celadon", "PC-30");
        UUID older = created(uploadGlazeImage(ada, glaze, Map.of("visibility", "PUBLIC", "kind", "TEST_TILE"), PNG));
        UUID newer = created(uploadGlazeImage(ada, glaze, Map.of("visibility", "PUBLIC", "kind", "FINISHED"), PNG));
        ImageView moved = changed(ada, older, "CLUB", club);
        assertNotEquals(older, moved.id());
        assertEquals(List.of(newer, moved.id()), glazeImages(ada, glaze), "the creation time is kept");
        assertEquals(List.of(newer, moved.id()), glazeImages(bea, glaze));
        assertEquals(List.of(newer), glazeImages(cyd, glaze));
        assertEquals(List.of(newer), glazeImages(guest, glaze));
        download(guest, older).expectStatus().isNotFound();
        download(bea, moved.id()).expectStatus().isOk().expectHeader().valueEquals("Cache-Control", PRIVATE_CACHE);
        var catalog = glazes(bea).stream().filter(g -> g.id().equals(glaze)).findFirst().orElseThrow();
        assertEquals(List.of(newer, moved.id()), catalog.images().stream().map(ImageView::id).toList());
        assertEquals(club, catalog.images().get(1).clubId());
    }

    @Test void aClubPictureMovesToAnotherClubUnderTheSameUrl() {
        people();
        RestTestClient dan = user("Dan");
        UUID other = createClub(ada, unique("Other kiln"));
        joinClub(ada, other, dan);
        UUID combo = addCombination(ada, unique("Clubs"), addGlaze(ada, unique("Clubs"), "Blue", "PC-20"));
        UUID id = uploadTile(ada, combo, "CLUB", club);
        download(bea, id).expectStatus().isOk();
        download(dan, id).expectStatus().isNotFound();
        assertEquals(id, changed(ada, id, "CLUB", other).id());
        download(bea, id).expectStatus().isNotFound();
        download(dan, id).expectStatus().isOk().expectHeader().valueEquals("Cache-Control", PRIVATE_CACHE);
        assertEquals(other, listed(dan, combo).getFirst().clubId());
        // An unchanged public picture keeps its URL.
        UUID shown = uploadTile(ada, combo, "PUBLIC", null);
        assertEquals(shown, changed(ada, shown, "PUBLIC", null).id());
        download(guest, shown).expectStatus().isOk().expectHeader().valueEquals("Cache-Control", PUBLIC_CACHE);
    }

    @Test void onlyTheUploaderMayChangeItAndOthersLearnNothingAboutHiddenPictures() {
        people();
        UUID combo = addCombination(ada, unique("Guarded"), addGlaze(ada, unique("Guarded"), "Blue", "PC-20"));
        UUID shown = uploadTile(ada, combo, "PUBLIC", null);
        UUID clubbed = uploadTile(ada, combo, "CLUB", club);
        UUID hidden = uploadTile(ada, combo, "PRIVATE", null);
        List<ImageView> before = listed(ada, combo);

        // Pictures they can see: 403 with a message; pictures they cannot see answer like unknown ones.
        for (var attempt : List.of(Map.entry(bea, shown), Map.entry(bea, clubbed), Map.entry(cyd, shown)))
            change(attempt.getKey(), attempt.getValue(), "PRIVATE", null).expectStatus().isForbidden()
                    .expectBody().jsonPath("$.error").isEqualTo(ImageController.OWNER_REQUIRED);
        for (var attempt : List.of(Map.entry(bea, hidden), Map.entry(cyd, clubbed), Map.entry(cyd, hidden),
                Map.entry(bea, UUID.randomUUID())))
            change(attempt.getKey(), attempt.getValue(), "PUBLIC", null).expectStatus().isNotFound()
                    .expectBody().jsonPath("$.error").isEqualTo("Image not found");
        change(guest, shown, "PRIVATE", null).expectStatus().isUnauthorized();
        change(guest, hidden, "PUBLIC", null).expectStatus().isUnauthorized();
        guestWithoutCsrf().put().uri("/api/images/{id}/visibility", shown).body(Map.of("visibility", "PRIVATE"))
                .exchange().expectStatus().isForbidden();
        ada.put().uri("/api/images/{id}/visibility", shown).header(CSRF_HEADER, "wrong")
                .body(Map.of("visibility", "PRIVATE")).exchange().expectStatus().isForbidden();
        assertEquals(before, listed(ada, combo));
        download(guest, shown).expectStatus().isOk();
    }

    @Test void invalidChangesAreRejectedAndChangeNothing() {
        people();
        RestTestClient eve = user("Eve");
        UUID foreignClub = createClub(eve, unique("Not Ada's"));
        UUID combo = addCombination(ada, unique("Invalid"), addGlaze(ada, unique("Invalid"), "Blue", "PC-20"));
        UUID shown = uploadTile(ada, combo, "PUBLIC", null);
        List<ImageView> before = listed(ada, combo);

        change(ada, shown, "CLUB", null).expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("Choose a club");
        change(ada, shown, "CLUB", foreignClub).expectStatus().isForbidden()
                .expectBody().jsonPath("$.error").isEqualTo("Club membership required");
        change(ada, shown, "EVERYONE", null).expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("Please check the form fields and try again.");
        ada.patch().uri("/api/images/{id}", shown).body(Map.of("kind", "SELFIE")).exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("Please check the form fields and try again.");
        ada.put().uri("/api/images/{id}/visibility", shown).body(Map.of()).exchange().expectStatus().isBadRequest();
        // Enum ordinals and other spellings are not values of the contract.
        ada.put().uri("/api/images/{id}/visibility", shown).body(Map.of("visibility", 2)).exchange().expectStatus().isBadRequest();
        ada.put().uri("/api/images/{id}/visibility", shown).body(Map.of("visibility", "2")).exchange().expectStatus().isBadRequest();
        ada.put().uri("/api/images/{id}/visibility", shown).body(Map.of("visibility", "private")).exchange().expectStatus().isBadRequest();
        ada.patch().uri("/api/images/{id}", shown).body(Map.of("kind", 1)).exchange().expectStatus().isBadRequest();
        assertEquals(before, listed(ada, combo));
        download(guest, shown).expectStatus().isOk().expectHeader().valueEquals("Cache-Control", PUBLIC_CACHE);
    }

    private static List<UUID> glazeImages(RestTestClient client, UUID glaze) {
        return body(client.get().uri("/api/glazes/{id}/images", glaze).exchange().expectStatus().isOk(),
                new ParameterizedTypeReference<List<ImageView>>() {}).stream().map(ImageView::id).toList();
    }
}
