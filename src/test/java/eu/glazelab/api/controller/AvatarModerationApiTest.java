package eu.glazelab.api.controller;

import eu.glazelab.api.dto.AvatarDtos.AvatarReportPageView;
import eu.glazelab.api.dto.AvatarDtos.AvatarReportView;
import eu.glazelab.api.exception.ApiErrorHandler;
import eu.glazelab.api.model.AvatarPictureState;
import eu.glazelab.api.model.AvatarReportStatus;
import eu.glazelab.api.repository.AppUserRepository;
import eu.glazelab.api.security.CurrentUser;
import eu.glazelab.api.security.TermsFilter;
import eu.glazelab.api.service.AvatarService;
import eu.glazelab.api.storage.ImageStorage;
import eu.glazelab.api.support.ApiTestBase;
import eu.glazelab.api.support.TestImages;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.test.web.servlet.client.RestTestClient.ResponseSpec;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Profile-picture moderation (backend guide "Profile pictures"): reports outlive their picture, a reported picture
 * stays as quarantined evidence that only site admins see and the daily purge deletes, and a picture a site admin
 * removed cannot be uploaded again by anyone. Every blocked picture here is a {@link TestImages#scene} of this class's
 * own seeds, so no other test's upload is refused by these blocks.
 */
class AvatarModerationApiTest extends ApiTestBase {
    private static final AtomicLong SEEDS = new AtomicLong(31_400);
    private static final List<String> SIZES = List.of("", "/small");

    @Autowired ImageStorage storage;
    @Autowired AvatarService avatars;
    @Autowired AppUserRepository users;

    private static byte[] scene() {
        return TestImages.scene(SEEDS.incrementAndGet());
    }

    private String key(UUID avatar) {
        return jdbc.queryForObject("SELECT storage_key FROM user_avatar WHERE id = ?", String.class, avatar);
    }

    private String evidenceKey(UUID avatar) {
        return jdbc.queryForObject("SELECT storage_key FROM avatar_evidence WHERE avatar_id = ?", String.class, avatar);
    }

    private static ResponseSpec report(RestTestClient client, UUID avatar) {
        return client.post().uri("/api/avatars/{id}/reports", avatar).body(Map.of("reason", "Shows my neighbour")).exchange();
    }

    private static List<AvatarReportView> queue(RestTestClient admin, UUID avatar) {
        return body(admin.get().uri("/api/admin/avatar-reports").exchange().expectStatus().isOk(), AvatarReportPageView.class)
                .entries().stream().filter(entry -> entry.avatarId().equals(avatar)).toList();
    }

    private static AvatarReportView only(RestTestClient admin, UUID avatar) {
        var entries = queue(admin, avatar);
        assertEquals(1, entries.size(), entries.toString());
        return entries.getFirst();
    }

    private static ResponseSpec evidence(RestTestClient client, UUID report) {
        return client.get().uri("/api/admin/avatar-reports/{id}/picture", report).exchange();
    }

    private static byte[] adminPicture(RestTestClient admin, UUID report) {
        return evidence(admin, report).expectStatus().isOk().expectHeader().contentType(MediaType.IMAGE_JPEG)
                .expectHeader().value("Cache-Control", value -> assertTrue(value.contains("no-store") && value.contains("private")
                        && !value.contains("max-age") && !value.contains("public"), value))
                .expectHeader().valueEquals("X-Accel-Expires", "0").expectHeader().doesNotExist("Set-Cookie")
                .expectBody(byte[].class).returnResult().getResponseBody();
    }

    private static ResponseSpec act(RestTestClient admin, UUID report, String decision) {
        return admin.post().uri("/api/admin/avatar-reports/{id}/" + decision, report).exchange();
    }

    private static void deleteAccount(RestTestClient client) {
        client.post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange().expectStatus().isNoContent();
    }

    /** No public or user endpoint answers a picture that is no longer current: owner, others, admins and guests alike. */
    private void neverServed(UUID avatar, RestTestClient... clients) {
        for (RestTestClient client : clients) {
            for (String size : SIZES) {
                client.get().uri("/api/avatars/" + avatar + size).exchange().expectStatus().isNotFound();
                client.head().uri("/api/avatars/" + avatar + size).exchange().expectStatus().isNotFound();
            }
            for (String size : List.of("", "/card")) client.get().uri("/api/images/" + avatar + size).exchange().expectStatus().isNotFound();
        }
        for (String size : SIZES) guest().get().uri("/api/avatars/" + avatar + size).exchange().expectStatus().isUnauthorized();
    }

    /**
     * A reported picture that is replaced keeps its objects as evidence: never at its old URLs (not even for its owner or
     * a site admin), only on the site admins' report endpoint, with no-store; others get 401/403, unknown reports 404.
     */
    @Test void evidenceIsServedOnlyToSiteAdminsThroughTheReport() {
        RestTestClient ada = user("Ada");
        RestTestClient bea = user("Bea");
        RestTestClient admin = siteAdmin();
        UUID picture = created(uploadAvatar(ada, scene()));
        String key = key(picture);
        report(bea, picture).expectStatus().isNoContent();
        AvatarReportView current = only(admin, picture);
        assertEquals(AvatarPictureState.CURRENT, current.picture());
        assertTrue(current.hasPicture());
        // While it is current the admin endpoint shows the current picture (still no-store there).
        byte[] stored = adminPicture(admin, current.id());
        assertArrayEquals(stored, get(bea, picture));

        UUID next = avatar(ada);
        neverServed(picture, ada, bea, admin);
        assertStored(storage, key, true);
        assertEquals(key, evidenceKey(picture));
        assertArrayEquals(stored, adminPicture(admin, current.id()));
        for (RestTestClient other : List.of(ada, bea))
            assertError(evidence(other, current.id()), 403, "Site admin role required");
        evidence(guest(), current.id()).expectStatus().isUnauthorized();
        assertError(evidence(admin, UUID.randomUUID()), 404, ImageReportController.REPORT_NOT_FOUND);
        AvatarReportView replaced = only(admin, picture);
        assertEquals(AvatarPictureState.REPLACED, replaced.picture());
        assertEquals(AvatarReportStatus.OPEN, replaced.status());
        assertEquals(me(ada).id(), replaced.ownerId());
        // The views and the author listing name only the current picture.
        assertEquals(next, me(ada).avatarId());
        String authors = bea.get().uri("/api/combinations/author-avatars").exchange().expectBody(String.class).returnResult().getResponseBody();
        assertFalse(authors.contains(picture.toString()));
    }

    private static byte[] get(RestTestClient client, UUID avatar) {
        return client.get().uri("/api/avatars/" + avatar).exchange().expectStatus().isOk().expectBody(byte[].class)
                .returnResult().getResponseBody();
    }

    /**
     * A decision re-reads the admin's flag under the admin's account lock: a flag taken away (SQL) after the request's
     * early check refuses with 403 and decides nothing (the service is called with the admin as the request saw it).
     */
    @Test void aDecisionReReadsTheSiteAdminFlagUnderTheLock() {
        RestTestClient admin = siteAdmin();
        RestTestClient ada = user("Ada");
        UUID picture = created(uploadAvatar(ada, scene()));
        report(user("Bea"), picture).expectStatus().isNoContent();
        UUID report = only(admin, picture).id();
        var asSeen = users.findById(me(admin).id()).orElseThrow();
        assertTrue(asSeen.isSiteAdmin());
        jdbc.update("UPDATE app_user SET site_admin = FALSE WHERE id = ?", asSeen.getId());
        for (AvatarReportStatus decision : List.of(AvatarReportStatus.ACTIONED, AvatarReportStatus.DISMISSED)) {
            var refused = assertThrows(ResponseStatusException.class, () -> avatars.decide(report, asSeen, decision));
            assertEquals(403, refused.getStatusCode().value());
            assertEquals(CurrentUser.SITE_ADMIN_REQUIRED, refused.getReason());
        }
        jdbc.update("UPDATE app_user SET site_admin = TRUE WHERE id = ?", asSeen.getId());
        assertEquals(AvatarReportStatus.OPEN, only(admin, picture).status());
        assertEquals(picture, me(ada).avatarId());
    }

    /** Reports stay whichever way their picture goes, and the queue says how it went. */
    @Test void reportsSurviveEveryWayAPictureGoes() {
        RestTestClient ada = user("Ada");
        RestTestClient bea = user("Bea");
        RestTestClient admin = siteAdmin();
        UUID replaced = created(uploadAvatar(ada, scene()));
        report(bea, replaced).expectStatus().isNoContent();
        UUID ownerRemoved = created(uploadAvatar(ada, scene()));
        report(bea, ownerRemoved).expectStatus().isNoContent();
        ada.delete().uri("/api/avatars/{id}", ownerRemoved).exchange().expectStatus().isNoContent();
        UUID adminRemoved = created(uploadAvatar(ada, scene()));
        report(bea, adminRemoved).expectStatus().isNoContent();
        admin.delete().uri("/api/avatars/{id}", adminRemoved).exchange().expectStatus().isNoContent();
        UUID actioned = created(uploadAvatar(ada, scene()));
        report(bea, actioned).expectStatus().isNoContent();
        act(admin, only(admin, actioned).id(), "remove").expectStatus().isNoContent();
        assertNull(me(ada).avatarId());

        assertEquals(AvatarPictureState.REPLACED, only(admin, replaced).picture());
        assertEquals(AvatarReportStatus.OPEN, only(admin, replaced).status());
        assertEquals(AvatarPictureState.OWNER_REMOVED, only(admin, ownerRemoved).picture());
        assertEquals(AvatarReportStatus.OPEN, only(admin, ownerRemoved).status());
        for (UUID removed : List.of(adminRemoved, actioned)) {
            AvatarReportView entry = only(admin, removed);
            assertEquals(AvatarPictureState.ADMIN_REMOVED, entry.picture());
            assertEquals(AvatarReportStatus.ACTIONED, entry.status());
            assertEquals(me(admin).displayName(), entry.closedBy());
            assertNotNull(entry.closedAt());
            assertTrue(entry.hasPicture(), "kept for the retention after closing");
            assertNotNull(evidenceKey(removed));
            neverServed(removed, ada, bea, admin);
        }
        for (UUID gone : List.of(replaced, ownerRemoved)) assertTrue(only(admin, gone).hasPicture());
        // The queue filters by status.
        var open = body(admin.get().uri("/api/admin/avatar-reports?status=OPEN").exchange().expectStatus().isOk(), AvatarReportPageView.class);
        assertTrue(open.entries().stream().allMatch(entry -> entry.status() == AvatarReportStatus.OPEN));
        assertTrue(open.entries().stream().anyMatch(entry -> entry.avatarId().equals(replaced)));
        assertTrue(open.entries().stream().noneMatch(entry -> entry.avatarId().equals(actioned)));
        admin.get().uri("/api/admin/avatar-reports?status=REOPENED").exchange().expectStatus().isBadRequest();
    }

    /**
     * A product decision (it replaced an earlier rule of evidence kept after the account's deletion): the
     * account's pictures go with it, the current one and every evidence copy, while the fingerprints stay, so a blocked
     * picture stays blocked for every account (a new registration included). Reports about the pictures keep only their
     * status and dates: open ones are CLOSED (nobody can decide them without the picture), every text is deleted.
     */
    @Test void accountDeletionDeletesThePicturesAndEvidenceButKeepsTheFingerprints() {
        RestTestClient ada = user("Ada");
        RestTestClient cyd = user("Cyd");
        RestTestClient bea = user("Bea");
        RestTestClient admin = siteAdmin();
        byte[] adas = scene(), cyds = scene(), blocked = scene();
        // Ada's earlier reported picture is evidence already, one was removed and blocked by an admin, her current one
        // has an open report; Cyd's reported picture was dismissed. Ada reported Bea's picture herself.
        UUID removed = created(uploadAvatar(ada, blocked));
        report(bea, removed).expectStatus().isNoContent();
        act(admin, only(admin, removed).id(), "remove").expectStatus().isNoContent();
        UUID earlier = created(uploadAvatar(ada, scene()));
        report(bea, earlier).expectStatus().isNoContent();
        UUID current = created(uploadAvatar(ada, adas));
        report(bea, current).expectStatus().isNoContent();
        UUID dismissed = created(uploadAvatar(cyd, cyds));
        report(bea, dismissed).expectStatus().isNoContent();
        act(admin, only(admin, dismissed).id(), "dismiss").expectStatus().isNoContent();
        UUID beas = avatar(bea);
        report(ada, beas).expectStatus().isNoContent();
        List<String> keys = List.of(key(current), key(dismissed), evidenceKey(earlier), evidenceKey(removed));
        UUID adaId = me(ada).id();

        deleteAccount(ada);
        deleteAccount(cyd);
        for (String key : keys) assertStored(storage, key, false);
        neverServed(current, bea, admin);
        neverServed(dismissed, bea, admin);
        for (UUID picture : List.of(current, dismissed, earlier, removed)) {
            AvatarReportView entry = only(admin, picture);
            assertNull(entry.ownerId());
            assertNull(entry.owner());
            assertNull(entry.reason(), "the reporter's text may name the owner");
            assertFalse(entry.hasPicture());
            assertError(evidence(admin, entry.id()), 404, AvatarService.AVATAR_NOT_FOUND);
            assertNull(evidenceKey(picture));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM avatar_evidence WHERE avatar_id = ? AND upload_sha256 IS NOT NULL"
                    + " AND stored_sha256 IS NOT NULL", Integer.class, picture), "the fingerprints stay");
        }
        assertEquals(AvatarPictureState.ACCOUNT_DELETED, only(admin, current).picture());
        assertEquals(AvatarPictureState.REPLACED, only(admin, earlier).picture());
        assertEquals(AvatarPictureState.ADMIN_REMOVED, only(admin, removed).picture());
        for (UUID picture : List.of(current, earlier)) {
            AvatarReportView entry = only(admin, picture);
            assertEquals(AvatarReportStatus.CLOSED, entry.status());
            assertNotNull(entry.closedAt());
            assertNull(entry.closedBy());
            for (String decision : List.of("remove", "dismiss"))
                assertError(act(admin, entry.id(), decision), 409, ApiErrorHandler.CHANGED_MEANWHILE);
        }
        assertEquals(AvatarReportStatus.ACTIONED, only(admin, removed).status());
        assertEquals(AvatarReportStatus.DISMISSED, only(admin, dismissed).status());
        // Ada's own report of someone else keeps its text (it is about Bea's picture), without Ada.
        AvatarReportView adasReport = only(admin, beas);
        assertNull(adasReport.reporter());
        assertEquals("Shows my neighbour", adasReport.reason());
        // Nothing links what is left to the deleted account.
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM avatar_report WHERE owner_id = ?", Integer.class, adaId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE id = ?", Integer.class, adaId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE LOWER(table_name) IN"
                + " ('avatar_evidence', 'avatar_block') AND (LOWER(column_name) LIKE '%user%' OR LOWER(column_name) LIKE '%owner%')",
                Integer.class));

        // The blocked picture stays blocked with the generic answer; the others were never decided against.
        RestTestClient newcomer = user("AdaAgain");
        assertError(uploadAvatar(newcomer, blocked), 409, AvatarService.PICTURE_REFUSED);
        assertNull(me(newcomer).avatarId());
        created(uploadAvatar(newcomer, adas));
        created(uploadAvatar(user("CydAgain"), cyds));
    }

    /** The daily purge deletes kept objects once every report of the picture was closed for the retention (90 days). */
    @Test void thePurgeDeletesEvidenceAfterTheRetention() {
        RestTestClient ada = user("Ada");
        RestTestClient bea = user("Bea");
        RestTestClient cyd = user("Cyd");
        RestTestClient admin = siteAdmin();
        UUID expired = created(uploadAvatar(ada, scene()));
        report(bea, expired).expectStatus().isNoContent();
        UUID recent = created(uploadAvatar(ada, scene()));
        report(bea, recent).expectStatus().isNoContent();
        UUID stillOpen = created(uploadAvatar(ada, scene()));
        report(bea, stillOpen).expectStatus().isNoContent();
        report(cyd, stillOpen).expectStatus().isNoContent();
        avatar(ada);
        act(admin, only(admin, expired).id(), "dismiss").expectStatus().isNoContent();
        act(admin, only(admin, recent).id(), "remove").expectStatus().isNoContent();
        // Closed 91 and 89 days ago; the third stays open however old it is: the evidence purge never closes a report
        // (expireReports does) and never deletes an open report's evidence.
        Instant now = Instant.now();
        jdbc.update("UPDATE avatar_report SET closed_at = ? WHERE avatar_id = ?", Timestamp.from(now.minus(91, ChronoUnit.DAYS)), expired);
        jdbc.update("UPDATE avatar_report SET closed_at = ? WHERE avatar_id = ?", Timestamp.from(now.minus(89, ChronoUnit.DAYS)), recent);
        jdbc.update("UPDATE avatar_report SET created_at = ? WHERE avatar_id = ?", Timestamp.from(now.minus(400, ChronoUnit.DAYS)), stillOpen);
        String expiredKey = evidenceKey(expired), recentKey = evidenceKey(recent), openKey = evidenceKey(stillOpen);
        // Evidence an account deletion left before V32 (its open report's owner nulled) goes with the next purge too.
        UUID orphaned = created(uploadAvatar(cyd, scene()));
        report(bea, orphaned).expectStatus().isNoContent();
        avatar(cyd);
        String orphanedKey = evidenceKey(orphaned);
        jdbc.update("UPDATE avatar_report SET owner_id = NULL WHERE avatar_id = ?", orphaned);

        assertTrue(avatars.purgeEvidence() >= 2);
        assertNull(evidenceKey(expired));
        assertStored(storage, expiredKey, false);
        assertNull(evidenceKey(orphaned));
        assertStored(storage, orphanedKey, false);
        AvatarReportView purged = only(admin, expired);
        assertFalse(purged.hasPicture());
        assertEquals(AvatarPictureState.REPLACED, purged.picture());
        assertEquals(AvatarReportStatus.DISMISSED, purged.status());
        assertError(evidence(admin, purged.id()), 404, AvatarService.AVATAR_NOT_FOUND);
        for (var kept : List.of(Map.entry(recent, recentKey), Map.entry(stillOpen, openKey))) {
            assertEquals(kept.getValue(), evidenceKey(kept.getKey()));
            assertStored(storage, kept.getValue(), true);
        }
        assertEquals(2, queue(admin, stillOpen).size());
        // A second run finds nothing more of these; the hashes stay for the block.
        avatars.purgeEvidence();
        assertEquals(recentKey, evidenceKey(recent));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM avatar_evidence WHERE avatar_id = ? AND stored_sha256 IS NOT NULL",
                Integer.class, expired));
    }

    /**
     * Reports nobody decided within 90 days of their opening are CLOSED without a decision; a picture left with no open
     * report loses its evidence copy at once (objects after commit), a current picture stays shown, younger reports stay
     * open, and a late decision answers 409.
     */
    @Test void undecidedReportsExpireAfterNinetyDays() {
        RestTestClient bea = user("Bea");
        RestTestClient cyd = user("Cyd");
        RestTestClient admin = siteAdmin();
        RestTestClient ada = user("Ada");
        UUID shown = created(uploadAvatar(ada, scene()));
        report(bea, shown).expectStatus().isNoContent();
        RestTestClient dan = user("Dan");
        UUID replaced = created(uploadAvatar(dan, scene()));
        report(bea, replaced).expectStatus().isNoContent();
        avatar(dan);
        RestTestClient eve = user("Eve");
        UUID mixed = created(uploadAvatar(eve, scene()));
        report(bea, mixed).expectStatus().isNoContent();
        report(cyd, mixed).expectStatus().isNoContent();
        avatar(eve);
        RestTestClient fay = user("Fay");
        UUID young = created(uploadAvatar(fay, scene()));
        report(bea, young).expectStatus().isNoContent();
        avatar(fay);
        Instant now = Instant.now();
        Timestamp old = Timestamp.from(now.minus(91, ChronoUnit.DAYS));
        for (UUID picture : List.of(shown, replaced))
            jdbc.update("UPDATE avatar_report SET created_at = ? WHERE avatar_id = ?", old, picture);
        jdbc.update("UPDATE avatar_report SET created_at = ? WHERE avatar_id = ? AND reporter_id = ?", old, mixed, me(bea).id());
        jdbc.update("UPDATE avatar_report SET created_at = ? WHERE avatar_id = ?", Timestamp.from(now.minus(89, ChronoUnit.DAYS)), young);
        String replacedKey = evidenceKey(replaced), mixedKey = evidenceKey(mixed), youngKey = evidenceKey(young);

        var closedCount = new int[1];
        var lines = logged(AvatarService.class, () -> closedCount[0] = avatars.expireReports());
        assertTrue(closedCount[0] >= 3);
        // Counts only: the reports' closing, then the evidence purge it runs.
        assertEquals("Closed " + closedCount[0] + " profile picture reports nobody decided in time", lines.getFirst());
        assertEquals(2, lines.size(), lines.toString());
        assertTrue(lines.get(1).matches("Deleted \\d+ profile picture evidence copies past their retention"), lines.get(1));
        AvatarReportView closed = only(admin, shown);
        assertEquals(AvatarReportStatus.CLOSED, closed.status());
        assertNull(closed.closedBy());
        assertNotNull(closed.closedAt());
        assertEquals(AvatarPictureState.CURRENT, closed.picture());
        bea.get().uri("/api/avatars/{id}", shown).exchange().expectStatus().isOk();
        assertError(act(admin, closed.id(), "dismiss"), 409, ApiErrorHandler.CHANGED_MEANWHILE);
        assertError(act(admin, closed.id(), "remove"), 409, ApiErrorHandler.CHANGED_MEANWHILE);
        // Its reports are closed without a decision, so replacing it keeps no copy.
        String shownKey = key(shown);
        avatar(ada);
        assertNull(evidenceKey(shown));
        assertStored(storage, shownKey, false);

        AvatarReportView gone = only(admin, replaced);
        assertEquals(AvatarReportStatus.CLOSED, gone.status());
        assertFalse(gone.hasPicture());
        assertNull(evidenceKey(replaced));
        assertStored(storage, replacedKey, false);
        assertError(evidence(admin, gone.id()), 404, AvatarService.AVATAR_NOT_FOUND);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM avatar_evidence WHERE avatar_id = ? AND stored_sha256 IS NOT NULL",
                Integer.class, replaced));

        // One old and one young report: only the old one closes, and the evidence stays for the open one.
        assertEquals(List.of(AvatarReportStatus.OPEN, AvatarReportStatus.CLOSED),
                queue(admin, mixed).stream().map(AvatarReportView::status).sorted().toList());
        assertEquals(mixedKey, evidenceKey(mixed));
        assertStored(storage, mixedKey, true);
        assertEquals(AvatarReportStatus.OPEN, only(admin, young).status());
        assertEquals(youngKey, evidenceKey(young));
        assertStored(storage, youngKey, true);
    }

    /** A reported picture whose reports were all closed long ago is not kept when it goes. */
    @Test void aPictureWhoseReportsClosedLongAgoIsNotKept() {
        RestTestClient ada = user("Ada");
        RestTestClient bea = user("Bea");
        RestTestClient admin = siteAdmin();
        UUID picture = created(uploadAvatar(ada, scene()));
        report(bea, picture).expectStatus().isNoContent();
        act(admin, only(admin, picture).id(), "dismiss").expectStatus().isNoContent();
        jdbc.update("UPDATE avatar_report SET closed_at = ? WHERE avatar_id = ?",
                Timestamp.from(Instant.now().minus(91, ChronoUnit.DAYS)), picture);
        String key = key(picture);
        ada.delete().uri("/api/avatars/{id}", picture).exchange().expectStatus().isNoContent();
        assertStored(storage, key, false);
        assertNull(evidenceKey(picture));
        AvatarReportView entry = only(admin, picture);
        assertEquals(AvatarPictureState.OWNER_REMOVED, entry.picture());
        assertFalse(entry.hasPicture());
    }

    /**
     * Blocks: a site admin's removal refuses the same picture for every account (the same file, the stored copy sent
     * again, a re-encode, a slight re-crop) with one generic answer, and nothing is stored; a different picture and
     * pictures that existed before the block are unaffected.
     */
    @Test void aRemovedPictureCannotBeUploadedAgainByAnyone() {
        RestTestClient ada = user("Ada");
        RestTestClient bea = user("Bea");
        RestTestClient cyd = user("Cyd");
        RestTestClient admin = siteAdmin();
        long seed = SEEDS.incrementAndGet();
        BufferedImage scene = TestImages.scene(seed, 600, 600);
        byte[] upload = TestImages.jpeg(scene, 0.92f);
        UUID earlierCopy = created(uploadAvatar(cyd, upload));
        UUID picture = created(uploadAvatar(ada, upload));
        byte[] stored = get(bea, picture);
        UUID adasBefore = me(ada).avatarId();
        admin.delete().uri("/api/avatars/{id}", picture).exchange().expectStatus().isNoContent();
        UUID kept = avatar(ada);

        String refusal = statusAndBody(uploadAvatar(ada, upload));
        assertEquals("409 {\"error\":\"" + AvatarService.PICTURE_REFUSED + "\"}", refusal);
        for (RestTestClient uploader : List.of(ada, bea, admin, user("Newcomer")))
            for (byte[] copy : List.of(upload, stored, TestImages.jpeg(scene, 0.6f), TestImages.png(scene),
                    TestImages.jpeg(TestImages.crop(scene, 12, 0, 588, 588), 0.85f)))
                assertEquals(refusal, statusAndBody(uploadAvatar(uploader, copy)));
        assertEquals(kept, me(ada).avatarId(), "a refusal keeps the current picture");
        assertNotEquals(adasBefore, kept);
        assertNull(me(bea).avatarId());
        // Existing pictures are not scanned: Cyd's earlier copy stays.
        get(bea, earlierCopy);
        // A clearly different picture is accepted.
        created(uploadAvatar(bea, scene()));
        // The owner's own removal blocks nothing.
        byte[] own = scene();
        UUID mine = created(uploadAvatar(bea, own));
        bea.delete().uri("/api/avatars/{id}", mine).exchange().expectStatus().isNoContent();
        created(uploadAvatar(bea, own));
    }

    /** The configured distance (8) is inclusive; an almost plain picture is blocked by its exact hashes only. */
    @Test void theBlockDistanceIsInclusiveAndPlainPicturesNeedAnExactMatch() {
        RestTestClient ada = user("Ada");
        byte[] upload = scene();
        UUID probe = created(uploadAvatar(ada, upload));
        long dhash = jdbc.queryForObject("SELECT dhash FROM user_avatar WHERE id = ?", Long.class, probe);
        int bits = Long.bitCount(dhash);
        assertTrue(bits >= 8 && bits <= 56, "an informative hash: " + bits);
        ada.delete().uri("/api/avatars/{id}", probe).exchange().expectStatus().isNoContent();
        UUID block = UUID.randomUUID();
        String random = HexFormat.of().formatHex(new byte[32]).replace('0', 'f');
        try {
            jdbc.update("INSERT INTO avatar_block (id, upload_sha256, stored_sha256, dhash, created_at) VALUES (?, ?, ?, ?, ?)",
                    block, random, random, dhash ^ 0xFFL, Timestamp.from(Instant.now()));
            assertError(uploadAvatar(ada, upload), 409, AvatarService.PICTURE_REFUSED);
            jdbc.update("UPDATE avatar_block SET dhash = ? WHERE id = ?", dhash ^ 0x1FFL, block);
            assertNotEquals(probe, created(uploadAvatar(ada, upload)));
        } finally {
            jdbc.update("DELETE FROM avatar_block WHERE id = ?", block);
        }

        RestTestClient admin = siteAdmin();
        BufferedImage plain = new BufferedImage(140, 140, BufferedImage.TYPE_INT_RGB);
        var graphics = plain.createGraphics();
        graphics.setColor(new Color(17, 99, 201));
        graphics.fillRect(0, 0, 140, 140);
        graphics.dispose();
        byte[] flat = TestImages.png(plain);
        UUID removed = created(uploadAvatar(ada, flat));
        admin.delete().uri("/api/avatars/{id}", removed).exchange().expectStatus().isNoContent();
        assertError(uploadAvatar(ada, flat), 409, AvatarService.PICTURE_REFUSED);
        BufferedImage other = new BufferedImage(130, 130, BufferedImage.TYPE_INT_RGB);
        var paint = other.createGraphics();
        paint.setColor(new Color(201, 17, 99));
        paint.fillRect(0, 0, 130, 130);
        paint.dispose();
        created(uploadAvatar(ada, TestImages.png(other)));
    }

    /** Decisions: idempotent per decision, 409 across decisions, ACTIONED blocks a picture its owner replaced first. */
    @Test void decisionsApplyToThePictureEvenAfterItWasReplaced() {
        RestTestClient ada = user("Ada");
        RestTestClient bea = user("Bea");
        RestTestClient cyd = user("Cyd");
        RestTestClient admin = siteAdmin();
        byte[] upload = scene();
        UUID picture = created(uploadAvatar(ada, upload));
        report(bea, picture).expectStatus().isNoContent();
        report(cyd, picture).expectStatus().isNoContent();
        UUID next = avatar(ada);
        var entries = queue(admin, picture);
        assertEquals(2, entries.size());
        assertError(act(bea, entries.getFirst().id(), "remove"), 403, "Site admin role required");
        act(guest(), entries.getFirst().id(), "remove").expectStatus().isUnauthorized();
        assertError(act(admin, UUID.randomUUID(), "remove"), 404, ImageReportController.REPORT_NOT_FOUND);
        act(admin, entries.getFirst().id(), "remove").expectStatus().isNoContent();
        act(admin, entries.getFirst().id(), "remove").expectStatus().isNoContent();
        assertTrue(queue(admin, picture).stream().allMatch(entry -> entry.status() == AvatarReportStatus.ACTIONED),
                "one decision closes every open report of the picture");
        assertError(act(admin, entries.get(1).id(), "dismiss"), 409, ApiErrorHandler.CHANGED_MEANWHILE);
        assertEquals(next, me(ada).avatarId(), "the current picture is not touched");
        assertTrue(queue(admin, picture).stream().allMatch(entry -> entry.picture() == AvatarPictureState.REPLACED));
        assertError(uploadAvatar(ada, upload), 409, AvatarService.PICTURE_REFUSED);
        assertError(uploadAvatar(bea, upload), 409, AvatarService.PICTURE_REFUSED);
    }

    /**
     * Withdrawing one's own picture never needs new terms (31-1 review); a site admin's removal, uploads, reports and
     * decisions still do.
     */
    @Test void theOwnerRemovesTheirPictureWithPendingTermsAndNobodyElseDoes() {
        RestTestClient ada = user("Ada");
        RestTestClient bea = user("Bea");
        RestTestClient admin = siteAdmin();
        UUID adas = avatar(ada), beas = avatar(bea);
        report(bea, adas).expectStatus().isNoContent();
        UUID reportId = only(admin, adas).id();
        jdbc.update("UPDATE app_user SET accepted_terms_version = '2000-01-01' WHERE id IN (?, ?, ?)", me(ada).id(), me(bea).id(), me(admin).id());
        for (RestTestClient pending : List.of(bea, admin))
            assertError(pending.delete().uri("/api/avatars/{id}", adas).exchange(), 428, TermsFilter.TERMS_REQUIRED);
        assertError(uploadAvatar(ada, TestImages.unique()), 428, TermsFilter.TERMS_REQUIRED);
        assertError(report(ada, beas), 428, TermsFilter.TERMS_REQUIRED);
        assertError(act(admin, reportId, "dismiss"), 428, TermsFilter.TERMS_REQUIRED);
        assertError(act(admin, reportId, "remove"), 428, TermsFilter.TERMS_REQUIRED);
        assertError(admin.delete().uri("/api/avatars/{id}", UUID.randomUUID()).exchange(), 404, AvatarService.AVATAR_NOT_FOUND);
        // Only that route is exempt: other removals and the avatar routes below it still need the terms.
        for (String path : List.of("/api/images/" + UUID.randomUUID(), "/api/stock/" + UUID.randomUUID(), "/api/avatars/" + adas + "/reports"))
            assertError(ada.delete().uri(path).exchange(), 428, TermsFilter.TERMS_REQUIRED);
        ada.delete().uri("/api/avatars/{id}", adas).exchange().expectStatus().isNoContent();
        assertNull(me(ada).avatarId());
        assertEquals(AvatarPictureState.OWNER_REMOVED, only(admin, adas).picture());
        // CSRF still applies to the exempt route.
        guestWithoutCsrf().delete().uri("/api/avatars/{id}", beas).exchange().expectStatus().isForbidden();
    }
}
