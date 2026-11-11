package eu.glazelab.api.controller;

import eu.glazelab.api.repository.ImageRecordRepository;
import eu.glazelab.api.repository.ImageDeletionRepository;
import eu.glazelab.api.storage.ImageStorage;
import eu.glazelab.api.support.ApiTestBase;
import eu.glazelab.api.support.TestImages;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.test.web.servlet.client.RestTestClient;
import static org.junit.jupiter.api.Assertions.*;

class ImageDeletionApiTest extends ApiTestBase {
    @Autowired ImageRecordRepository images;
    @Autowired ImageDeletionRepository deletions;
    @Autowired ImageStorage storage;

    private static RestTestClient.ResponseSpec remove(RestTestClient client, UUID id) {
        return client.delete().uri("/api/images/{id}", id).exchange();
    }

    @Test void permittedDeletionsRemoveRecordAndObjectWhileMetadataSurvives() throws Exception {
        var owner = user("Owner");
        var admin = siteAdmin();
        var member = user("Member");
        var other = user("Other");
        UUID club = createClub(owner, unique("Club"));
        joinClub(owner, club, member);
        String title = unique("Blue");
        UUID glaze = addGlaze(owner, unique("Maker"), title, "1");
        UUID combo = addCombination(owner, unique("Layer"), glaze);
        UUID publicId = created(uploadGlazeImage(owner, glaze, Map.of("visibility", "PUBLIC", "kind", "TEST_TILE"), TestImages.png(24, 24)));
        UUID clubId = created(upload(owner, combo, Map.of("visibility", "CLUB", "clubId", club.toString(), "kind", "FINISHED"), TestImages.png(24, 24)));
        UUID privateId = created(upload(owner, combo, Map.of("visibility", "PRIVATE", "kind", "TEST_TILE"), TestImages.png(24, 24)));
        owner.get().uri("/api/images/{id}", publicId).exchange().expectStatus().isOk().expectHeader().valueEquals("X-Accel-Expires", "60");
        owner.get().uri("/api/images/{id}", privateId).exchange().expectStatus().isOk().expectHeader().valueEquals("X-Accel-Expires", "0");
        long before = deletions.count();
        remove(guest(), publicId).expectStatus().isUnauthorized();
        remove(other, clubId).expectStatus().isNotFound();
        remove(other, publicId).expectStatus().isForbidden();
        remove(member, clubId).expectStatus().isForbidden();
        remove(member, privateId).expectStatus().isNotFound();
        remove(other, UUID.randomUUID()).expectStatus().isNotFound();
        assertEquals(before, deletions.count());
        UUID uploader = me(owner).id();
        UUID actor = me(admin).id();
        for (var attempt : java.util.List.of(Map.entry(admin, publicId), Map.entry(owner, clubId), Map.entry(owner, privateId))) {
            String key = images.findById(attempt.getValue()).orElseThrow().getStorageKey();
            assertStored(storage, key, true);
            remove(attempt.getKey(), attempt.getValue()).expectStatus().isNoContent();
            assertFalse(images.existsById(attempt.getValue()));
            assertStored(storage, key, false);
            guest().get().uri("/api/images/{id}", attempt.getValue()).exchange().expectStatus().isNotFound();
            remove(attempt.getKey(), attempt.getValue()).expectStatus().isNotFound();
        }
        assertEquals(before + 3, deletions.count());
        var log = deletions.findAll().stream().filter(row -> row.getImageId().equals(publicId)).findFirst().orElseThrow();
        assertEquals(glaze, log.getGlazeId());
        assertNull(log.getCombinationId());
        assertEquals(title, log.getTitle());
        assertEquals("PUBLIC", log.getVisibility());
        assertEquals(uploader, log.getUploaderId());
        assertEquals(actor, log.getDeletedBy());
        assertNotNull(log.getDeletedAt());
        assertTrue(combination(owner, combo).orElseThrow().images().isEmpty());
    }

    @Test void deletionRequiresCsrf() {
        String email = email("Csrf");
        var owner = signUp(email);
        var browser = guestWithoutCsrf();
        var form = new LinkedMultiValueMap<String, String>();
        form.add("email", email);
        form.add("password", PASSWORD);
        browser.post().uri("/api/auth/login").header(CSRF_HEADER, csrf(browser))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).exchange().expectStatus().isOk();
        UUID glaze = addGlaze(owner, unique("Maker"), "Blue", "1");
        UUID id = created(uploadGlazeImage(owner, glaze, Map.of("visibility", "PUBLIC", "kind", "TEST_TILE"), TestImages.png(24, 24)));
        long before = deletions.count();
        remove(browser, id).expectStatus().isForbidden();
        browser.delete().uri("/api/images/{id}", id).header(CSRF_HEADER, "wrong").exchange().expectStatus().isForbidden();
        assertTrue(images.existsById(id));
        assertEquals(before, deletions.count());
    }
}
