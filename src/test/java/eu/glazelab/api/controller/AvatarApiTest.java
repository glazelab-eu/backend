package eu.glazelab.api.controller;

import eu.glazelab.api.dto.AvatarDtos.AuthorAvatarView;
import eu.glazelab.api.dto.AvatarDtos.AvatarReportPageView;
import eu.glazelab.api.dto.AvatarDtos.AvatarReportView;
import eu.glazelab.api.dto.BuddyDtos.BuddiesView;
import eu.glazelab.api.dto.BuddyDtos.BuddyLinkPreviewView;
import eu.glazelab.api.dto.BuddyDtos.BuddyLinkView;
import eu.glazelab.api.dto.ClubDtos.MemberView;
import eu.glazelab.api.model.AvatarPictureState;
import eu.glazelab.api.model.AvatarReportStatus;
import eu.glazelab.api.service.AvatarService;
import eu.glazelab.api.storage.ImageStorage;
import eu.glazelab.api.support.ApiTestBase;
import eu.glazelab.api.support.TestImages;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.test.web.servlet.client.RestTestClient.ResponseSpec;

import static org.junit.jupiter.api.Assertions.*;

/** Profile pictures (backend guide "Profile pictures"): who sees them, how they are stored, cached, replaced, reported and removed. */
class AvatarApiTest extends ApiTestBase {
    private static final String WEEK_IN_THE_BROWSER = "max-age=604800, private, immutable";
    private static final List<String> SIZES = List.of("", "/small");

    @Autowired ImageStorage storage;

    private static ResponseSpec get(RestTestClient client, UUID avatar, String size) {
        return client.get().uri("/api/avatars/" + avatar + size).exchange();
    }

    private static byte[] bytes(RestTestClient client, UUID avatar, String size) {
        return get(client, avatar, size).expectStatus().isOk().expectBody(byte[].class).returnResult().getResponseBody();
    }

