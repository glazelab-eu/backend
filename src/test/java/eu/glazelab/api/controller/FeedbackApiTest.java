package eu.glazelab.api.controller;

import eu.glazelab.api.dto.FeedbackDtos.FeedbackPageView;
import eu.glazelab.api.dto.FeedbackDtos.FeedbackView;
import eu.glazelab.api.exception.ApiErrorHandler;
import eu.glazelab.api.service.FeedbackService;
import eu.glazelab.api.storage.ImageStorage;
import eu.glazelab.api.support.ApiTestBase;
import eu.glazelab.api.support.TestImages;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.junit.jupiter.api.Assertions.*;

/** In-app feedback (backend guide "Feedback"): signed-in senders, site-admin readers, retention and account deletion. */
class FeedbackApiTest extends ApiTestBase {
    private static final Map<String, String> CONTEXT = Map.of("route", "stock", "build", "0.1.0 (abc1234)", "language", "pt-PT", "device", "PHONE");
    @Autowired ImageStorage storage;
    @Autowired FeedbackService service;

    private static UUID send(RestTestClient client, String message, Map<String, String> context, byte[]... photos) {
        return created(sendFeedback(client, message, context, photos));
    }

    private static FeedbackPageView page(RestTestClient admin, String query) {
        return body(admin.get().uri("/api/admin/feedback" + query).exchange().expectStatus().isOk(), FeedbackPageView.class);
    }

    /** The feedback {@code id} in the admins' first page (the suite's database is shared: always look up one's own). */
    private static FeedbackView find(RestTestClient admin, UUID id) {
        return page(admin, "").entries().stream().filter(entry -> entry.id().equals(id)).findFirst().orElseThrow();
    }

    private List<String> keys(UUID feedback) {
        return jdbc.queryForList("SELECT storage_key FROM feedback_photo WHERE feedback_id = ? ORDER BY photo_index", String.class, feedback);
    }

