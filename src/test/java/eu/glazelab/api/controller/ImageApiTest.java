package eu.glazelab.api.controller;

import eu.glazelab.api.model.ImageVisibility;
import eu.glazelab.api.model.ImageKind;
import eu.glazelab.api.dto.GlazeDtos.GlazeView;
import eu.glazelab.api.dto.ImageDtos.ImageView;
import eu.glazelab.api.repository.ImageRecordRepository;
import eu.glazelab.api.storage.ImageStorage;
import eu.glazelab.api.support.ApiTestBase;
import eu.glazelab.api.support.TestImages;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.Set;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.junit.jupiter.api.Assertions.*;

class ImageApiTest extends ApiTestBase {
    private static final Map<String, String> PUBLIC_TILE = Map.of("visibility", "PUBLIC", "kind", "TEST_TILE");

    @Autowired ImageRecordRepository records;
    @Autowired ImageStorage storage;

    private static List<ImageView> images(RestTestClient client, UUID combo) {
        return combination(client, combo).orElseThrow().images();
    }

    private static UUID uploadToGlaze(RestTestClient client, UUID glaze, String visibility, String kind, UUID club) {
        var fields = club == null ? Map.of("visibility", visibility, "kind", kind)
                : Map.of("visibility", visibility, "kind", kind, "clubId", club.toString());
        return created(uploadGlazeImage(client, glaze, fields, PNG));
    }

    private static List<UUID> glazeImageIds(RestTestClient client, UUID glaze) {
        return body(client.get().uri("/api/glazes/{id}/images", glaze).exchange().expectStatus().isOk(),
                new ParameterizedTypeReference<List<ImageView>>() {}).stream().map(ImageView::id).toList();
    }

    /** The image IDs embedded in the catalog listing for one glaze. */
    private static List<UUID> catalogImageIds(RestTestClient client, UUID glaze) {
        return body(client.get().uri("/api/glazes").exchange().expectStatus().isOk(),
                new ParameterizedTypeReference<List<GlazeView>>() {}).stream()
                .filter(g -> g.id().equals(glaze)).findFirst().orElseThrow()
                .images().stream().map(ImageView::id).toList();
    }

    private static RestTestClient.ResponseSpec image(RestTestClient client, UUID id) {
        return client.get().uri("/api/images/{id}", id).exchange();
    }

    @Test void uploadsAreConvertedAndVisibilityIsEnforcedOnListingsAndDownloads() {
        RestTestClient ada = user("Ada");
        RestTestClient bea = user("Bea");
        RestTestClient guest = guest();
        UUID glaze = addGlaze(ada, unique("Images"), "Blue", "PC-20");
        UUID club = createClub(ada, unique("Kiln"));
        UUID combo = addCombination(ada, unique("Photographed"), glaze);
        UUID privateId = uploadTile(ada, combo, "PRIVATE", null);
        UUID publicId = uploadTile(ada, combo, "PUBLIC", null);
        UUID clubId = uploadTile(ada, combo, "CLUB", club);

        byte[] stored = image(ada, publicId).expectStatus().isOk().expectBody(byte[].class).returnResult().getResponseBody();
        assertArrayEquals(new byte[] {(byte) 0xff, (byte) 0xd8}, new byte[] {stored[0], stored[1]}, "Stored as JPEG");
        image(guest, publicId).expectStatus().isOk();
        image(guest, privateId).expectStatus().isNotFound();
        image(bea, clubId).expectStatus().isNotFound();
        image(bea, privateId).expectStatus().isNotFound();
        assertEquals(List.of(publicId), images(guest, combo).stream().map(ImageView::id).toList());
        assertEquals(List.of(publicId), images(bea, combo).stream().map(ImageView::id).toList());
        assertEquals(3, images(ada, combo).size());

        for (int i = 0; i < 2; i++)
            joinClub(ada, club, bea);
        image(bea, clubId).expectStatus().isOk();
        image(bea, privateId).expectStatus().isNotFound();
        assertEquals(2, images(bea, combo).size());
    }