    private static BufferedImage picture(RestTestClient client, UUID avatar, String size) {
        try {
            return ImageIO.read(new ByteArrayInputStream(bytes(client, avatar, size)));
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private String key(UUID avatar) {
        return jdbc.queryForObject("SELECT storage_key FROM user_avatar WHERE id = ?", String.class, avatar);
    }

    private int avatarsOf(UUID user) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM user_avatar WHERE user_id = ?", Integer.class, user);
    }

    private static ResponseSpec report(RestTestClient client, UUID avatar, String reason) {
        return client.post().uri("/api/avatars/{id}/reports", avatar)
                .body(reason == null ? Map.of() : Map.of("reason", reason)).exchange();
    }

    private static List<AvatarReportView> queue(RestTestClient admin, UUID avatar) {
        return body(admin.get().uri("/api/admin/avatar-reports").exchange().expectStatus().isOk(), AvatarReportPageView.class)
                .entries().stream().filter(entry -> entry.avatarId().equals(avatar)).toList();
    }

    private static List<AuthorAvatarView> authorAvatars(RestTestClient client) {
        return body(client.get().uri("/api/combinations/author-avatars").exchange().expectStatus().isOk(),
                new ParameterizedTypeReference<List<AuthorAvatarView>>() {});
    }

    private static List<MemberView> members(RestTestClient client, UUID club) {
        return body(client.get().uri("/api/clubs/{id}/members", club).exchange().expectStatus().isOk(),
                new ParameterizedTypeReference<List<MemberView>>() {});
    }

    private static void assertNeverStored(ResponseSpec refusal) {
        refusal.expectHeader().value("Cache-Control", value -> assertTrue(value.contains("no-store")
                        && !value.contains("max-age=604800") && !value.contains("immutable"), value))
                .expectHeader().doesNotExist("X-Accel-Expires").expectHeader().doesNotExist("Set-Cookie");
    }

    private static void assertRed(int rgb) {
        Color colour = new Color(rgb);
        assertTrue(colour.getRed() > 200 && colour.getGreen() < 60 && colour.getBlue() < 60, colour.toString());
    }

    private static void assertBlue(int rgb) {
        Color colour = new Color(rgb);
        assertTrue(colour.getBlue() > 200 && colour.getRed() < 60 && colour.getGreen() < 60, colour.toString());
    }

    /**
     * Every signed-in, verified account sees every current picture (both sizes); guests, ended sessions and accounts
     * that are not verified get 401, unknown and gone pictures 404, and every refusal is never stored.
     */
    @Test void everySignedInAccountSeesAPictureAndNobodyElse() {
        RestTestClient ada = user("Ada");
        RestTestClient bea = user("Bea");
        RestTestClient admin = siteAdmin();
        String cydEmail = email("Cyd");
        RestTestClient cyd = signUp(cydEmail);
        RestTestClient guest = guest();
        UUID picture = avatar(ada);
        for (String size : SIZES) {
            for (RestTestClient viewer : List.of(ada, bea, admin, cyd))
                get(viewer, picture, size).expectStatus().isOk().expectHeader().contentType(MediaType.IMAGE_JPEG)
                        .expectHeader().valueEquals("Cache-Control", WEEK_IN_THE_BROWSER)
                        .expectHeader().valueEquals("X-Accel-Expires", "0")
                        .expectHeader().doesNotExist("Set-Cookie");
            viewerHead(bea, picture, size);
            assertNeverStored(get(guest, picture, size).expectStatus().isUnauthorized());
            assertNeverStored(guest.head().uri("/api/avatars/" + picture + size).exchange().expectStatus().isUnauthorized());
            assertNeverStored(get(bea, UUID.randomUUID(), size).expectStatus().isNotFound());
            assertError(get(bea, UUID.randomUUID(), size), 404, AvatarService.AVATAR_NOT_FOUND);
            assertNeverStored(bea.get().uri("/api/avatars/not-an-id" + size).exchange().expectStatus().isBadRequest());
        }
        // An account that is no longer verified (or a pending one, which cannot sign in at all) is signed out.
        jdbc.update("UPDATE app_user SET email_verified = FALSE WHERE email = ?", cydEmail);
        for (String size : SIZES) assertNeverStored(get(cyd, picture, size).expectStatus().isUnauthorized());
        cyd.get().uri("/api/combinations/author-avatars").exchange().expectStatus().isUnauthorized();
        // A deleted account takes its picture along: both URLs answer 404 to everyone.
        String key = key(picture);
        ada.post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange().expectStatus().isNoContent();
        for (String size : SIZES) assertNeverStored(get(bea, picture, size).expectStatus().isNotFound());
        assertStored(storage, key, false);
    }

    private static void viewerHead(RestTestClient viewer, UUID picture, String size) {
        viewer.head().uri("/api/avatars/" + picture + size).exchange().expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", WEEK_IN_THE_BROWSER).expectHeader().valueEquals("X-Accel-Expires", "0")
                .expectHeader().doesNotExist("Set-Cookie");
    }

    /** No validators, so a conditional or range request gets the same full, browser-only answer (like photos). */
    @Test void conditionalAndRangeRequestsGetTheSameBrowserOnlyAnswer() {
        RestTestClient ada = user("Ada");
        RestTestClient bea = user("Bea");
        UUID picture = avatar(ada);
        for (String size : SIZES) {
            byte[] full = bytes(bea, picture, size);
            byte[] answered = bea.get().uri("/api/avatars/" + picture + size).header("Range", "bytes=0-1")
                    .header("If-None-Match", "*").header("If-Modified-Since", "Sat, 01 Jan 2050 00:00:00 GMT").exchange()
                    .expectStatus().isOk().expectHeader().valueEquals("Cache-Control", WEEK_IN_THE_BROWSER)
                    .expectHeader().doesNotExist("ETag").expectHeader().doesNotExist("Last-Modified")
                    .expectHeader().doesNotExist("Content-Range").expectBody(byte[].class).returnResult().getResponseBody();
            assertArrayEquals(full, answered);
        }
    }

    /** A new picture gets a new ID; the old ID, both its URLs, its objects and its reports are gone. */
    @Test void replacingGivesANewIdAndTheOldUrlsAnswer404() {
        RestTestClient ada = user("Ada");
        RestTestClient bea = user("Bea");
        RestTestClient admin = siteAdmin();
        UUID adaId = me(ada).id();
        assertNull(me(ada).avatarId());
        UUID first = avatar(ada);
        assertEquals(first, me(ada).avatarId());
        String firstKey = key(first);
        assertStored(storage, firstKey, true);
        report(bea, first, "Not a pot").expectStatus().isNoContent();
        assertEquals(1, queue(admin, first).size());

        UUID second = created(uploadAvatar(ada, TestImages.png(300, 300)));
        assertNotEquals(first, second);
        assertEquals(second, me(ada).avatarId());
        assertEquals(1, avatarsOf(adaId));
        for (String size : SIZES) {
            assertNeverStored(get(bea, first, size).expectStatus().isNotFound());
            get(bea, second, size).expectStatus().isOk();
        }
        assertStored(storage, key(second), true);
        // The report of the old picture stays open, its objects stay as evidence for the site admins (never at its old
        // URLs, see AvatarModerationApiTest); the new picture starts without reports.
        assertStored(storage, firstKey, true);
        var kept = queue(admin, first);
        assertEquals(1, kept.size());
        assertEquals(AvatarReportStatus.OPEN, kept.getFirst().status());
        assertEquals(AvatarPictureState.REPLACED, kept.getFirst().picture());
        assertTrue(kept.getFirst().hasPicture());
        assertTrue(queue(admin, second).isEmpty());
        // An unreported picture leaves nothing behind when it is replaced.
        String secondKey = key(second);
        avatar(ada);
        assertStored(storage, secondKey, false);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM avatar_evidence WHERE avatar_id = ?", Integer.class, second));
    }

    /** The owner removes their picture (views fall back to null); others who see it get 403, a gone one is 404. */
    @Test void theOwnerAndSiteAdminsRemoveAPictureAndNobodyElse() {
        RestTestClient ada = user("Ada");
        RestTestClient bea = user("Bea");
        RestTestClient admin = siteAdmin();
        UUID picture = avatar(ada);
        String key = key(picture);
        assertError(bea.delete().uri("/api/avatars/{id}", picture).exchange(), 403, AvatarService.REMOVE_RIGHTS_REQUIRED);
        guest().delete().uri("/api/avatars/{id}", picture).exchange().expectStatus().isUnauthorized();
        guestWithoutCsrf().delete().uri("/api/avatars/{id}", picture).exchange().expectStatus().isForbidden();
        get(bea, picture, "").expectStatus().isOk();
        ada.delete().uri("/api/avatars/{id}", picture).exchange().expectStatus().isNoContent();
        assertNull(me(ada).avatarId());
        assertStored(storage, key, false);
        for (String size : SIZES) get(bea, picture, size).expectStatus().isNotFound();
        assertError(ada.delete().uri("/api/avatars/{id}", picture).exchange(), 404, AvatarService.AVATAR_NOT_FOUND);
        assertError(admin.delete().uri("/api/avatars/{id}", UUID.randomUUID()).exchange(), 404, AvatarService.AVATAR_NOT_FOUND);

        // Moderation: a site admin removes another account's picture; the account falls back to no picture.
        UUID again = avatar(ada);
        String againKey = key(again);
        admin.delete().uri("/api/avatars/{id}", again).exchange().expectStatus().isNoContent();
        assertNull(me(ada).avatarId());
        assertStored(storage, againKey, false);
        get(bea, again, "").expectStatus().isNotFound();
    }

    /** Uploads take the photo limits and sniffing; nothing is stored for a refusal. */
    @Test void invalidUploadsAreRefusedAndStoreNothing() {
        RestTestClient ada = user("Ada");
        UUID adaId = me(ada).id();
        assertError(uploadAvatar(ada, "not an image".getBytes()), 400, ImageEncoder.UNSUPPORTED_IMAGE);
        // A PNG name and type do not make a picture: the content decides.
        assertError(uploadAvatar(ada, "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes()), 400, ImageEncoder.UNSUPPORTED_IMAGE);
        assertError(uploadAvatar(ada, new byte[0]), 400, "Choose an image under 12 MB");
        assertError(uploadAvatar(ada, TestImages.pngWithBrokenData(24, 24)), 400, ImageEncoder.UNSUPPORTED_IMAGE);
        // Decompression bombs: the header's size is refused before any pixel is decoded.
        assertError(uploadAvatar(ada, TestImages.pngWithBrokenData(10_000, 10_000)), 400, "Image dimensions are too large");
        assertError(uploadAvatar(ada, TestImages.pngWithBrokenData(5000, 5000)), 400, "Image dimensions are too large");
        // Above the encoder's 12 MB (and still within the multipart limit, whose larger uploads Tomcat refuses itself).
        byte[] huge = new byte[12_000_001];
        System.arraycopy(PNG, 0, huge, 0, PNG.length);
        assertError(uploadAvatar(ada, huge), 400, "Choose an image under 12 MB");
        // A write: CSRF and the current terms are required, and guests are refused.
        uploadAvatar(guest(), PNG).expectStatus().isUnauthorized();
        RestTestClient noCsrf = guestWithoutCsrf();
        String bea = email("Bea");
        signUpWithoutCsrf(noCsrf, bea);
        noCsrf.post().uri("/api/avatars").contentType(MediaType.MULTIPART_FORM_DATA).body(parts(Map.of(), PNG))
                .exchange().expectStatus().isForbidden();
        jdbc.update("UPDATE app_user SET accepted_terms_version = '2000-01-01' WHERE id = ?", adaId);
        uploadAvatar(ada, PNG).expectStatus().isEqualTo(428);
        assertEquals(0, avatarsOf(adaId));
        assertNull(me(ada).avatarId());
    }

    private void signUpWithoutCsrf(RestTestClient client, String email) {
        String token = csrf(client);
        String setup = setupToken(client.post().uri("/api/auth/register").header(CSRF_HEADER, token)
                .body(Map.of("termsAccepted", true, "termsVersion", eu.glazelab.api.service.AccountService.TERMS_VERSION,
                        "email", email, "displayName", "NoCsrf")).exchange());
        client.post().uri("/api/auth/verification/confirm").header(CSRF_HEADER, token)
                .body(activation(setup, PASSWORD, "NoCsrf")).exchange().expectStatus().isOk();
        login(client, token, email, PASSWORD).expectStatus().isOk();
    }

    /**
     * The server cuts any shape to its centre square, turns it upright, re-encodes without metadata and stores at
     * most 256 px plus a 64 px copy; a small picture is never enlarged.
     */
    @Test void picturesAreStoredAsUprightSquaresWithoutMetadataInTwoSizes() {
        RestTestClient ada = user("Ada");
        RestTestClient bea = user("Bea");
        UUID wide = created(uploadAvatar(ada, TestImages.photo(900, 600)));
        BufferedImage full = picture(bea, wide, ""), small = picture(bea, wide, "/small");
        assertEquals(256, full.getWidth());
        assertEquals(256, full.getHeight());
        assertEquals(64, small.getWidth());
        assertEquals(64, small.getHeight());

        // Left red, right blue; EXIF orientation 6 turns it a quarter clockwise: red on top, blue at the bottom.
        byte[] turned = TestImages.withExifOrientation(TestImages.halves(400, 200, Color.RED, Color.BLUE), 6, false);
        assertTrue(TestImages.hasMarker(turned, 0xE1));
        UUID upright = created(uploadAvatar(ada, turned));
        byte[] stored = bytes(bea, upright, "");
        assertFalse(TestImages.hasMarker(stored, 0xE1), "EXIF is stripped");
        assertFalse(TestImages.hasMarker(bytes(bea, upright, "/small"), 0xE1), "EXIF is stripped from the small copy");
        BufferedImage square = picture(bea, upright, "");
        assertEquals(200, square.getWidth());
        assertEquals(200, square.getHeight());
        assertRed(square.getRGB(100, 20));
        assertBlue(square.getRGB(100, 180));

        // Without orientation the centre square keeps both halves side by side.
        UUID plain = created(uploadAvatar(ada, TestImages.halves(400, 200, Color.RED, Color.BLUE)));
        BufferedImage halves = picture(bea, plain, "");
        assertRed(halves.getRGB(20, 100));
        assertBlue(halves.getRGB(180, 100));

        // Any other shape is cut to its centre square, never squashed: red | blue | red thirds leave only blue,
        // across and upright alike.
        for (boolean across : List.of(true, false)) {
            BufferedImage thirds = new BufferedImage(across ? 600 : 200, across ? 200 : 600, BufferedImage.TYPE_INT_RGB);
            var graphics = thirds.createGraphics();
            graphics.setColor(Color.RED);
            graphics.fillRect(0, 0, thirds.getWidth(), thirds.getHeight());
            graphics.setColor(Color.BLUE);
            graphics.fillRect(across ? 200 : 0, across ? 0 : 200, 200, 200);
            graphics.dispose();
            BufferedImage centre = picture(bea, created(uploadAvatar(ada, TestImages.jpeg(thirds, 0.95f))), "");
            assertEquals(200, centre.getWidth());
            assertEquals(200, centre.getHeight());
            for (int[] at : new int[][] {{8, 8}, {8, 191}, {191, 8}, {191, 191}, {100, 100}}) assertBlue(centre.getRGB(at[0], at[1]));
        }

        // Small pictures keep their size (cut square), and the small copy is the same picture.
        UUID tiny = created(uploadAvatar(ada, TestImages.png(40, 30)));
        assertEquals(30, picture(bea, tiny, "").getWidth());
        assertEquals(30, picture(bea, tiny, "").getHeight());
        assertArrayEquals(bytes(bea, tiny, ""), bytes(bea, tiny, "/small"));
        // A transparent picture becomes white, never black.
        Color corner = new Color(picture(bea, tiny, "").getRGB(0, 0));
        assertTrue(corner.getRed() > 240 && corner.getGreen() > 240 && corner.getBlue() > 240, corner.toString());

        // An animated picture keeps only its first frame.
        UUID animated = created(uploadAvatar(ada, TestImages.animatedGif(120, 80, Color.RED, Color.BLUE)));
        BufferedImage frame = picture(bea, animated, "");
        assertEquals(80, frame.getWidth());
        assertRed(frame.getRGB(40, 40));
        assertEquals(1, avatarsOf(me(ada).id()));
    }

    /** Reports: once per reporter and picture, never the owner's own, with an optional reason of at most 500 characters. */
    @Test void aPictureIsReportedOncePerAccountAndSiteAdminsWorkThroughTheQueue() {
        RestTestClient ada = user("Ada");
        RestTestClient bea = user("Bea");
        RestTestClient cyd = user("Cyd");
        RestTestClient admin = siteAdmin();
        UUID adaId = me(ada).id();
        UUID picture = avatar(ada);
        assertError(report(ada, picture, "Mine"), 400, AvatarService.OWN_AVATAR_REPORT);
        report(guest(), picture, null).expectStatus().isUnauthorized();
        report(bea, picture, "x".repeat(501)).expectStatus().isBadRequest();
        assertError(report(bea, UUID.randomUUID(), null), 404, AvatarService.AVATAR_NOT_FOUND);
        report(bea, picture, "  Shows someone else  ").expectStatus().isNoContent();
        report(bea, picture, "Changed my mind").expectStatus().isNoContent();
        report(cyd, picture, "   ").expectStatus().isNoContent();

        guest().get().uri("/api/admin/avatar-reports").exchange().expectStatus().isUnauthorized();
        assertError(bea.get().uri("/api/admin/avatar-reports").exchange(), 403, "Site admin role required");
        admin.get().uri("/api/admin/avatar-reports?page=-1").exchange().expectStatus().isBadRequest();
        var entries = queue(admin, picture);
        assertEquals(2, entries.size());
        var cyds = entries.getFirst();
        var beas = entries.get(1);
        assertEquals(me(cyd).displayName(), cyds.reporter());
        assertNull(cyds.reason());
        assertEquals(me(bea).displayName(), beas.reporter());
        assertEquals("Shows someone else", beas.reason());
        assertEquals(adaId, beas.ownerId());
        assertEquals(me(ada).displayName(), beas.owner());

        // Dismissal closes every open report of the picture, keeps the reports and leaves the picture.
        assertError(bea.post().uri("/api/admin/avatar-reports/{id}/dismiss", cyds.id()).exchange(), 403, "Site admin role required");
        assertError(admin.post().uri("/api/admin/avatar-reports/{id}/dismiss", UUID.randomUUID()).exchange(), 404,
                ImageReportController.REPORT_NOT_FOUND);
        admin.post().uri("/api/admin/avatar-reports/{id}/dismiss", cyds.id()).exchange().expectStatus().isNoContent();
        admin.post().uri("/api/admin/avatar-reports/{id}/dismiss", beas.id()).exchange().expectStatus().isNoContent();
        var dismissed = queue(admin, picture);
        assertEquals(2, dismissed.size());
        for (var entry : dismissed) {
            assertEquals(AvatarReportStatus.DISMISSED, entry.status());
            assertNotNull(entry.closedAt());
            assertEquals(me(admin).displayName(), entry.closedBy());
            assertEquals(AvatarPictureState.CURRENT, entry.picture());
        }
        get(bea, picture, "").expectStatus().isOk();
        // A dismissed report is not acted on later, and a reporter's repeat stays one report.
        assertError(admin.post().uri("/api/admin/avatar-reports/{id}/remove", beas.id()).exchange(), 409,
                eu.glazelab.api.exception.ApiErrorHandler.CHANGED_MEANWHILE);
        report(bea, picture, "Again").expectStatus().isNoContent();
        assertEquals(2, queue(admin, picture).size());

        // A deleted reporter's report stays without a name.
        bea.post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange().expectStatus().isNoContent();
        assertNull(queue(admin, picture).stream().filter(entry -> entry.id().equals(beas.id())).findFirst().orElseThrow().reporter());

        // Removal by a site admin keeps the closed reports and shows how the picture went.
        admin.delete().uri("/api/avatars/{id}", picture).exchange().expectStatus().isNoContent();
        var removed = queue(admin, picture);
        assertEquals(2, removed.size());
        assertTrue(removed.stream().allMatch(entry -> entry.picture() == AvatarPictureState.ADMIN_REMOVED
                && entry.status() == AvatarReportStatus.DISMISSED), removed.toString());
        assertNull(me(ada).avatarId());
    }

    /** Admin queue pages: newest first, 50 per page, one slice query (like the photo queue). */
    @Test void theReportQueueIsPagedNewestFirst() {
        RestTestClient ada = user("Ada");
        RestTestClient admin = siteAdmin();
        UUID picture = avatar(ada);
        var future = java.time.OffsetDateTime.parse("2099-01-01T00:00:00Z");
        for (int i = 0; i < 51; i++)
            jdbc.update("INSERT INTO avatar_report (id, avatar_id, reason, created_at, status) VALUES (?, ?, ?, ?, 'OPEN')",
                    UUID.randomUUID(), picture, "Page " + i, future.plusSeconds(i));
        try {
            var first = body(admin.get().uri("/api/admin/avatar-reports").exchange().expectStatus().isOk(), AvatarReportPageView.class);
            assertEquals(50, first.entries().size());
            assertTrue(first.hasMore());
            assertEquals("Page 50", first.entries().getFirst().reason());
            var second = body(admin.get().uri("/api/admin/avatar-reports?page=1").exchange().expectStatus().isOk(), AvatarReportPageView.class);
            assertEquals("Page 0", second.entries().getFirst().reason());
        } finally {
            jdbc.update("DELETE FROM avatar_report WHERE avatar_id = ?", picture);
        }
    }

    /** Account deletion removes an unreported picture's row and both objects; a pictureless account deletes as before. */
    @Test void accountDeletionRemovesAnUnreportedPictureAndItsObjects() {
        RestTestClient ada = user("Ada");
        RestTestClient bea = user("Bea");
        UUID adaId = me(ada).id();
        UUID picture = avatar(ada);
        String key = key(picture);
        ada.post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange().expectStatus().isNoContent();
        assertEquals(0, avatarsOf(adaId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM avatar_evidence WHERE avatar_id = ?", Integer.class, picture));
        assertStored(storage, key, false);
        get(bea, picture, "").expectStatus().isNotFound();
        bea.post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange().expectStatus().isNoContent();
    }

    /**
     * Who carries a picture: the own account (UserView), club members, buddies and requests, the buddy link preview and
     * the authors' listing; a guest sees none of them, and the synced combination view never carries one.
     */
    @Test void viewsCarryThePictureForSignedInViewersOnly() {
        RestTestClient ada = user("Ada");
        RestTestClient bea = user("Bea");
        RestTestClient cyd = user("Cyd");
        RestTestClient dan = user("Dan");
        UUID adaId = me(ada).id(), beaId = me(bea).id(), cydId = me(cyd).id(), danId = me(dan).id();
        UUID adas = avatar(ada), beas = avatar(bea), cyds = avatar(cyd);

        UUID club = createClub(ada, unique("Pictures club"));
        joinClub(ada, club, bea);
        joinClub(ada, club, dan);
        var memberAvatars = members(bea, club).stream().collect(java.util.stream.Collectors.toMap(MemberView::id,
                member -> java.util.Optional.ofNullable(member.avatarId())));
        assertEquals(Map.of(adaId, java.util.Optional.of(adas), beaId, java.util.Optional.of(beas), danId, java.util.Optional.empty()),
                memberAvatars);

        // Buddies: Ada and Bea are buddies; Cyd asked Ada; Ada asked Dan.
        String token = body(bea.post().uri("/api/buddies/link").exchange().expectStatus().isOk(), BuddyLinkView.class).token();
        var preview = body(ada.post().uri("/api/buddies/link/preview").body(Map.of("token", token)).exchange().expectStatus().isOk(),
                BuddyLinkPreviewView.class);
        assertEquals(beas, preview.avatarId());
        ada.post().uri("/api/buddies/link/request").body(Map.of("token", token)).exchange().expectStatus().isOk();
        bea.post().uri("/api/buddies/{id}/accept", adaId).exchange().expectStatus().isNoContent();
        String adasLink = body(ada.post().uri("/api/buddies/link").exchange().expectStatus().isOk(), BuddyLinkView.class).token();
        cyd.post().uri("/api/buddies/link/request").body(Map.of("token", adasLink)).exchange().expectStatus().isOk();
        ada.post().uri("/api/buddies/requests").body(Map.of("clubId", club, "userId", danId)).exchange().expectStatus().isOk();
        var buddies = body(ada.get().uri("/api/buddies").exchange().expectStatus().isOk(), BuddiesView.class);
        assertEquals(beas, buddies.buddies().getFirst().avatarId());
        assertEquals(cyds, buddies.incoming().getFirst().avatarId());
        assertNull(buddies.outgoing().getFirst().avatarId());

        // Authors: only published authorship names an account; a draft-only author stays unlisted.
        UUID glaze = addGlaze(ada, unique("Pictures"), "Blue", "PIC-1");
        addCombination(ada, unique("Published"), glaze);
        dan.post().uri("/api/combinations").body(Map.of("title", unique("Draft"), "draft", true,
                "steps", List.of(Map.of("glazeId", glaze, "coats", 1)))).exchange().expectStatus().isOk();
        UUID dans = avatar(dan);
        var authors = authorAvatars(cyd);
        assertTrue(authors.contains(new AuthorAvatarView(adaId, adas)), authors.toString());
        assertTrue(authors.stream().noneMatch(author -> author.userId().equals(danId) || author.avatarId().equals(dans)));
        assertTrue(authors.stream().noneMatch(author -> author.userId().equals(beaId)), "Bea authored nothing");
        guest().get().uri("/api/combinations/author-avatars").exchange().expectStatus().isUnauthorized();

        // A changed picture shows at once (full fetches), the removed one falls back to null.
        UUID newer = avatar(ada);
        assertTrue(authorAvatars(cyd).contains(new AuthorAvatarView(adaId, newer)));
        ada.delete().uri("/api/avatars/{id}", newer).exchange().expectStatus().isNoContent();
        assertTrue(authorAvatars(cyd).stream().noneMatch(author -> author.userId().equals(adaId)));
        assertNull(members(bea, club).stream().filter(member -> member.id().equals(adaId)).findFirst().orElseThrow().avatarId());
        assertNull(body(bea.get().uri("/api/buddies").exchange().expectStatus().isOk(), BuddiesView.class).buddies().getFirst().avatarId());

        // The synced views carry no picture for anyone (no format change, no re-sent combinations).
        for (RestTestClient viewer : List.of(guest(), ada, cyd)) {
            String listing = viewer.get().uri("/api/combinations").exchange().expectStatus().isOk().expectBody(String.class)
                    .returnResult().getResponseBody();
            assertFalse(listing.toLowerCase().contains("avatar"), listing);
            String sync = viewer.get().uri("/api/sync").exchange().expectStatus().isOk().expectBody(String.class)
                    .returnResult().getResponseBody();
            assertFalse(sync.toLowerCase().contains("avatar"), sync);
        }
    }
}