    private int rowsOf(String email) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM feedback f JOIN app_user u ON u.id = f.user_id WHERE u.email = ?", Integer.class, email);
    }

    @Test void guestsCannotSend() {
        assertError(sendFeedback(guest(), "Hello", Map.of()), 401, eu.glazelab.api.security.CurrentUser.SIGN_IN_REQUIRED);
    }

    @Test void aSignedInAccountSendsTextContextAndPhotosThatOnlySiteAdminsRead() {
        RestTestClient sender = user("Sender");
        String name = me(sender).displayName();
        byte[] withExif = TestImages.withExifOrientation(TestImages.jpeg(new java.awt.image.BufferedImage(40, 20,
                java.awt.image.BufferedImage.TYPE_INT_RGB), 0.9f), 6, false);
        UUID id = send(sender, "  The shelf jumps when I scan.  ", CONTEXT, TestImages.photo(2000, 1000), withExif);
        RestTestClient admin = siteAdmin();
        FeedbackView view = find(admin, id);
        assertEquals("The shelf jumps when I scan.", view.message());
        assertEquals("stock", view.route());
        assertEquals("0.1.0 (abc1234)", view.build());
        assertEquals("pt-PT", view.language());
        assertEquals("PHONE", view.device().name());
        assertEquals(me(sender).id(), view.senderId());
        assertEquals(name, view.sender());
        assertEquals("OPEN", view.status().name());
        assertNull(view.doneAt());
        assertEquals(2, view.photos().size());
        // Scaled to the photo limit and turned upright (EXIF orientation 6), stored without metadata.
        assertEquals(1600, view.photos().get(0).width());
        assertEquals(800, view.photos().get(0).height());
        assertEquals(20, view.photos().get(1).width());
        assertEquals(40, view.photos().get(1).height());
        for (var photo : view.photos()) {
            for (String size : List.of("", "/card")) {
                var answer = admin.get().uri("/api/admin/feedback/photos/{id}" + size, photo.id()).exchange().expectStatus().isOk()
                        .expectHeader().contentType("image/jpeg")
                        .expectHeader().valueEquals("Cache-Control", "no-store, private")
                        .expectHeader().valueEquals("X-Accel-Expires", "0")
                        .expectBody(byte[].class).returnResult().getResponseBody();
                assertFalse(new String(answer, StandardCharsets.ISO_8859_1).contains("Exif"), "metadata stripped");
            }
            // Nobody else sees a feedback photo, not even its sender.
            sender.get().uri("/api/admin/feedback/photos/{id}", photo.id()).exchange().expectStatus().isForbidden();
            guest().get().uri("/api/admin/feedback/photos/{id}", photo.id()).exchange().expectStatus().isUnauthorized();
        }
        keys(id).forEach(key -> assertStored(storage, key, true));
        // The list is for site admins only, and its JSON names no address.
        sender.get().uri("/api/admin/feedback").exchange().expectStatus().isForbidden();
        guest().get().uri("/api/admin/feedback").exchange().expectStatus().isUnauthorized();
        String json = admin.get().uri("/api/admin/feedback").exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        assertFalse(json.contains("@"), "no email address in the list");
    }

    @Test void theContextCanBeLeftOutAndATextOnlyFeedbackHasNoPhotos() {
        RestTestClient sender = user("Plain");
        UUID id = send(sender, "Just text", Map.of());
        FeedbackView view = find(siteAdmin(), id);
        assertNull(view.route());
        assertNull(view.build());
        assertNull(view.language());
        assertNull(view.device());
        assertTrue(view.photos().isEmpty());
    }

    @Test void invalidFeedbackIsRefusedAndStoresNothing() {
        String email = email("Refused");
        RestTestClient sender = signUp(email);
        assertError(sendFeedback(sender, "   ", Map.of()), 400, FeedbackController.MESSAGE_REQUIRED);
        assertError(sendFeedback(sender, "x".repeat(2001), Map.of()), 400, FeedbackController.MESSAGE_TOO_LONG);
        assertError(sendFeedback(sender, "Photos", Map.of(), PNG, PNG, PNG, PNG), 400, FeedbackController.TOO_MANY_PHOTOS);
        assertError(sendFeedback(sender, "Link", Map.of("route", "#/glazes/123?token=abc")), 400, ApiErrorHandler.INVALID_INPUT);
        assertError(sendFeedback(sender, "Language", Map.of("language", "not a language")), 400, ApiErrorHandler.INVALID_INPUT);
        assertError(sendFeedback(sender, "Device", Map.of("device", "WATCH")), 400, ApiErrorHandler.INVALID_INPUT);
        assertError(sendFeedback(sender, "Broken", Map.of(), PNG, "not an image".getBytes()), 400, ImageEncoder.UNSUPPORTED_IMAGE);
        assertError(sendFeedback(sender, "Build", Map.of("build", "x".repeat(65))), 400, ApiErrorHandler.INVALID_INPUT);
        assertError(sendFeedback(sender, "Build", Map.of("build", "0.1/../x")), 400, ApiErrorHandler.INVALID_INPUT);
        assertError(sendFeedback(sender, "Route", Map.of("route", "r".repeat(41))), 400, ApiErrorHandler.INVALID_INPUT);
        assertEquals(0, rowsOf(email));
        // Exactly 2000 characters after trimming are fine, and so are exactly three photos and the longest context.
        send(sender, " " + "y".repeat(2000) + " ", Map.of());
        assertEquals(1, rowsOf(email));
        UUID three = send(sender, "Three", Map.of("route", "r".repeat(40), "build", "b".repeat(64), "language", "zh-Hant-TW-x1"), PNG, PNG, PNG);
        assertEquals(3, keys(three).size());
    }

    /**
     * What a browser sends (FEEDBACK-1 review): {@code FormData} string parts carry no Content-Type and their line breaks
     * as CRLF (the multipart encoding normalizes them), while the app counts the textarea's LF breaks. A text the app
     * allows (2000 characters with LF) is accepted, stored with LF breaks, and decoded as UTF-8.
     */
    @Test void aBrowserTextWithLineBreaksCountsLikeTheAppAndKeepsItsCharacters() {
        RestTestClient sender = user("Breaks");
        String typed = "ü\n".repeat(999) + "ü€"; // 2000 characters as the textarea counts them
        String boundary = "----feedbackBoundary";
        byte[] body = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"message\"\r\n\r\n"
                + typed.replace("\n", "\r\n") + "\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        UUID id = created(sender.post().uri("/api/feedback").contentType(org.springframework.http.MediaType.parseMediaType(
                "multipart/form-data; boundary=" + boundary)).body(body).exchange());
        assertEquals(typed, find(siteAdmin(), id).message());
    }

    @Test void siteAdminsMarkDoneAndOpenFilterAndCount() {
        UUID id = send(user("Marked"), "Mark me", Map.of());
        RestTestClient admin = siteAdmin();
        long open = page(admin, "").open();
        assertTrue(open >= 1);
        admin.put().uri("/api/admin/feedback/{id}/status", id).body(Map.of("status", "DONE")).exchange().expectStatus().isNoContent();
        FeedbackView done = find(admin, id);
        assertEquals("DONE", done.status().name());
        assertNotNull(done.doneAt());
        assertTrue(page(admin, "?status=DONE").entries().stream().anyMatch(entry -> entry.id().equals(id)));
        assertTrue(page(admin, "?status=OPEN").entries().stream().noneMatch(entry -> entry.id().equals(id)));
        admin.put().uri("/api/admin/feedback/{id}/status", id).body(Map.of("status", "OPEN")).exchange().expectStatus().isNoContent();
        assertNull(find(admin, id).doneAt());
        assertError(admin.put().uri("/api/admin/feedback/{id}/status", UUID.randomUUID()).body(Map.of("status", "DONE")).exchange(),
                404, FeedbackService.FEEDBACK_NOT_FOUND);
        admin.put().uri("/api/admin/feedback/{id}/status", id).body(Map.of()).exchange().expectStatus().isBadRequest();
        RestTestClient other = user("NotAdmin");
        other.put().uri("/api/admin/feedback/{id}/status", id).body(Map.of("status", "DONE")).exchange().expectStatus().isForbidden();
        other.delete().uri("/api/admin/feedback/{id}", id).exchange().expectStatus().isForbidden();
        admin.get().uri("/api/admin/feedback?page=-1").exchange().expectStatus().isBadRequest();
    }

    /**
     * Marking done is idempotent: a second admin on an out-of-date page who marks it done again keeps the first time, so the 90 days
     * are counted from the real change; opening it again clears the time and the next done starts afresh.
     */
    @Test void markingDoneAgainKeepsTheTimeItWasDone() {
        UUID id = send(user("Twice"), "Mark me twice", Map.of());
        RestTestClient admin = siteAdmin();
        admin.put().uri("/api/admin/feedback/{id}/status", id).body(Map.of("status", "DONE")).exchange().expectStatus().isNoContent();
        Instant earlier = Instant.now().minus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
        jdbc.update("UPDATE feedback SET done_at = ? WHERE id = ?", Timestamp.from(earlier), id);
        admin.put().uri("/api/admin/feedback/{id}/status", id).body(Map.of("status", "DONE")).exchange().expectStatus().isNoContent();
        assertEquals(earlier, doneAt(id));
        assertEquals("DONE", find(admin, id).status().name());

        admin.put().uri("/api/admin/feedback/{id}/status", id).body(Map.of("status", "OPEN")).exchange().expectStatus().isNoContent();
        assertNull(doneAt(id));
        admin.put().uri("/api/admin/feedback/{id}/status", id).body(Map.of("status", "OPEN")).exchange().expectStatus().isNoContent();
        assertNull(doneAt(id));
        assertEquals("OPEN", find(admin, id).status().name());
        admin.put().uri("/api/admin/feedback/{id}/status", id).body(Map.of("status", "DONE")).exchange().expectStatus().isNoContent();
        assertTrue(doneAt(id).isAfter(Instant.now().minus(1, ChronoUnit.MINUTES)));
    }

    private Instant doneAt(UUID id) {
        Timestamp at = jdbc.queryForObject("SELECT done_at FROM feedback WHERE id = ?", Timestamp.class, id);
        return at == null ? null : at.toInstant();
    }

    @Test void deletingAFeedbackDeletesItsPhotos() {
        UUID id = send(user("Deleted"), "Delete me", Map.of(), PNG);
        List<String> keys = keys(id);
        RestTestClient admin = siteAdmin();
        UUID photo = find(admin, id).photos().getFirst().id();
        admin.delete().uri("/api/admin/feedback/{id}", id).exchange().expectStatus().isNoContent();
        keys.forEach(key -> assertStored(storage, key, false));
        assertError(admin.delete().uri("/api/admin/feedback/{id}", id).exchange(), 404, FeedbackService.FEEDBACK_NOT_FOUND);
        assertError(admin.get().uri("/api/admin/feedback/photos/{id}", photo).exchange(), 404, ImageController.IMAGE_NOT_FOUND);
    }

    /** The daily purge deletes feedback marked done more than 90 days ago, with its photos; open feedback stays whatever its age. */
    @Test void doneFeedbackGoesAfterTheRetention() {
        RestTestClient sender = user("Retention");
        UUID expired = send(sender, "Old and done", Map.of(), PNG), recent = send(sender, "Recently done", Map.of()),
                open = send(sender, "Old and open", Map.of());
        List<String> keys = keys(expired);
        Timestamp old = Timestamp.from(Instant.now().minus(91, ChronoUnit.DAYS));
        jdbc.update("UPDATE feedback SET status = 'DONE', done_at = ? WHERE id = ?", old, expired);
        jdbc.update("UPDATE feedback SET status = 'DONE', done_at = ? WHERE id = ?", Timestamp.from(Instant.now().minus(89, ChronoUnit.DAYS)), recent);
        jdbc.update("UPDATE feedback SET created_at = ? WHERE id = ?", old, open);
        assertTrue(service.purge() >= 1);
        assertEquals(List.of(recent, open), jdbc.queryForList("SELECT id FROM feedback WHERE id IN (?, ?, ?) ORDER BY message DESC",
                UUID.class, expired, recent, open));
        keys.forEach(key -> assertStored(storage, key, false));
    }

    @Test void deletingTheAccountDeletesItsFeedbackAndPhotos() {
        String email = email("Leaving");
        RestTestClient sender = signUp(email);
        UUID id = send(sender, "Bye", Map.of(), PNG);
        UUID kept = send(user("Staying"), "Still here", Map.of());
        List<String> keys = keys(id);
        sender.post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange().expectStatus().isNoContent();
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM feedback WHERE id = ?", Integer.class, id));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM feedback_photo WHERE feedback_id = ?", Integer.class, id));
        keys.forEach(key -> assertStored(storage, key, false));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM feedback WHERE id = ?", Integer.class, kept));
    }
}
