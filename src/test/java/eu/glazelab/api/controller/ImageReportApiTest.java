package eu.glazelab.api.controller;

import eu.glazelab.api.dto.ImageDtos.ImageReportPageView;
import eu.glazelab.api.dto.ImageDtos.ImageReportView;
import eu.glazelab.api.model.ImageVisibility;
import eu.glazelab.api.service.PhotoRetention;
import eu.glazelab.api.support.ApiTestBase;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.RestTestClient;
import static org.junit.jupiter.api.Assertions.*;

/** Photo reports (a product decision): anyone signed in reports what they see once; site admins work the queue. */
class ImageReportApiTest extends ApiTestBase {
    private static final OffsetDateTime FUTURE = OffsetDateTime.of(2099, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static RestTestClient.ResponseSpec report(RestTestClient client, UUID image, String reason) {
        return client.post().uri("/api/images/{id}/reports", image)
                .body(reason == null ? Map.of() : Map.of("reason", reason)).exchange();
    }

    private static List<ImageReportView> queue(RestTestClient admin, UUID image) {
        return body(admin.get().uri("/api/admin/image-reports").exchange().expectStatus().isOk()
                        .expectHeader().value("Cache-Control", value -> assertTrue(value.contains("no-store"))),
                ImageReportPageView.class).entries().stream().filter(r -> r.imageId().equals(image)).toList();
    }

    @Autowired PhotoRetention retention;

    private int reports(UUID image) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM image_report WHERE image_id = ?", Integer.class, image);
    }

    /** A report nobody decided within 90 days of its opening is deleted like a dismissal; the photo and younger reports stay. */
    @Test void undecidedReportsExpireAfterNinetyDays() {
        var uploader = user("ExpiryUploader");
        var early = user("EarlyReporter");
        var late = user("LateReporter");
        var admin = siteAdmin();
        UUID image = uploadTile(uploader, addCombination(uploader, unique("Expiry"), addGlaze(uploader, unique("Expiry maker"), "Blue", "1")),
                "PUBLIC", null);
        report(early, image, "Old").expectStatus().isNoContent();
        report(late, image, "Young").expectStatus().isNoContent();
        Instant now = Instant.now();
        jdbc.update("UPDATE image_report SET created_at = ? WHERE image_id = ? AND reporter_id = ?",
                Timestamp.from(now.minus(91, ChronoUnit.DAYS)), image, me(early).id());
        jdbc.update("UPDATE image_report SET created_at = ? WHERE image_id = ? AND reporter_id = ?",
                Timestamp.from(now.minus(89, ChronoUnit.DAYS)), image, me(late).id());
        var removed = new int[1];
        var lines = logged(PhotoRetention.class, () -> removed[0] = retention.expireReports());
        assertTrue(removed[0] >= 1);
        assertEquals(List.of("Deleted " + removed[0] + " photo reports nobody decided in time"), lines);
        assertEquals(List.of("Young"), queue(admin, image).stream().map(ImageReportView::reason).toList());
        uploader.get().uri("/api/images/{id}", image).exchange().expectStatus().isOk();
        // The reporter may report again: an expired report is gone like a dismissed one.
        report(early, image, "Again").expectStatus().isNoContent();
        assertEquals(2, reports(image));
    }

    @Test void anUploaderWhoLeftTheClubStillSeesTheirPhotoAndCannotReportIt() {
        var uploader = user("ReportLeaver");
        UUID club = createClub(uploader, unique("Left club"));
        UUID glaze = addGlaze(uploader, unique("Left maker"), "Blue", "1");
        UUID image = uploadTile(uploader, addCombination(uploader, unique("Left"), glaze), "CLUB", club);
        jdbc.update("DELETE FROM membership WHERE club_id = ?", club);
        assertError(report(uploader, image, null), 400, ImageController.OWN_PHOTO_REPORT);
        assertEquals(0, reports(image));
    }