    @Test void uploadsAnswerWithTheCreatedPictureAsItsUploaderSeesIt() {
        RestTestClient ada = user("Ada");
        UUID glaze = addGlaze(ada, unique("Answers"), "Blue", "PC-20");
        UUID combo = addCombination(ada, unique("Answered"), glaze);
        String name = me(ada).displayName();
        ImageView onCombination = body(upload(ada, combo, PUBLIC_TILE, PNG).expectStatus().isOk(), ImageView.class);
        assertEquals(new ImageView(onCombination.id(), ImageVisibility.PUBLIC, ImageKind.TEST_TILE, "/api/images/" + onCombination.id(),
                "/api/images/" + onCombination.id() + "/card", 24, 24, null, true, name, true, null, null, null, null), onCombination);
        assertEquals(List.of(onCombination), images(ada, combo));
        UUID club = createClub(ada, unique("Kiln"));
        ImageView onGlaze = body(uploadGlazeImage(ada, glaze, Map.of("visibility", "CLUB", "kind", "FINISHED",
                "clubId", club.toString()), PNG).expectStatus().isOk(), ImageView.class);
        assertEquals(new ImageView(onGlaze.id(), ImageVisibility.CLUB, ImageKind.FINISHED, "/api/images/" + onGlaze.id(), "/api/images/" + onGlaze.id() + "/card", 24, 24, club, true, name, true, null, null, null, null),
                onGlaze);
        assertEquals(List.of(onGlaze), body(ada.get().uri("/api/glazes/{id}/images", glaze).exchange(),
                new ParameterizedTypeReference<List<ImageView>>() {}));
    }

    @Test void largeImagesAreScaledToTheConfiguredMaximum() {
        RestTestClient ada = user("Ada");
        UUID combo = addCombination(ada, unique("Large"), addGlaze(ada, unique("Large"), "Blue", "PC-20"));
        UUID id = created(upload(ada, combo, Map.of("visibility", "PUBLIC", "kind", "FINISHED"), TestImages.png(2400, 1200)));
        assertEquals(List.of(new ImageView(id, ImageVisibility.PUBLIC, ImageKind.FINISHED, "/api/images/" + id, "/api/images/" + id + "/card", 1600, 800, null, true, me(ada).displayName(), true, null, null, null, null)), images(ada, combo));
    }

    @Test void invalidUploadsAreRejectedWithoutStoringImages() {
        RestTestClient ada = user("Ada");
        RestTestClient bea = user("Bea");
        UUID combo = addCombination(ada, unique("Rejected"), addGlaze(ada, unique("Rejected"), "Blue", "PC-20"));
        UUID beasClub = createClub(bea, unique("Not Ada's"));

        upload(ada, combo, PUBLIC_TILE, "not an image".getBytes()).expectStatus().isBadRequest();
        upload(ada, combo, PUBLIC_TILE, new byte[0]).expectStatus().isBadRequest();
        upload(ada, combo, PUBLIC_TILE, TestImages.pngWithBrokenData(24, 24)).expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("Unsupported image");
        upload(ada, combo, PUBLIC_TILE, TestImages.pngWithBrokenData(10_000, 10_000)).expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("Image dimensions are too large");
        upload(ada, combo, PUBLIC_TILE, TestImages.pngWithBrokenData(5000, 5000)).expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("Image dimensions are too large");
        upload(ada, combo, Map.of("visibility", "public", "kind", "TEST_TILE"), PNG).expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("Please check the form fields and try again.");
        upload(ada, combo, Map.of("visibility", "PUBLIC", "kind", "SELFIE"), PNG).expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("Please check the form fields and try again.");
        upload(ada, combo, Map.of("visibility", "CLUB", "kind", "TEST_TILE"), PNG).expectStatus().isBadRequest();
        upload(ada, combo, Map.of("visibility", "CLUB", "kind", "TEST_TILE", "clubId", beasClub.toString()), PNG)
                .expectStatus().isForbidden();
        upload(ada, UUID.randomUUID(), PUBLIC_TILE, PNG).expectStatus().isNotFound();
        upload(guest(), combo, PUBLIC_TILE, PNG).expectStatus().isUnauthorized();
        assertEquals(List.of(), images(ada, combo));
        image(guest(), UUID.randomUUID()).expectStatus().isNotFound();
    }

    @Test void aRecordWhoseStoredImageIsGoneAnswersNotFound() throws Exception {
        RestTestClient ada = user("Ada");
        UUID combo = addCombination(ada, unique("Lost"), addGlaze(ada, unique("Lost"), "Blue", "PC-20"));
        UUID id = uploadTile(ada, combo, "PUBLIC", null);
        image(ada, id).expectStatus().isOk();
        storage.delete(records.findById(id).orElseThrow().getStorageKey());
        image(ada, id).expectStatus().isNotFound().expectBody().jsonPath("$.error").isEqualTo("Image not found");
    }

