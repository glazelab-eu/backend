package eu.glazelab.api.controller;

import eu.glazelab.api.model.ImageKind;
import eu.glazelab.api.model.AccountToken;
import eu.glazelab.api.repository.AccountTokenRepository;
import eu.glazelab.api.repository.AppUserRepository;
import eu.glazelab.api.repository.ImageDeletionRepository;
import eu.glazelab.api.repository.ImageRecordRepository;
import eu.glazelab.api.security.SecureTokens;
import eu.glazelab.api.storage.ImageStorage;
import eu.glazelab.api.support.ApiTestBase;
import eu.glazelab.api.support.TestImages;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.junit.jupiter.api.Assertions.*;

class AccountDeletionApiTest extends ApiTestBase {
    @Autowired AppUserRepository users;
    @Autowired AccountTokenRepository tokens;
    @Autowired ImageRecordRepository images;
    @Autowired ImageDeletionRepository deletions;
    @Autowired ImageStorage storage;
    @Autowired eu.glazelab.api.security.CurrentUser current;
    @Autowired org.springframework.transaction.support.TransactionTemplate transactions;

    @Test void requiresPasswordAndCsrfAndNeverDeletesOnAGet() {
        String email = email("Protected");
        var owner = signUp(email);
        var id = me(owner).id();
        guest().post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange().expectStatus().isUnauthorized();
        owner.get().uri("/api/auth/delete").exchange().expectStatus().isEqualTo(405);
        owner.post().uri("/api/auth/delete").body(Map.of()).exchange().expectStatus().isBadRequest();
        owner.post().uri("/api/auth/delete").body(Map.of("password", "wrong")).exchange().expectStatus().isBadRequest();
        var noCsrf = guestWithoutCsrf();
        login(noCsrf, email, PASSWORD).expectStatus().isForbidden();
        var form = new org.springframework.util.LinkedMultiValueMap<String, String>();
        form.add("email", email); form.add("password", PASSWORD);
        noCsrf.post().uri("/api/auth/login").header(CSRF_HEADER, csrf(noCsrf))
                .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED).body(form).exchange().expectStatus().isOk();
        noCsrf.post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange().expectStatus().isForbidden();
        noCsrf.post().uri("/api/auth/delete").header(CSRF_HEADER, "wrong").body(Map.of("password", PASSWORD)).exchange().expectStatus().isForbidden();
        assertTrue(users.existsById(id));
    }

    @Test void lastClubAdminConflictRollsBackEveryChange() throws Exception {
        var owner = user("LastAdmin");
        var userId = me(owner).id();
        var club = createClub(owner, unique("Kept club"));
        joinClub(owner, club, user("KeptMember"));
        var glaze = addGlaze(owner, unique("Kept"), "Blue", "1");
        var image = created(uploadGlazeImage(owner, glaze, Map.of("visibility", "PRIVATE", "kind", "TEST_TILE"), TestImages.png(24, 24)));
        String key = images.findById(image).orElseThrow().getStorageKey();
        owner.post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange().expectStatus().isEqualTo(409)
                .expectHeader().doesNotExist("Clear-Site-Data");
        assertTrue(users.existsById(userId));
        assertTrue(images.existsById(image));
        assertTrue(storage.get(key).length > 0);
        assertEquals("ADMIN", jdbc.queryForObject("SELECT role FROM membership WHERE club_id = ? AND user_id = ?", String.class, club, userId));
        assertEquals(userId, jdbc.queryForObject("SELECT created_by FROM glaze WHERE id = ?", UUID.class, glaze));
        assertEquals(userId, me(owner).id());
    }

    @Test void removesAccountPrivateDataAndTokensWhileAnonymisingSharedContentAndLogs() throws Exception {
        String email = email("DeleteMe");
        var owner = signUp(email);
        var userId = me(owner).id();
        var anotherSession = guest();
        var oldPrincipal = new eu.glazelab.api.security.AccountPrincipal(users.findById(userId).orElseThrow());
        var inFlightAuth = org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(oldPrincipal, null, oldPrincipal.getAuthorities());
        login(anotherSession, email, PASSWORD).expectStatus().isOk();
        var peer = user("Peer");
        var peerId = me(peer).id();
        var club = createClub(owner, unique("Retained"));
        joinClub(owner, club, peer);
        owner.put().uri("/api/clubs/{id}/members/{user}", club, peerId).body(Map.of("role", "ADMIN")).exchange().expectStatus().isOk();
        var glaze = addGlaze(owner, unique("Retained"), "Blue", "1");
        var combination = addCombination(owner, unique("Retained layers"), glaze);
        owner.post().uri("/api/stock").body(Map.of("glazeId", glaze, "quantityG", 500, "jarSizeG", 500, "ownerType", "personal")).exchange().expectStatus().isOk();
        owner.post().uri("/api/stock").body(Map.of("glazeId", glaze, "quantityG", 200, "jarSizeG", 250, "ownerType", "club", "clubId", club)).exchange().expectStatus().isOk();
        owner.put().uri("/api/favorites/glazes/{id}", glaze).exchange().expectStatus().isOk();
        peer.put().uri("/api/favorites/glazes/{id}", glaze).exchange().expectStatus().isOk();
        var publicPhoto = created(uploadGlazeImage(owner, glaze, Map.of("visibility", "PUBLIC", "kind", "PACKAGING"), TestImages.png(24, 24)));
        var clubPhoto = created(upload(owner, combination, Map.of("visibility", "CLUB", "clubId", club.toString(), "kind", "FINISHED"), TestImages.png(24, 24)));
        var privatePhoto = created(upload(owner, combination, Map.of("visibility", "PRIVATE", "kind", "TEST_TILE"), TestImages.png(24, 24)));
        var privateGlazePhoto = created(uploadGlazeImage(owner, glaze, Map.of("visibility", "PRIVATE", "kind", "PACKAGING"), TestImages.png(24, 24)));
        var peerPhoto = created(uploadGlazeImage(peer, glaze, Map.of("visibility", "PRIVATE", "kind", "TEST_TILE"), TestImages.png(24, 24)));
        var logged = created(uploadGlazeImage(owner, glaze, Map.of("visibility", "PUBLIC", "kind", "FINISHED"), TestImages.png(24, 24)));
        owner.delete().uri("/api/images/{id}", logged).exchange().expectStatus().isNoContent();
        var peerLogged = created(uploadGlazeImage(peer, glaze, Map.of("visibility", "PUBLIC", "kind", "FINISHED"), TestImages.png(24, 24)));
        // Only uploaders, club admins and site admins delete: the owner moderates as a site admin here.
        jdbc.update("UPDATE app_user SET site_admin = TRUE WHERE id = ?", userId);
        owner.delete().uri("/api/images/{id}", peerLogged).exchange().expectStatus().isNoContent();
        var keys = new java.util.HashMap<UUID, String>();
        for (UUID photo : List.of(publicPhoto, clubPhoto, privatePhoto, privateGlazePhoto, peerPhoto))
            keys.put(photo, images.findById(photo).orElseThrow().getStorageKey());
        String rawToken = SecureTokens.generate();
        var token = new AccountToken(users.findById(userId).orElseThrow(), AccountToken.Purpose.RESET_PASSWORD);
        token.renew(SecureTokens.hash(rawToken), LocalDateTime.now(java.time.ZoneOffset.UTC));
        tokens.saveAndFlush(token);
        // A person can delete rather than accept newer terms.
        jdbc.update("UPDATE app_user SET accepted_terms_version = NULL, terms_accepted_at = NULL WHERE id = ?", userId);

        owner.post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange().expectStatus().isNoContent();

        assertFalse(users.existsById(userId));
        for (String table : List.of("stock", "favorite", "membership", "account_token"))
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id = ?", Integer.class, userId));
        assertNull(jdbc.queryForObject("SELECT created_by FROM glaze WHERE id = ?", UUID.class, glaze));
        assertNull(jdbc.queryForObject("SELECT created_by FROM combination WHERE id = ?", UUID.class, combination));
        assertNull(jdbc.queryForObject("SELECT owner_id FROM club WHERE id = ?", UUID.class, club));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM membership WHERE club_id = ? AND role = 'ADMIN'", Integer.class, club));
        assertEquals(200, jdbc.queryForObject("SELECT quantity_g FROM stock WHERE club_id = ? AND glaze_id = ?", Integer.class, club, glaze));
        assertEquals(250, jdbc.queryForObject("SELECT jar_size_g FROM stock WHERE club_id = ? AND glaze_id = ?", Integer.class, club, glaze));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM favorite WHERE user_id = ?", Integer.class, peerId));
        for (UUID photo : List.of(privatePhoto, privateGlazePhoto)) {
            assertFalse(images.existsById(photo));
            assertStored(storage, keys.get(photo), false);
        }
        for (UUID photo : List.of(publicPhoto, clubPhoto)) {
            assertNull(images.findById(photo).orElseThrow().getUploadedBy());
            assertStored(storage, keys.get(photo), true);
        }
        assertEquals(peerId, images.findById(peerPhoto).orElseThrow().getUploadedBy().getId());
        assertStored(storage, keys.get(peerPhoto), true);
        for (UUID photo : List.of(logged, privatePhoto, privateGlazePhoto)) {
            var row = deletions.findAll().stream().filter(d -> d.getImageId().equals(photo)).findFirst().orElseThrow();
            assertNull(row.getUploaderId()); assertNull(row.getDeletedBy());
        }
        var peerLog = deletions.findAll().stream().filter(d -> d.getImageId().equals(peerLogged)).findFirst().orElseThrow();
        assertEquals(peerId, peerLog.getUploaderId());
        assertNull(peerLog.getDeletedBy());
        var anonymous = combination(peer, combination).orElseThrow();
        assertNull(anonymous.author());
        assertNull(anonymous.authorId());
        assertFalse(anonymous.own());
        // Shared pictures and the glaze stay, without naming the deleted account.
        assertEquals(List.of(clubPhoto), anonymous.images().stream().map(i -> i.id()).toList());
        assertNull(anonymous.images().getFirst().uploader());
        var retained = glazes(peer).stream().filter(g -> g.id().equals(glaze)).findFirst().orElseThrow();
        assertNull(retained.addedBy());
        assertNotNull(retained.createdAt());
        assertNull(retained.images().stream().filter(i -> i.id().equals(publicPhoto)).findFirst().orElseThrow().uploader());
        assertEquals(ImageKind.PACKAGING, retained.images().stream().filter(i -> i.id().equals(publicPhoto)).findFirst().orElseThrow().kind());
        peer.patch().uri("/api/images/{id}", publicPhoto).body(Map.of("kind", "TEST_TILE")).exchange().expectStatus().isForbidden();
        assertEquals(me(peer).displayName(),
                retained.images().stream().filter(i -> i.id().equals(peerPhoto)).findFirst().orElseThrow().uploader());
        guest().get().uri("/api/images/{id}", publicPhoto).exchange().expectStatus().isOk();
        guest().get().uri("/api/images/{id}", clubPhoto).exchange().expectStatus().isNotFound();
        peer.get().uri("/api/images/{id}", clubPhoto).exchange().expectStatus().isOk();
        peer.get().uri("/api/clubs").exchange().expectStatus().isOk();
        owner.get().uri("/api/auth/me").exchange().expectStatus().isUnauthorized();
        anotherSession.get().uri("/api/auth/me").exchange().expectStatus().isUnauthorized();
        login(guest(), email, PASSWORD).expectStatus().isUnauthorized();
        guest().post().uri("/api/auth/password/reset").body(Map.of("token", rawToken, "password", PASSWORD)).exchange().expectStatus().isBadRequest();
        // The old email is free, but an old session must never acquire the new account.
        var replacement = signUp(email);
        assertNotEquals(userId, me(replacement).id());
        var missingIdentity = assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> current.user(inFlightAuth));
        assertEquals(org.springframework.http.HttpStatus.UNAUTHORIZED, missingIdentity.getStatusCode());
        anotherSession.get().uri("/api/stock").exchange().expectStatus().isUnauthorized();
        // Anonymous photos still participate safely in deletion logging.
        assertError(peer.delete().uri("/api/images/{id}", publicPhoto).exchange(), 403, ImageController.DELETE_RIGHTS_REQUIRED);
        siteAdmin().delete().uri("/api/images/{id}", publicPhoto).exchange().expectStatus().isNoContent();
    }

    @Test void anonymisationNeverDetachesAPrivatePhotoFromItsUploader() throws Exception {
        // Stands in for a PRIVATE upload committed after the deletion's locked read: it must keep the
        // uploader, so the account row cannot be deleted while the photo remains.
        var owner = user("LateUpload");
        var userId = me(owner).id();
        var glaze = addGlaze(owner, unique("Late"), "Blue", "1");
        var shared = created(uploadGlazeImage(owner, glaze, Map.of("visibility", "PUBLIC", "kind", "TEST_TILE"), TestImages.png(24, 24)));
        var late = created(uploadGlazeImage(owner, glaze, Map.of("visibility", "PRIVATE", "kind", "TEST_TILE"), TestImages.png(24, 24)));
        transactions.executeWithoutResult(status -> images.anonymiseSharedUploads(userId));
        assertNull(jdbc.queryForObject("SELECT uploaded_by FROM image WHERE id = ?", UUID.class, shared));
        assertEquals(userId, jdbc.queryForObject("SELECT uploaded_by FROM image WHERE id = ?", UUID.class, late));
        jdbc.update("UPDATE glaze SET created_by = NULL WHERE id = ?", glaze);
        jdbc.update("DELETE FROM account_token WHERE user_id = ?", userId);
        var blocked = assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> transactions.executeWithoutResult(status -> { users.deleteById(userId); users.flush(); }));
        assertTrue(blocked.getMessage().toLowerCase(java.util.Locale.ROOT).contains("fk_image_uploaded_by"), blocked.getMessage());
        assertTrue(users.existsById(userId));
    }

    @Test void simultaneousLastMembersCanDeleteTheirAccountsAndTheirNowEmptyClub() throws Exception {
        var first = user("FirstAdmin");
        var second = user("SecondAdmin");
        var club = createClub(first, unique("Concurrent"));
        joinClub(first, club, second);
        first.put().uri("/api/clubs/{id}/members/{user}", club, me(second).id()).body(Map.of("role", "ADMIN")).exchange().expectStatus().isOk();
        var ready = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var results = List.of(first, second).stream().map(client -> executor.submit(() -> {
                ready.await();
                return client.post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange().returnResult().getStatus().value();
            })).toList();
            ready.countDown();
            var statuses = new java.util.ArrayList<Integer>();
            for (var result : results) statuses.add(result.get(15, java.util.concurrent.TimeUnit.SECONDS));
            statuses.sort(Integer::compareTo);
            assertEquals(List.of(204, 204), statuses);
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM club WHERE id = ?", Integer.class, club));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM membership WHERE club_id = ?", Integer.class, club));
    }

    @Test void soleMemberClubsRemoveStockInvitesAndAllClubPhotosButKeepSharedTargetsAndOtherClubs() throws Exception {
        var owner = user("SoleOwner");
        var past = user("PastUploader");
        var other = user("OtherAdmin");
        var sole = createClub(owner, unique("Sole studio"));
        joinClub(owner, sole, past);
        owner.put().uri("/api/clubs/{id}", sole).body(Map.of("name", "Sole studio", "houseRules", "Rules", "invitesEnabled", true)).exchange().expectStatus().isOk();
        var invite = body(owner.post().uri("/api/clubs/{id}/invite", sole).exchange().expectStatus().isOk(), eu.glazelab.api.dto.ClubDtos.InviteView.class).token();
        var retained = createClub(other, unique("Other studio"));
        joinClub(other, retained, owner);
        var glaze = addGlaze(owner, unique("Sole"), "Kept glaze", "1");
        var combination = addCombination(owner, unique("Kept layers"), glaze);
        owner.post().uri("/api/stock").body(Map.of("glazeId", glaze, "quantityG", 300, "ownerType", "club", "clubId", sole)).exchange().expectStatus().isOk();
        owner.post().uri("/api/stock").body(Map.of("glazeId", glaze, "quantityG", 500, "ownerType", "club", "clubId", retained)).exchange().expectStatus().isOk();
        var ownPhoto = created(uploadGlazeImage(owner, glaze, Map.of("visibility", "CLUB", "clubId", sole.toString(), "kind", "TEST_TILE"), TestImages.png(24, 24)));
        var anonymousPhoto = created(upload(past, combination, Map.of("visibility", "CLUB", "clubId", sole.toString(), "kind", "FINISHED"), TestImages.png(24, 24)));
        past.post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange().expectStatus().isNoContent();
        assertNull(images.findById(anonymousPhoto).orElseThrow().getUploadedBy());
        var publicPhoto = created(uploadGlazeImage(owner, glaze, Map.of("visibility", "PUBLIC", "kind", "TEST_TILE"), TestImages.png(24, 24)));
        var retainedPhoto = created(upload(owner, combination, Map.of("visibility", "CLUB", "clubId", retained.toString(), "kind", "FINISHED"), TestImages.png(24, 24)));
        var keys = Map.of(ownPhoto, images.findById(ownPhoto).orElseThrow().getStorageKey(),
                anonymousPhoto, images.findById(anonymousPhoto).orElseThrow().getStorageKey());

        owner.post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange().expectStatus().isNoContent();

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM club WHERE id = ?", Integer.class, sole));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM membership WHERE club_id = ?", Integer.class, sole));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM stock WHERE club_id = ?", Integer.class, sole));
        other.post().uri("/api/clubs/invitations/preview").body(Map.of("token", invite)).exchange().expectStatus().isNotFound();
        for (var entry : keys.entrySet()) {
            assertFalse(images.existsById(entry.getKey()));
            assertStored(storage, entry.getValue(), false);
            var row = deletions.findAll().stream().filter(d -> d.getImageId().equals(entry.getKey())).findFirst().orElseThrow();
            assertEquals("CLUB", row.getVisibility());
            assertNull(row.getUploaderId()); assertNull(row.getDeletedBy());
        }
        assertTrue(glazes(guest()).stream().anyMatch(row -> row.id().equals(glaze)));
        assertTrue(combination(guest(), combination).isPresent());
        guest().get().uri("/api/images/{id}", publicPhoto).exchange().expectStatus().isOk();
        assertTrue(images.existsById(retainedPhoto));
        other.get().uri("/api/images/{id}", retainedPhoto).exchange().expectStatus().isOk();
        assertEquals(500, jdbc.queryForObject("SELECT quantity_g FROM stock WHERE club_id = ? AND glaze_id = ?", Integer.class, retained, glaze));
    }

    @Test void plainMemberOnlyLosesTheMembership() {
        var admin = user("StayingAdmin");
        var member = user("LeavingMember");
        var memberId = me(member).id();
        var club = createClub(admin, unique("Staying club"));
        joinClub(admin, club, member);
        var glaze = addGlaze(admin, unique("Staying"), "Blue", "1");
        member.post().uri("/api/stock").body(Map.of("glazeId", glaze, "quantityG", 100, "ownerType", "club", "clubId", club)).exchange().expectStatus().isOk();

        member.post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange().expectStatus().isNoContent();

        assertFalse(users.existsById(memberId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM club WHERE id = ?", Integer.class, club));
        assertEquals(List.of("ADMIN"), jdbc.queryForList("SELECT role FROM membership WHERE club_id = ?", String.class, club));
        assertEquals(100, jdbc.queryForObject("SELECT quantity_g FROM stock WHERE club_id = ? AND glaze_id = ?", Integer.class, club, glaze));
    }

    @Test void legacyOwnedClubsWithoutMembersAreRemovedAndOthersOnlyLoseTheOwner() throws Exception {
        var owner = user("LegacyOwner");
        var userId = me(owner).id();
        var admin = user("LegacyAdmin");
        var memberless = createClub(owner, unique("Memberless"));
        var shared = createClub(owner, unique("Handed over"));
        joinClub(owner, shared, admin);
        owner.put().uri("/api/clubs/{id}/members/{user}", shared, me(admin).id()).body(Map.of("role", "ADMIN")).exchange().expectStatus().isOk();
        var glaze = addGlaze(owner, unique("Legacy"), "Blue", "1");
        owner.post().uri("/api/stock").body(Map.of("glazeId", glaze, "quantityG", 100, "ownerType", "club", "clubId", memberless)).exchange().expectStatus().isOk();
        var photo = created(uploadGlazeImage(owner, glaze, Map.of("visibility", "CLUB", "clubId", memberless.toString(), "kind", "TEST_TILE"), TestImages.png(24, 24)));
        String key = images.findById(photo).orElseThrow().getStorageKey();
        // Before club roles, an owner was not necessarily a member.
        jdbc.update("DELETE FROM membership WHERE user_id = ?", userId);

        owner.post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange().expectStatus().isNoContent();

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM club WHERE id = ?", Integer.class, memberless));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM stock WHERE club_id = ?", Integer.class, memberless));
        assertFalse(images.existsById(photo));
        assertStored(storage, key, false);
        assertTrue(deletions.findAll().stream().anyMatch(d -> d.getImageId().equals(photo) && d.getDeletedBy() == null));
        assertNull(jdbc.queryForObject("SELECT owner_id FROM club WHERE id = ?", UUID.class, shared));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM membership WHERE club_id = ? AND role = 'ADMIN'", Integer.class, shared));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void concurrentGlazeRemovalKeepsAnonymousAuditAndConsistentLockOrder(boolean authoredByUploader) throws Exception {
        String email = email("AuditAdmin");
        var admin = signUp(email);
        var adminId = me(admin).id();
        jdbc.update("UPDATE app_user SET site_admin = TRUE WHERE id = ?", adminId);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 3; i++) {
                var uploader = user("AuditUploader");
                var uploaderId = me(uploader).id();
                // Own glazes exercise glaze -> images lock ordering; others exercise audit anonymisation.
                var glaze = addGlaze(authoredByUploader ? uploader : admin, unique("Concurrent audit"), "Blue", "1");
                var image = created(uploadGlazeImage(uploader, glaze, Map.of("visibility", "PUBLIC", "kind", "TEST_TILE"), TestImages.png(24, 24)));
                var start = new java.util.concurrent.CountDownLatch(1);
                var accountRemoval = executor.submit(() -> {
                    start.await();
                    uploader.post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange().expectStatus().isNoContent();
                    return true;
                });
                var glazeRemoval = executor.submit(() -> {
                    start.await();
                    admin.delete().uri("/api/glazes/{id}", glaze).exchange().expectStatus().isNoContent();
                    return true;
                });
                start.countDown();
                assertTrue(accountRemoval.get(15, java.util.concurrent.TimeUnit.SECONDS));
                assertTrue(glazeRemoval.get(15, java.util.concurrent.TimeUnit.SECONDS));
                assertFalse(users.existsById(uploaderId));
                var row = deletions.findAll().stream().filter(d -> d.getImageId().equals(image)).findFirst().orElseThrow();
                assertNull(row.getUploaderId());
                assertEquals(adminId, row.getDeletedBy());
            }
        }
    }
}
