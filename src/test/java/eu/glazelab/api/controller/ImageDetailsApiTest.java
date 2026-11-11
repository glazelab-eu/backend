package eu.glazelab.api.controller;

import eu.glazelab.api.dto.ImageDtos.ImageView;
import eu.glazelab.api.model.ImageKind;
import eu.glazelab.api.model.PhotoDetails;
import eu.glazelab.api.support.ApiTestBase;
import eu.glazelab.api.support.TestImages;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.test.web.servlet.client.RestTestClient;
import static org.junit.jupiter.api.Assertions.*;

/** The uploader's caption and firing date of a photo (decision Q7): PATCH /api/images/{id} and the upload fields. */
class ImageDetailsApiTest extends ApiTestBase {
    private static RestTestClient.ResponseSpec patch(RestTestClient client, UUID id, Map<String, ?> body) {
        return client.patch().uri("/api/images/{id}", id).body(body).exchange();
    }

    private static Map<String, Object> nullOf(String field) {
        var body = new HashMap<String, Object>();
        body.put(field, null);
        return body;
    }

    private ImageView combinationPhoto(RestTestClient owner, String visibility, Map<String, String> extra) {
        UUID combination = addCombination(owner, unique("Details"), addGlaze(owner, unique("Details maker"), "Blue", "D-1"));
        var fields = new HashMap<>(Map.of("visibility", visibility, "kind", "TEST_TILE"));
        fields.putAll(extra);
        return body(upload(owner, combination, fields, PNG).expectStatus().isOk(), ImageView.class);
    }

    @Test void aNewPhotoHasNoCaptionOrDate() {
        var photo = combinationPhoto(user("DetailsNone"), "PUBLIC", Map.of());
        assertNull(photo.caption());
        assertNull(photo.firedOn());
    }

    @Test void uploadsAcceptCaptionAndFiringDateOnCombinationsAndGlazes() {
        var owner = user("DetailsUpload");
        var photo = combinationPhoto(owner, "PUBLIC", Map.of("caption", "  Top shelf, cone 6  ", "firedOn", "2026-10-14"));
        assertEquals("Top shelf, cone 6", photo.caption());
        assertEquals(LocalDate.of(2026, 10, 14), photo.firedOn());
        UUID glaze = addGlaze(owner, unique("Details glaze"), "Green", "D-2");
        var onGlaze = body(uploadGlazeImage(owner, glaze, Map.of("visibility", "PRIVATE", "kind", "PACKAGING", "caption", "Front label", "firedOn", "1999-01-02"),
                TestImages.png(24, 24)).expectStatus().isOk(), ImageView.class);
        assertEquals("Front label", onGlaze.caption());
        assertEquals(LocalDate.of(1999, 1, 2), onGlaze.firedOn());
        var listed = glazes(owner).stream().filter(g -> g.id().equals(glaze)).findFirst().orElseThrow().images().getFirst();
        assertEquals(onGlaze, listed);
        assertEquals("Top shelf, cone 6", jdbc.queryForObject("SELECT caption FROM image WHERE id = ?", String.class, photo.id()));
    }

    @Test void blankUploadFieldsStayEmptyAndBadOnesAreRefusedWithoutStoringAPhoto() {
        var owner = user("DetailsBadUpload");
        UUID combination = addCombination(owner, unique("Bad upload"), addGlaze(owner, unique("Bad upload maker"), "Blue", "D-3"));
        var blank = body(upload(owner, combination, Map.of("visibility", "PUBLIC", "kind", "TEST_TILE", "caption", "   ", "firedOn", ""), PNG)
                .expectStatus().isOk(), ImageView.class);
        assertNull(blank.caption()); assertNull(blank.firedOn());
        int before = combination(owner, combination).orElseThrow().images().size();
        assertError(upload(owner, combination, Map.of("visibility", "PUBLIC", "kind", "TEST_TILE", "caption", "c".repeat(201)), PNG),
                400, PhotoDetails.CAPTION_TOO_LONG);
        assertError(upload(owner, combination, Map.of("visibility", "PUBLIC", "kind", "TEST_TILE", "firedOn", "1899-12-31"), PNG),
                400, PhotoDetails.DATE_OUT_OF_RANGE);
        upload(owner, combination, Map.of("visibility", "PUBLIC", "kind", "TEST_TILE", "firedOn", "yesterday"), PNG).expectStatus().isBadRequest();
        assertEquals(before, combination(owner, combination).orElseThrow().images().size());
    }