    /**
     * PUBLIC pictures may be kept by browsers and shared caches for a year (Nginx at most 60 s); CLUB and PRIVATE
     * pictures only by the viewer's own browser, for a week (30-1); every refusal is never stored. Both sizes alike.
     */
    @Test void publicImagesAreCachedEverywhereAndOthersOnlyByTheViewersBrowserForAWeek() throws Exception {
        RestTestClient ada = user("Ada");
        RestTestClient bea = user("Bea");
        RestTestClient cyd = user("Cyd");
        RestTestClient guest = guest();
        UUID combo = addCombination(ada, unique("Cache"), addGlaze(ada, unique("Cache"), "Blue", "PC-20"));
        UUID club = createClub(ada, unique("Cache club"));
        joinClub(ada, club, bea);
        UUID publicId = uploadTile(ada, combo, "PUBLIC", null);
        UUID clubId = uploadTile(ada, combo, "CLUB", club);
        UUID privateId = uploadTile(ada, combo, "PRIVATE", null);
        String yearImmutable = CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable().getHeaderValue();
        assertEquals("max-age=31536000, public, immutable", yearImmutable);
        String weekInTheBrowser = "max-age=604800, private, immutable";
        Map<UUID, List<RestTestClient>> viewers = Map.of(publicId, List.of(guest, ada, cyd), clubId, List.of(ada, bea), privateId, List.of(ada));
        Map<UUID, List<RestTestClient>> hidden = Map.of(clubId, List.of(guest, cyd), privateId, List.of(guest, bea, cyd));
        for (String size : List.of("", "/card")) {
            viewers.forEach((id, clients) -> {
                boolean everyone = id.equals(publicId);
                for (RestTestClient client : clients)
                    client.get().uri("/api/images/" + id + size).exchange().expectStatus().isOk()
                            .expectHeader().contentType(MediaType.IMAGE_JPEG)
                            .expectHeader().valueEquals("Cache-Control", everyone ? yearImmutable : weekInTheBrowser)
                            // Only a public answer may enter the Nginx cache (60 s); 0 forbids storing any other there.
                            .expectHeader().valueEquals("X-Accel-Expires", everyone ? "60" : "0")
                            .expectHeader().doesNotExist("Set-Cookie");
            });
            hidden.forEach((id, clients) -> {
                for (RestTestClient client : clients)
                    assertNeverStored(client.get().uri("/api/images/" + id + size).exchange().expectStatus().isNotFound());
            });
            assertNeverStored(guest.get().uri("/api/images/" + UUID.randomUUID() + size).exchange().expectStatus().isNotFound());
            assertNeverStored(guest.get().uri("/api/images/not-an-id" + size).exchange().expectStatus().isBadRequest());
        }
        // Refused writes on the image paths are never stored either.
        assertNeverStored(cyd.put().uri("/api/images/{id}/visibility", privateId).body(Map.of("visibility", "PUBLIC")).exchange().expectStatus().isNotFound());
        assertNeverStored(bea.put().uri("/api/images/{id}/visibility", clubId).body(Map.of("visibility", "PUBLIC")).exchange().expectStatus().isForbidden());
        // A record whose stored object is gone is a 404 for its viewers too.
        storage.delete(records.findById(clubId).orElseThrow().getStorageKey());
        assertNeverStored(image(bea, clubId).expectStatus().isNotFound());
    }

    @Test void rangeConditionalAndHeadRequestsGetTheSameBrowserOnlyAnswer() {
        RestTestClient ada = user("Ada");
        RestTestClient bea = user("Bea");
        UUID combo = addCombination(ada, unique("Ranges"), addGlaze(ada, unique("Ranges"), "Blue", "PC-20"));
        UUID club = createClub(ada, unique("Ranges club"));
        joinClub(ada, club, bea);
        UUID clubId = uploadTile(ada, combo, "CLUB", club);
        String weekInTheBrowser = "max-age=604800, private, immutable";
        for (String size : List.of("", "/card")) {
            byte[] full = bea.get().uri("/api/images/" + clubId + size).exchange().expectStatus().isOk()
                    .expectBody(byte[].class).returnResult().getResponseBody();
            // No validators exist, so a conditional request is never a 304 and a range never a 206 slice.
            byte[] answered = bea.get().uri("/api/images/" + clubId + size)
                    .header("Range", "bytes=0-1").header("If-None-Match", "*")
                    .header("If-Modified-Since", "Sat, 01 Jan 2050 00:00:00 GMT").exchange().expectStatus().isOk()
                    .expectHeader().valueEquals("Cache-Control", weekInTheBrowser).expectHeader().valueEquals("X-Accel-Expires", "0")
                    .expectHeader().doesNotExist("ETag").expectHeader().doesNotExist("Last-Modified")
                    .expectHeader().doesNotExist("Content-Range").expectHeader().doesNotExist("Set-Cookie")
                    .expectBody(byte[].class).returnResult().getResponseBody();
            assertArrayEquals(full, answered);
            bea.head().uri("/api/images/" + clubId + size).exchange().expectStatus().isOk()
                    .expectHeader().valueEquals("Cache-Control", weekInTheBrowser).expectHeader().valueEquals("X-Accel-Expires", "0")
                    .expectHeader().doesNotExist("Set-Cookie");
            assertNeverStored(guest().head().uri("/api/images/" + clubId + size).exchange().expectStatus().isNotFound());
        }
    }