    @Test void aVisiblePhotoIsReportedOncePerUserWithAnOptionalReason() {
        var uploader = user("ReportUploader");
        String reporterEmail = email("Reporter");
        var reporter = signUp(reporterEmail);
        var admin = siteAdmin();
        String title = unique("Reported");
        UUID glaze = addGlaze(uploader, unique("Report maker"), "Blue", "1");
        UUID combination = addCombination(uploader, title, glaze);
        UUID image = uploadTile(uploader, combination, "PUBLIC", null);

        report(reporter, image, "  Not a glaze test  ").expectStatus().isNoContent();
        // A second report keeps the first one, reason included.
        report(reporter, image, "Changed my mind").expectStatus().isNoContent();
        assertEquals(1, reports(image));
        report(admin, image, " ").expectStatus().isNoContent();
        assertEquals(2, reports(image));

        var rows = queue(admin, image);
        assertEquals(2, rows.size());
        var first = rows.get(1);
        assertEquals(combination, first.combinationId());
        assertNull(first.glazeId());
        assertEquals(title, first.title());
        assertEquals(ImageVisibility.PUBLIC, first.visibility());
        assertEquals(reporterEmail.split("@")[0], first.reporter());
        assertEquals("Not a glaze test", first.reason());
        assertNull(rows.get(0).reason());
        assertFalse(rows.get(0).createdAt().isBefore(first.createdAt()));
        // The uploader by ID and current name, whom a site admin may ban (backend guide "Bans").
        assertEquals(me(uploader).id(), first.uploaderId());
        assertEquals(me(uploader).displayName(), first.uploader());

        UUID glazePhoto = created(uploadGlazeImage(uploader, glaze, Map.of("visibility", "PUBLIC", "kind", "PACKAGING"), PNG));
        report(reporter, glazePhoto, null).expectStatus().isNoContent();
        var glazeRow = queue(admin, glazePhoto).getFirst();
        assertEquals(glaze, glazeRow.glazeId());
        assertNull(glazeRow.combinationId());
        assertEquals("Blue", glazeRow.title());
        // A deleted uploader leaves a shared photo and its report without a name.
        uploader.post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange().expectStatus().isNoContent();
        var anonymous = queue(admin, glazePhoto).getFirst();
        assertNull(anonymous.uploaderId());
        assertNull(anonymous.uploader());
    }

    @Test void reportsNeedASignedInViewerWhoDidNotUploadThePhoto() {
        var uploader = user("ReportOwner");
        var other = user("ReportOther");
        UUID combination = addCombination(uploader, unique("Hidden"), addGlaze(uploader, unique("Report maker"), "Red", "1"));
        UUID privateImage = uploadTile(uploader, combination, "PRIVATE", null);
        UUID club = createClub(uploader, unique("Report club"));
        UUID clubImage = uploadTile(uploader, combination, "CLUB", club);
        UUID publicImage = uploadTile(uploader, combination, "PUBLIC", null);

        assertError(guest().post().uri("/api/images/{id}/reports", publicImage).body(Map.of()).exchange(), 401, "Please sign in.");
        assertError(report(other, privateImage, null), 404, ImageController.IMAGE_NOT_FOUND);
        assertError(report(other, clubImage, null), 404, ImageController.IMAGE_NOT_FOUND);
        assertError(report(other, UUID.randomUUID(), null), 404, ImageController.IMAGE_NOT_FOUND);
        assertError(report(uploader, publicImage, "Mine"), 400, ImageController.OWN_PHOTO_REPORT);
        report(other, publicImage, "x".repeat(501)).expectStatus().isBadRequest();
        report(other, publicImage, "x".repeat(500)).expectStatus().isNoContent();
        assertEquals(0, reports(privateImage) + reports(clubImage));
        assertEquals(1, reports(publicImage));
    }

    @Test void onlySiteAdminsReadPagesOfTheQueueNewestFirst() {
        var uploader = user("QueueUploader");
        var admin = siteAdmin();
        guest().get().uri("/api/admin/image-reports").exchange().expectStatus().isUnauthorized();
        assertError(uploader.get().uri("/api/admin/image-reports").exchange(), 403, "Site admin role required");
        admin.get().uri("/api/admin/image-reports?page=-1").exchange().expectStatus().isBadRequest();
        admin.get().uri("/api/admin/image-reports?page=100001").exchange().expectStatus().isBadRequest();
        UUID image = uploadTile(uploader, addCombination(uploader, unique("Queue"), addGlaze(uploader, unique("Queue maker"), "Green", "1")),
                "PUBLIC", null);
        // Future-dated rows of deleted reporters (several may share a photo) come first.
        for (int i = 0; i < 51; i++)
            jdbc.update("INSERT INTO image_report (id, image_id, reason, created_at) VALUES (?, ?, ?, ?)",
                    UUID.randomUUID(), image, "Page " + i, FUTURE.plusSeconds(i));
        try {
            var first = body(admin.get().uri("/api/admin/image-reports").exchange().expectStatus().isOk(), ImageReportPageView.class);
            assertEquals(50, first.entries().size());
            assertTrue(first.hasMore());
            assertEquals("Page 50", first.entries().getFirst().reason());
            assertNull(first.entries().getFirst().reporter());
            var second = body(admin.get().uri("/api/admin/image-reports?page=1").exchange().expectStatus().isOk(), ImageReportPageView.class);
            assertEquals("Page 0", second.entries().getFirst().reason());
            assertTrue(first.entries().stream().noneMatch(e -> second.entries().contains(e)));
        } finally {
            jdbc.update("DELETE FROM image_report WHERE created_at >= ?", FUTURE);
        }
    }