    @Test void glazeUploadsRefuseBadDetailsWithoutStoringAPhoto() {
        var owner = user("DetailsBadGlazeUpload");
        UUID glaze = addGlaze(owner, unique("Bad glaze upload"), "Teal", "D-8");
        assertError(uploadGlazeImage(owner, glaze, Map.of("visibility", "PUBLIC", "kind", "TEST_TILE", "caption", "c".repeat(201)), PNG),
                400, PhotoDetails.CAPTION_TOO_LONG);
        assertError(uploadGlazeImage(owner, glaze, Map.of("visibility", "PUBLIC", "kind", "TEST_TILE", "firedOn", "2101-01-01"), PNG),
                400, PhotoDetails.DATE_OUT_OF_RANGE);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM image WHERE glaze_id = ?", Integer.class, glaze));
    }

    @Test void uploadsReadCaptionAndDateFromTheQueryLikeTheBrowserSendsThem() {
        var owner = user("DetailsQuery");
        UUID combination = addCombination(owner, unique("Query upload"), addGlaze(owner, unique("Query maker"), "Blue", "D-9"));
        var fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.IMAGE_PNG);
        var parts = new LinkedMultiValueMap<String, Object>();
        parts.add("file", new HttpEntity<>(new ByteArrayResource(PNG) {
            @Override public String getFilename() { return "tile.png"; }
        }, fileHeaders));
        var photo = body(owner.post()
                .uri(builder -> builder.path("/api/combinations/{id}/images").queryParam("visibility", "PUBLIC").queryParam("kind", "TEST_TILE")
                        .queryParam("caption", "{caption}").queryParam("firedOn", "2026-10-14").build(combination, "Cone 6 & 7, 100%"))
                .contentType(MediaType.MULTIPART_FORM_DATA).body(parts).exchange().expectStatus().isOk(), ImageView.class);
        assertEquals("Cone 6 & 7, 100%", photo.caption());
        assertEquals(LocalDate.of(2026, 10, 14), photo.firedOn());
    }

    @Test void patchChangesOnlyTheFieldsItSendsAndKeepsIdUrlBytesAndCaching() {
        var owner = user("DetailsPatch");
        var photo = combinationPhoto(owner, "PUBLIC", Map.of("caption", "First", "firedOn", "2026-01-02"));
        var before = jdbc.queryForMap("SELECT storage_key, created_at, visibility, club_id FROM image WHERE id = ?", photo.id());
        byte[] bytes = guest().get().uri(photo.url()).exchange().expectStatus().isOk().expectBody(byte[].class).returnResult().getResponseBody();

        var both = body(patch(owner, photo.id(), Map.of("caption", "  Second  ", "firedOn", "2026-03-04", "kind", "FINISHED")).expectStatus().isOk(), ImageView.class);
        assertEquals(new ImageView(photo.id(), photo.visibility(), ImageKind.FINISHED, photo.url(), photo.cardUrl(), photo.width(), photo.height(), null, true,
                photo.uploader(), true, "Second", LocalDate.of(2026, 3, 4), null, null), both);

        // Absent fields stay: a kind-only edit (the 21f-5 shape) keeps caption and date.
        var kindOnly = body(patch(owner, photo.id(), Map.of("kind", "TEST_TILE")).expectStatus().isOk(), ImageView.class);
        assertEquals(new ImageView(photo.id(), photo.visibility(), ImageKind.TEST_TILE, photo.url(), photo.cardUrl(), photo.width(), photo.height(), null, true,
                photo.uploader(), true, "Second", LocalDate.of(2026, 3, 4), null, null), kindOnly);
        // A caption-only edit keeps kind and date; a null kind counts as absent.
        var body = new HashMap<String, Object>(Map.of("caption", "Third"));
        body.put("kind", null);
        var captionOnly = body(patch(owner, photo.id(), body).expectStatus().isOk(), ImageView.class);
        assertEquals(ImageKind.TEST_TILE, captionOnly.kind());
        assertEquals("Third", captionOnly.caption());
        assertEquals(LocalDate.of(2026, 3, 4), captionOnly.firedOn());
        // An empty edit changes nothing.
        assertEquals(captionOnly, body(patch(owner, photo.id(), Map.of()).expectStatus().isOk(), ImageView.class));

        assertEquals(before, jdbc.queryForMap("SELECT storage_key, created_at, visibility, club_id FROM image WHERE id = ?", photo.id()));
        assertArrayEquals(bytes, guest().get().uri(photo.url()).exchange().expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "max-age=31536000, public, immutable")
                .expectHeader().valueEquals("X-Accel-Expires", "60").expectBody(byte[].class).returnResult().getResponseBody());
    }

    @Test void nullAndBlankClearTheCaptionAndTheDateButAbsentDoesNot() {
        var owner = user("DetailsClear");
        var photo = combinationPhoto(owner, "PRIVATE", Map.of("caption", "Keep", "firedOn", "2026-05-06"));
        var clearedDate = body(patch(owner, photo.id(), nullOf("firedOn")).expectStatus().isOk(), ImageView.class);
        assertNull(clearedDate.firedOn()); assertEquals("Keep", clearedDate.caption());
        var clearedCaption = body(patch(owner, photo.id(), Map.of("caption", "   ")).expectStatus().isOk(), ImageView.class);
        assertNull(clearedCaption.caption());
        assertNull(jdbc.queryForObject("SELECT caption FROM image WHERE id = ?", String.class, photo.id()));
        patch(owner, photo.id(), Map.of("caption", "Back", "firedOn", "2026-05-06")).expectStatus().isOk();
        var cleared = body(patch(owner, photo.id(), new HashMap<String, Object>() {{ put("caption", null); put("firedOn", null); }}).expectStatus().isOk(), ImageView.class);
        assertNull(cleared.caption()); assertNull(cleared.firedOn());
        assertEquals(cleared, combination(owner, combinationOf(photo.id())).orElseThrow().images().getFirst());
    }

    private UUID combinationOf(UUID image) {
        return jdbc.queryForObject("SELECT combination_id FROM image WHERE id = ?", UUID.class, image);
    }

    @Test void captionLengthAndDateRangeAreCheckedAndNothingChangesOnRefusal() {
        var owner = user("DetailsLimits");
        var photo = combinationPhoto(owner, "PUBLIC", Map.of("caption", "Stays", "firedOn", "2026-01-01"));
        assertEquals(200, body(patch(owner, photo.id(), Map.of("caption", "c".repeat(200))).expectStatus().isOk(), ImageView.class).caption().length());
        assertError(patch(owner, photo.id(), Map.of("caption", " " + "c".repeat(201))), 400, PhotoDetails.CAPTION_TOO_LONG);
        assertError(patch(owner, photo.id(), Map.of("firedOn", "1899-12-31")), 400, PhotoDetails.DATE_OUT_OF_RANGE);
        assertError(patch(owner, photo.id(), Map.of("firedOn", "2101-01-01")), 400, PhotoDetails.DATE_OUT_OF_RANGE);
        patch(owner, photo.id(), Map.of("firedOn", "+999999999-01-01")).expectStatus().isBadRequest();
        patch(owner, photo.id(), Map.of("firedOn", "not a date")).expectStatus().isBadRequest();
        patch(owner, photo.id(), Map.of("kind", 1)).expectStatus().isBadRequest();
        patch(owner, photo.id(), Map.of("kind", "JAR")).expectStatus().isBadRequest();
        // The first valid edit is not half applied when a later field is refused.
        patch(owner, photo.id(), Map.of("caption", "Half", "firedOn", "1700-01-01")).expectStatus().isBadRequest();
        var now = combination(owner, combinationOf(photo.id())).orElseThrow().images().getFirst();
        assertEquals("c".repeat(200), now.caption());
        assertEquals(LocalDate.of(2026, 1, 1), now.firedOn());
        assertEquals(LocalDate.of(1900, 1, 1), body(patch(owner, photo.id(), Map.of("firedOn", "1900-01-01")).expectStatus().isOk(), ImageView.class).firedOn());
        assertEquals(LocalDate.of(2100, 12, 31), body(patch(owner, photo.id(), Map.of("firedOn", "2100-12-31")).expectStatus().isOk(), ImageView.class).firedOn());
    }

    @Test void kindRulesStillHoldWithDetails() {
        var owner = user("DetailsKind");
        var onCombination = combinationPhoto(owner, "PUBLIC", Map.of());
        assertError(patch(owner, onCombination.id(), Map.of("kind", "PACKAGING", "caption", "No")), 400, ImageController.PACKAGING_GLAZE_ONLY);
        assertNull(combination(owner, combinationOf(onCombination.id())).orElseThrow().images().getFirst().caption());
        UUID glaze = addGlaze(owner, unique("Kind glaze"), "Red", "D-4");
        var onGlaze = body(uploadGlazeImage(owner, glaze, Map.of("visibility", "PUBLIC", "kind", "TEST_TILE"), TestImages.png(24, 24)).expectStatus().isOk(), ImageView.class);
        var packaging = body(patch(owner, onGlaze.id(), Map.of("kind", "PACKAGING", "caption", "Label")).expectStatus().isOk(), ImageView.class);
        assertEquals(ImageKind.PACKAGING, packaging.kind()); assertEquals("Label", packaging.caption());
    }

    @Test void onlyTheUploaderEditsDetailsAndHiddenPhotosStayHidden() {
        var owner = user("DetailsOwner"); var other = user("DetailsOther"); var guest = guest(); var admin = siteAdmin();
        var member = user("DetailsMember"); var clubAdmin = user("DetailsClubAdmin");
        UUID club = createClub(clubAdmin, unique("Details club"));
        joinClub(clubAdmin, club, member);
        var clubOwner = user("DetailsClubOwner");
        joinClub(clubAdmin, club, clubOwner);
        UUID combination = addCombination(clubOwner, unique("Details rights"), addGlaze(clubOwner, unique("Rights maker"), "Blue", "D-5"));
        var shared = body(upload(clubOwner, combination, Map.of("visibility", "CLUB", "kind", "TEST_TILE", "clubId", club.toString(), "caption", "Club only"), PNG)
                .expectStatus().isOk(), ImageView.class);
        var edit = Map.of("caption", "Hijacked", "firedOn", "2026-01-01");

        patch(guest, shared.id(), edit).expectStatus().isUnauthorized();
        patch(member, shared.id(), edit).expectStatus().isForbidden();
        patch(clubAdmin, shared.id(), edit).expectStatus().isForbidden();
        patch(admin, shared.id(), edit).expectStatus().isNotFound();
        patch(other, shared.id(), edit).expectStatus().isNotFound();
        patch(other, UUID.randomUUID(), edit).expectStatus().isNotFound();
        assertEquals("Club only", jdbc.queryForObject("SELECT caption FROM image WHERE id = ?", String.class, shared.id()));
        assertNull(jdbc.queryForObject("SELECT fired_on FROM image WHERE id = ?", java.sql.Date.class, shared.id()));

        UUID publicCombination = addCombination(owner, unique("Details public"), addGlaze(owner, unique("Public maker"), "Blue", "D-6"));
        var pub = body(upload(owner, publicCombination, Map.of("visibility", "PUBLIC", "kind", "TEST_TILE", "caption", "Everyone"), PNG).expectStatus().isOk(), ImageView.class);
        patch(other, pub.id(), edit).expectStatus().isForbidden();
        patch(admin, pub.id(), edit).expectStatus().isForbidden();
        patch(guest, pub.id(), edit).expectStatus().isUnauthorized();
        assertEquals("Everyone", jdbc.queryForObject("SELECT caption FROM image WHERE id = ?", String.class, pub.id()));
    }

    @Test void captionAndDateRideOnTheImageAndAreHiddenWithIt() {
        var owner = user("DetailsVisibility"); var other = user("DetailsViewer"); var guest = guest();
        UUID glaze = addGlaze(owner, unique("Vis maker"), "Blue", "D-7");
        UUID combination = addCombination(owner, unique("Details visibility"), glaze);
        var fields = Map.of("kind", "TEST_TILE", "caption", "Secret note", "firedOn", "2026-02-03");
        for (String visibility : new String[]{"PUBLIC", "PRIVATE"}) {
            var withVisibility = new HashMap<>(fields);
            withVisibility.put("visibility", visibility);
            var photo = body(upload(owner, combination, withVisibility, PNG).expectStatus().isOk(), ImageView.class);
            var seenByOther = combination(other, combination).orElseThrow().images().stream().filter(image -> image.id().equals(photo.id())).findFirst();
            var seenByGuest = combination(guest, combination).orElseThrow().images().stream().filter(image -> image.id().equals(photo.id())).findFirst();
            if (visibility.equals("PUBLIC")) {
                assertEquals("Secret note", seenByOther.orElseThrow().caption());
                assertEquals(LocalDate.of(2026, 2, 3), seenByGuest.orElseThrow().firedOn());
                assertFalse(seenByOther.orElseThrow().own());
            } else {
                assertTrue(seenByOther.isEmpty()); assertTrue(seenByGuest.isEmpty());
            }
        }
    }

    @Test void leavingPublicKeepsCaptionAndDateOnTheSuccessorPhoto() {
        var owner = user("DetailsMoved");
        var photo = combinationPhoto(owner, "PUBLIC", Map.of("caption", "Carry me", "firedOn", "2026-07-08"));
        var moved = body(owner.put().uri(photo.url() + "/visibility").body(Map.of("visibility", "PRIVATE")).exchange().expectStatus().isOk(), ImageView.class);
        assertNotEquals(photo.id(), moved.id());
        assertEquals("Carry me", moved.caption());
        assertEquals(LocalDate.of(2026, 7, 8), moved.firedOn());
        assertEquals("Carry me", jdbc.queryForObject("SELECT caption FROM image WHERE id = ?", String.class, moved.id()));
    }

    @Test void detailEditsLeaveTheDeletionLogAndAccountDeletionAlone() {
        var owner = user("DetailsDelete");
        var photo = combinationPhoto(owner, "PUBLIC", Map.of("caption", "Gone soon"));
        patch(owner, photo.id(), Map.of("caption", "Edited", "firedOn", "2026-02-03")).expectStatus().isOk();
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM image_deletion WHERE image_id = ?", Integer.class, photo.id()));
        owner.delete().uri(photo.url()).exchange().expectStatus().isNoContent();
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM image_deletion WHERE image_id = ?", Integer.class, photo.id()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM image WHERE id = ?", Integer.class, photo.id()));
    }
}
