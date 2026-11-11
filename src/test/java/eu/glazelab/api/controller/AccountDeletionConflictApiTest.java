package eu.glazelab.api.controller;

import eu.glazelab.api.repository.AppUserRepository;
import eu.glazelab.api.repository.ClubRepository;
import eu.glazelab.api.repository.ImageRecordRepository;
import eu.glazelab.api.storage.ImageStorage;
import eu.glazelab.api.support.ApiTestBase;
import eu.glazelab.api.support.TestImages;
import jakarta.persistence.EntityManager;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Its own context injects a final-delete conflict without affecting ordinary account tests. */
class AccountDeletionConflictApiTest extends ApiTestBase {
    @MockitoSpyBean AppUserRepository users;
    @MockitoSpyBean ClubRepository clubs;
    @Autowired ImageRecordRepository images;
    @Autowired ImageStorage storage;
    @Autowired EntityManager entities;

    @Test void integrityConflictRollsBackRecordsAndLeavesFilesUntilRetrySucceeds() throws Exception {
        var owner = user("Conflict");
        var userId = me(owner).id();
        var club = createClub(owner, unique("Conflict club"));
        var glaze = addGlaze(owner, unique("Conflict"), "Blue", "1");
        owner.post().uri("/api/stock").body(Map.of("ownerType", "club", "clubId", club, "glazeId", glaze, "quantityG", 250)).exchange().expectStatus().isOk();
        var image = created(uploadGlazeImage(owner, glaze, Map.of("visibility", "PRIVATE", "kind", "TEST_TILE"), TestImages.png(24, 24)));
        String key = images.findById(image).orElseThrow().getStorageKey();
        byte[] original = storage.get(key);
        // Like a late FK reference: fail the final account DELETE after all earlier SQL has run.
        doThrow(new DataIntegrityViolationException("Simulated late foreign key reference")).when(users).deleteById(userId);
        try {
            owner.post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange()
                    .expectStatus().isEqualTo(409).expectBody()
                    .jsonPath("$.error").isEqualTo("Your account data changed. Please try deleting it again.");
            assertTrue(users.existsById(userId));
            assertTrue(images.existsById(image));
            assertArrayEquals(original, storage.get(key));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM club WHERE id = ?", Integer.class, club));
            assertEquals(250, jdbc.queryForObject("SELECT quantity_g FROM stock WHERE club_id = ?", Integer.class, club));
            assertEquals(userId, jdbc.queryForObject("SELECT created_by FROM glaze WHERE id = ?", java.util.UUID.class, glaze));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM image_deletion WHERE image_id = ?", Integer.class, image));
            assertEquals(userId, me(owner).id());
        } finally { reset(users); }

        owner.post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange().expectStatus().isNoContent();
        assertFalse(users.existsById(userId));
        assertFalse(images.existsById(image));
        assertStored(storage, key, false);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM club WHERE id = ?", Integer.class, club));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM image_deletion WHERE image_id = ?", Integer.class, image));
    }

    @Test void roleChangedBeforeTheClubLockIsCheckedFresh() {
        var admin = user("HandoverAdmin");
        var member = user("PromotedMember");
        var memberId = me(member).id();
        var adminId = me(admin).id();
        var club = createClub(admin, unique("Handover club"));
        joinClub(admin, club, member);
        // Committed while the deletion waits for the club lock: the member became the only admin.
        beforeTheClubLock(clubs, entities, club, () -> {
            jdbc.update("UPDATE membership SET role = 'ADMIN' WHERE club_id = ? AND user_id = ?", club, memberId);
            jdbc.update("UPDATE membership SET role = 'MEMBER' WHERE club_id = ? AND user_id = ?", club, adminId);
        });
        try {
            member.post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange()
                    .expectStatus().isEqualTo(409).expectBody()
                    .jsonPath("$.error").isEqualTo("Make another member an admin in each club before deleting your account.");
        } finally { reset(clubs); }
        assertTrue(users.existsById(memberId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM membership WHERE club_id = ? AND role = 'ADMIN'", Integer.class, club));
    }

    @Test void memberJoinedBeforeTheClubLockKeepsTheClub() {
        var admin = user("SoleAdmin");
        var adminId = me(admin).id();
        var joinerId = me(user("LateMember")).id();
        var club = createClub(admin, unique("Sole club"));
        var combination = addCombination(admin, unique("Sole tile"), addGlaze(admin, unique("Sole"), "Blue", "1"));
        var image = uploadTile(admin, combination, "CLUB", club);
        // Committed while the deletion waits for the club lock: the club is no longer the user's alone.
        beforeTheClubLock(clubs, entities, club,
                () -> jdbc.update("INSERT INTO membership (club_id, user_id, role) VALUES (?, ?, 'MEMBER')", club, joinerId));
        try {
            admin.post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange()
                    .expectStatus().isEqualTo(409).expectBody()
                    .jsonPath("$.error").isEqualTo("Make another member an admin in each club before deleting your account.");
        } finally { reset(clubs); }
        assertTrue(users.existsById(adminId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM club WHERE id = ?", Integer.class, club));
        assertTrue(images.existsById(image));
    }
}