    private static void assertNeverStored(RestTestClient.ResponseSpec refusal) {
        refusal.expectHeader().value("Cache-Control", value -> assertTrue(value.contains("no-store") && !value.contains("max-age=604800")
                        && !value.contains("public") && !value.contains("immutable"), value))
                .expectHeader().doesNotExist("X-Accel-Expires");
    }

    @Test void photosAreStoredBoundedUprightProgressiveAndWithoutMetadata() {
        RestTestClient ada = user("Ada");
        UUID glaze = addGlaze(ada, unique("Phone photo"), "Blue", "PC-20");
        byte[] upload = TestImages.withExifOrientation(TestImages.photo(3000, 2000), 6, true);
        UUID id = created(uploadGlazeImage(ada, glaze, Map.of("visibility", "PUBLIC", "kind", "TEST_TILE"), upload));
        assertEquals(new ImageView(id, ImageVisibility.PUBLIC, ImageKind.TEST_TILE, "/api/images/" + id, "/api/images/" + id + "/card", 1067, 1600, null, true, me(ada).displayName(), true, null, null, null, null),
                body(ada.get().uri("/api/glazes/{id}/images", glaze).exchange().expectStatus().isOk(),
                        new ParameterizedTypeReference<List<ImageView>>() {}).getFirst());
        byte[] stored = image(guest(), id).expectStatus().isOk().expectBody(byte[].class).returnResult().getResponseBody();
        assertTrue(TestImages.hasMarker(stored, 0xc2), "progressive JPEG");
        assertFalse(TestImages.hasMarker(stored, 0xe1), "Exif stripped");
        assertTrue(stored.length < 400_000 && stored.length * 5 < upload.length,
                "upload " + upload.length + " bytes, stored " + stored.length);
    }

    @Test void glazeImagesFollowTheSameVisibilityRulesOnListingsAndDownloads() {
        RestTestClient ada = user("Ada");
        RestTestClient bea = user("Bea");
        RestTestClient guest = guest();
        UUID glaze = addGlaze(ada, unique("Glaze pictures"), "Celadon", "PC-30");
        UUID club = createClub(ada, unique("Kiln"));
        UUID privateId = uploadToGlaze(ada, glaze, "PRIVATE", "TEST_TILE", null);
        UUID clubId = uploadToGlaze(ada, glaze, "CLUB", "TEST_TILE", club);
        UUID publicId = uploadToGlaze(ada, glaze, "PUBLIC", "FINISHED", null);

        byte[] stored = image(guest, publicId).expectStatus().isOk()
                .expectBody(byte[].class).returnResult().getResponseBody();
        assertArrayEquals(new byte[] {(byte) 0xff, (byte) 0xd8}, new byte[] {stored[0], stored[1]}, "Stored as JPEG");
        image(guest, privateId).expectStatus().isNotFound();
        image(guest, clubId).expectStatus().isNotFound();
        image(bea, privateId).expectStatus().isNotFound();
        image(bea, clubId).expectStatus().isNotFound();
        image(ada, privateId).expectStatus().isOk();
        image(ada, clubId).expectStatus().isOk();

        for (RestTestClient visitor : List.of(guest, bea)) {
            assertEquals(List.of(publicId), glazeImageIds(visitor, glaze));
            assertEquals(List.of(publicId), catalogImageIds(visitor, glaze));
        }
        assertEquals(Set.of(privateId, clubId, publicId), Set.copyOf(glazeImageIds(ada, glaze)));
        assertEquals(Set.of(privateId, clubId, publicId), Set.copyOf(catalogImageIds(ada, glaze)));
        assertEquals(new ImageView(publicId, ImageVisibility.PUBLIC, ImageKind.FINISHED, "/api/images/" + publicId, "/api/images/" + publicId + "/card", 24, 24, null, false, me(ada).displayName(), false, null, null, null, null),
                body(guest.get().uri("/api/glazes/{id}/images", glaze).exchange().expectStatus().isOk(),
                        new ParameterizedTypeReference<List<ImageView>>() {}).getFirst());

        joinClub(ada, club, bea);
        image(bea, clubId).expectStatus().isOk();
        image(bea, privateId).expectStatus().isNotFound();
        assertEquals(Set.of(clubId, publicId), Set.copyOf(glazeImageIds(bea, glaze)));
        assertEquals(Set.of(clubId, publicId), Set.copyOf(catalogImageIds(bea, glaze)));
    }

