package eu.glazelab.api.controller;

import eu.glazelab.api.dto.ImageDtos.ImageDeletionPageView;
import eu.glazelab.api.service.PhotoRetention;
import eu.glazelab.api.support.ApiTestBase;
import eu.glazelab.api.support.TestImages;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.junit.jupiter.api.Assertions.*;

class ImageDeletionLogApiTest extends ApiTestBase {
    @Autowired PhotoRetention retention;
    private static final java.time.OffsetDateTime FUTURE = java.time.OffsetDateTime.of(2099, 1, 1, 0, 0, 0, 0, java.time.ZoneOffset.UTC);

    @Test void onlyAdminsReadTheLogAndGlazeRemovalIsLoggedToo() {
        var user = user("Uploader");
        var admin = siteAdmin();
        guest().get().uri("/api/admin/image-deletions").exchange().expectStatus().isUnauthorized();
        user.get().uri("/api/admin/image-deletions").exchange().expectStatus().isForbidden();
        admin.get().uri("/api/admin/image-deletions?page=-1").exchange().expectStatus().isBadRequest();
        UUID glaze = addGlaze(user, unique("Maker"), "Blue", "1");
        UUID image = created(uploadGlazeImage(user, glaze, Map.of("visibility", "PRIVATE", "kind", "TEST_TILE"), TestImages.png(24, 24)));
        admin.delete().uri("/api/glazes/{id}", glaze).exchange().expectStatus().isNoContent();
        var result = body(admin.get().uri("/api/admin/image-deletions").exchange().expectStatus().isOk()
                .expectHeader().value("Cache-Control", value -> assertTrue(value.contains("no-store"))), ImageDeletionPageView.class);
        var row = result.entries().stream().filter(e -> e.imageId().equals(image)).findFirst().orElseThrow();
        assertEquals(glaze, row.glazeId());
        assertEquals("Blue", row.title());
        assertEquals("PRIVATE", row.visibility());
        assertEquals(me(user).id(), row.uploaderId());
        assertEquals(me(admin).id(), row.deletedBy());
        // Account removal can anonymise IDs without losing the audit row or breaking the response.
        jdbc.update("UPDATE image_deletion SET uploader_id = NULL, deleted_by = NULL WHERE image_id = ?", image);
        var anonymous = body(admin.get().uri("/api/admin/image-deletions").exchange().expectStatus().isOk(), ImageDeletionPageView.class)
                .entries().stream().filter(e -> e.imageId().equals(image)).findFirst().orElseThrow();
        assertNull(anonymous.uploaderId());
        assertNull(anonymous.deletedBy());
    }

    @Test void pagesAreBoundedAndNewestFirst() {
        var admin = siteAdmin();
        for (int i = 0; i < 51; i++) {
            jdbc.update("INSERT INTO image_deletion (id, image_id, glaze_id, title, visibility, deleted_at) VALUES (?, ?, ?, ?, 'PUBLIC', ?)",
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Page " + i,
                    FUTURE.plusSeconds(i));
        }
        var first = body(admin.get().uri("/api/admin/image-deletions").exchange().expectStatus().isOk(), ImageDeletionPageView.class);
        assertEquals(50, first.entries().size());
        assertTrue(first.hasMore());
        assertEquals("Page 50", first.entries().getFirst().title());
        var second = body(admin.get().uri("/api/admin/image-deletions?page=1").exchange().expectStatus().isOk(), ImageDeletionPageView.class);
        assertEquals("Page 0", second.entries().getFirst().title());
        assertTrue(first.entries().stream().noneMatch(e -> second.entries().contains(e)));
        // Don't leave future-dated fixtures masking later tests' real log entries.
        jdbc.update("DELETE FROM image_deletion WHERE deleted_at >= ?", FUTURE);
    }

    /** The daily purge deletes log rows older than the retention (90 days) and keeps younger ones. */
    @Test void theDailyPurgeDeletesEntriesPastTheRetention() {
        Instant now = Instant.now();
        UUID old = UUID.randomUUID(), young = UUID.randomUUID();
        for (var row : Map.of(old, now.minus(91, ChronoUnit.DAYS), young, now.minus(89, ChronoUnit.DAYS)).entrySet())
            jdbc.update("INSERT INTO image_deletion (id, image_id, glaze_id, title, visibility, deleted_at) VALUES (?, ?, ?, 'Old', 'PUBLIC', ?)",
                    UUID.randomUUID(), row.getKey(), UUID.randomUUID(), Timestamp.from(row.getValue()));
        var removed = new int[1];
        var lines = logged(PhotoRetention.class, () -> removed[0] = retention.purgeDeletionLog());
        assertTrue(removed[0] >= 1);
        assertEquals(List.of("Deleted " + removed[0] + " photo deletion log entries past their retention"), lines);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM image_deletion WHERE image_id = ?", Integer.class, old));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM image_deletion WHERE image_id = ?", Integer.class, young));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM image_deletion WHERE deleted_at < ?", Integer.class,
                Timestamp.from(now.minus(90, ChronoUnit.DAYS))));
        jdbc.update("DELETE FROM image_deletion WHERE image_id = ?", young);
    }
}