    @Test void dismissingRemovesOnlyTheReport() {
        var uploader = user("DismissUploader");
        var reporter = user("DismissReporter");
        var admin = siteAdmin();
        UUID image = uploadTile(uploader, addCombination(uploader, unique("Dismiss"), addGlaze(uploader, unique("Dismiss maker"), "Grey", "1")),
                "PUBLIC", null);
        report(reporter, image, "Blurry").expectStatus().isNoContent();
        UUID id = queue(admin, image).getFirst().id();
        reporter.post().uri("/api/admin/image-reports/{id}/dismiss", id).exchange().expectStatus().isForbidden();
        guest().post().uri("/api/admin/image-reports/{id}/dismiss", id).exchange().expectStatus().isUnauthorized();
        admin.post().uri("/api/admin/image-reports/{id}/dismiss", id).exchange().expectStatus().isNoContent();
        assertTrue(queue(admin, image).isEmpty());
        assertError(admin.post().uri("/api/admin/image-reports/{id}/dismiss", id).exchange(), 404, ImageReportController.REPORT_NOT_FOUND);
        assertError(admin.post().uri("/api/admin/image-reports/{id}/dismiss", UUID.randomUUID()).exchange(), 404,
                ImageReportController.REPORT_NOT_FOUND);
        guest().get().uri("/api/images/{id}", image).exchange().expectStatus().isOk();
        // Dismissed, the photo may be reported again.
        report(reporter, image, "Still blurry").expectStatus().isNoContent();
        assertEquals("Still blurry", queue(admin, image).getFirst().reason());
    }

    @Test void deletingThePhotoResolvesItsReportsAndTheLogKeepsTheEvidence() {
        var uploader = user("ResolvedUploader");
        var reporter = user("ResolvedReporter");
        var admin = siteAdmin();
        UUID image = uploadTile(uploader, addCombination(uploader, unique("Resolved"), addGlaze(uploader, unique("Resolved maker"), "Teal", "1")),
                "PUBLIC", null);
        report(reporter, image, "Offensive").expectStatus().isNoContent();
        admin.delete().uri("/api/images/{id}", image).exchange().expectStatus().isNoContent();
        assertEquals(0, reports(image));
        assertTrue(queue(admin, image).isEmpty());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM image_deletion WHERE image_id = ? AND deleted_by = ?",
                Integer.class, image, me(admin).id()));
    }

    @Test void reportsFollowAPhotoThatLeavesPublic() {
        var uploader = user("MovedUploader");
        var reporter = user("MovedReporter");
        var admin = siteAdmin();
        UUID image = uploadTile(uploader, addCombination(uploader, unique("Moved"), addGlaze(uploader, unique("Moved maker"), "Ochre", "1")),
                "PUBLIC", null);
        report(reporter, image, "Copyright").expectStatus().isNoContent();
        var before = queue(admin, image).getFirst();
        UUID moved = body(uploader.put().uri("/api/images/{id}/visibility", image).body(Map.of("visibility", "PRIVATE"))
                .exchange().expectStatus().isOk(), eu.glazelab.api.dto.ImageDtos.ImageView.class).id();
        assertNotEquals(image, moved);
        var after = queue(admin, moved);
        assertEquals(1, after.size());
        assertEquals(before.id(), after.getFirst().id());
        assertEquals(before.createdAt(), after.getFirst().createdAt());
        assertEquals(before.reporter(), after.getFirst().reporter());
        assertEquals(ImageVisibility.PRIVATE, after.getFirst().visibility());
        // Moderation still reaches it: site admins may delete any photo, hidden ones included.
        admin.delete().uri("/api/images/{id}", moved).exchange().expectStatus().isNoContent();
        assertEquals(0, reports(moved));
    }

    @Test void accountDeletionOfTheReporterKeepsTheReportWithoutAName() {
        var uploader = user("KeptUploader");
        var reporter = user("KeptReporter");
        var admin = siteAdmin();
        UUID image = uploadTile(uploader, addCombination(uploader, unique("Kept"), addGlaze(uploader, unique("Kept maker"), "Black", "1")),
                "PUBLIC", null);
        report(reporter, image, "Wrong glaze").expectStatus().isNoContent();
        reporter.post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange().expectStatus().isNoContent();
        var row = queue(admin, image).getFirst();
        assertNull(row.reporter());
        assertEquals("Wrong glaze", row.reason());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM image_report WHERE image_id = ? AND reporter_id IS NULL",
                Integer.class, image));
    }
}