    @Test void glazeAndCombinationPicturesStaySeparate() {
        RestTestClient ada = user("Ada");
        UUID glaze = addGlaze(ada, unique("Separate"), "Blue", "PC-20");
        UUID combo = addCombination(ada, unique("Separate"), glaze);
        UUID glazeImage = uploadToGlaze(ada, glaze, "PUBLIC", "TEST_TILE", null);
        UUID comboImage = uploadTile(ada, combo, "PUBLIC", null);
        assertEquals(List.of(glazeImage), glazeImageIds(guest(), glaze));
        assertEquals(List.of(comboImage), images(guest(), combo).stream().map(ImageView::id).toList());
        UUID large = created(uploadGlazeImage(ada, glaze, Map.of("visibility", "PUBLIC", "kind", "FINISHED"),
                TestImages.png(1200, 2400)));
        assertEquals(new ImageView(large, ImageVisibility.PUBLIC, ImageKind.FINISHED, "/api/images/" + large, "/api/images/" + large + "/card", 800, 1600, null, true, me(ada).displayName(), true, null, null, null, null),
                body(ada.get().uri("/api/glazes/{id}/images", glaze).exchange().expectStatus().isOk(),
                        new ParameterizedTypeReference<List<ImageView>>() {}).stream()
                        .filter(i -> i.id().equals(large)).findFirst().orElseThrow());
    }

    @Test void invalidGlazeUploadsAreRejectedWithoutStoringImages() {
        RestTestClient ada = user("Ada");
        RestTestClient bea = user("Bea");
        UUID glaze = addGlaze(ada, unique("Rejected glaze"), "Blue", "PC-20");
        UUID beasClub = createClub(bea, unique("Not Ada's"));

        uploadGlazeImage(ada, UUID.randomUUID(), PUBLIC_TILE, PNG).expectStatus().isNotFound()
                .expectBody().jsonPath("$.error").isEqualTo("Glaze not found");
        uploadGlazeImage(guest(), glaze, PUBLIC_TILE, PNG).expectStatus().isUnauthorized();
        uploadGlazeImage(ada, glaze, PUBLIC_TILE, "not an image".getBytes()).expectStatus().isBadRequest();
        uploadGlazeImage(ada, glaze, PUBLIC_TILE, new byte[0]).expectStatus().isBadRequest();
        uploadGlazeImage(ada, glaze, PUBLIC_TILE, TestImages.pngWithBrokenData(24, 24)).expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("Unsupported image");
        uploadGlazeImage(ada, glaze, Map.of("visibility", "EVERYONE", "kind", "TEST_TILE"), PNG).expectStatus().isBadRequest();
        uploadGlazeImage(ada, glaze, Map.of("visibility", "PUBLIC", "kind", "SELFIE"), PNG).expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("Please check the form fields and try again.");
        uploadGlazeImage(ada, glaze, Map.of("visibility", "CLUB", "kind", "TEST_TILE"), PNG).expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("Choose a club");
        uploadGlazeImage(ada, glaze, Map.of("visibility", "CLUB", "kind", "TEST_TILE", "clubId", beasClub.toString()), PNG)
                .expectStatus().isForbidden();
        assertEquals(List.of(), glazeImageIds(ada, glaze));
        assertEquals(List.of(), catalogImageIds(ada, glaze));
        guest().get().uri("/api/glazes/{id}/images", UUID.randomUUID()).exchange().expectStatus().isNotFound();
    }
}
