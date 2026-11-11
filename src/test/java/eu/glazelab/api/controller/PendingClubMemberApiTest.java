package eu.glazelab.api.controller;

import eu.glazelab.api.service.PendingAccounts;
import eu.glazelab.api.support.ApiTestBase;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.RestTestClient;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A pending (unverified) account cannot sign in, so it never joins a club (membership comes only from an invite link);
 * a membership row it gets anyway (inserted here with SQL) is never promoted and never blocks its own purge.
 */
class PendingClubMemberApiTest extends ApiTestBase {
    @Autowired PendingAccounts pending;

    private UUID registerPending(String email) {
        register(guest(), email, "Squatter").expectStatus().isOk();
        return jdbc.queryForObject("SELECT id FROM app_user WHERE email = ?", UUID.class, email);
    }

    private void expire(UUID userId) {
        jdbc.update("UPDATE app_user SET verification_expires_at = ? WHERE id = ?",
                LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1), userId);
    }

    private int count(String table, UUID userId) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE user_id = ?", Integer.class, userId);
    }

    private boolean exists(UUID userId) {
        return jdbc.queryForObject("SELECT count(*) FROM app_user WHERE id = ?", Integer.class, userId) == 1;
    }

    @Test void aPendingMemberCannotBePromoted() {
        RestTestClient admin = user("Promoter");
        UUID club = createClub(admin, unique("Promote club"));
        UUID pendingId = registerPending(email("Legacy"));
        jdbc.update("INSERT INTO membership (club_id, user_id, role) VALUES (?, ?, 'MEMBER')", club, pendingId);

        assertError(admin.put().uri("/api/clubs/{id}/members/{user}", club, pendingId).body(Map.of("role", "ADMIN"))
                .exchange(), 404, "Member not found");
        assertEquals("MEMBER", jdbc.queryForObject("SELECT role FROM membership WHERE user_id = ?", String.class, pendingId));
    }

    @Test void anExpiredPendingMemberIsPurgedWithItsRowsAndTheAddressRegistersAgain() {
        RestTestClient donor = user("Donor");
        UUID club = createClub(donor, unique("Legacy club"));
        UUID glaze = addGlaze(donor, unique("Maker"), "Legacy glaze", "LG1");
        donor.post().uri("/api/stock").body(Map.of("glazeId", glaze, "quantityG", 500, "ownerType", "personal"))
                .exchange().expectStatus().isOk();
        donor.put().uri("/api/favorites/glazes/{id}", glaze).exchange().expectStatus().is2xxSuccessful();
        UUID donorId = me(donor).id();
        String pendingEmail = email("Expired");
        UUID pendingId = registerPending(pendingEmail);
        // Rows a pending account could only own through a bug: a membership as ADMIN,
        // a personal stock entry and a favourite.
        jdbc.update("INSERT INTO membership (club_id, user_id, role) VALUES (?, ?, 'ADMIN')", club, pendingId);
        jdbc.update("UPDATE stock SET user_id = ? WHERE user_id = ?", pendingId, donorId);
        jdbc.update("UPDATE favorite SET user_id = ? WHERE user_id = ?", pendingId, donorId);
        expire(pendingId);

        pending.expireBatch();

        assertFalse(exists(pendingId));
        assertEquals(0, count("membership", pendingId));
        assertEquals(0, count("stock", pendingId));
        assertEquals(0, count("favorite", pendingId));
        assertEquals(1, count("membership", donorId));
        assertNotNull(setupToken(register(guest(), pendingEmail, "Real Owner")), "the address is free again");
    }

    @Test void registeringAnExpiredPendingMemberAddressPurgesItAtOnce() {
        RestTestClient admin = user("Owner");
        UUID club = createClub(admin, unique("Squat club"));
        String pendingEmail = email("Squatted");
        UUID pendingId = registerPending(pendingEmail);
        jdbc.update("INSERT INTO membership (club_id, user_id, role) VALUES (?, ?, 'MEMBER')", club, pendingId);
        expire(pendingId);

        assertNotNull(setupToken(register(guest(), pendingEmail, "Real Owner")));
        assertFalse(exists(pendingId));
    }

    @Test void aPendingMemberCountsForTheLastAdminUntilItsPurge() {
        RestTestClient admin = user("LoneAdmin");
        UUID club = createClub(admin, unique("Lone club"));
        UUID pendingId = registerPending(email("Lingering"));
        jdbc.update("INSERT INTO membership (club_id, user_id, role) VALUES (?, ?, 'MEMBER')", club, pendingId);

        assertError(admin.post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange(), 409,
                "Make another member an admin in each club before deleting your account.");
        expire(pendingId);
        pending.expireBatch();
        admin.post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange().expectStatus().is2xxSuccessful();
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM club WHERE id = ?", Integer.class, club));
    }

    @Test void oneFailingPurgeDoesNotStopTheOthers() {
        RestTestClient admin = user("Blocker");
        UUID club = createClub(admin, unique("Blocked club"));
        UUID adminId = me(admin).id();
        UUID stuck = registerPending(email("Stuck"));
        UUID first = registerPending(email("First"));
        UUID second = registerPending(email("Second"));
        // A reference the purge does not clean up: the legacy club owner of a club (a pending account never owns one).
        jdbc.update("UPDATE club SET owner_id = ? WHERE id = ?", stuck, club);
        // Deleted before the failing account row: the failed purge must roll them back with it.
        jdbc.update("INSERT INTO membership (club_id, user_id, role) VALUES (?, ?, 'MEMBER')", club, stuck);
        int stuckTokens = count("account_token", stuck);
        assertTrue(stuckTokens > 0);
        try {
            expire(stuck);
            expire(first);
            expire(second);

            pending.expireBatch();

            assertTrue(exists(stuck));
            assertEquals(1, count("membership", stuck));
            assertEquals(stuckTokens, count("account_token", stuck));
            assertFalse(exists(first));
            assertFalse(exists(second));
        } finally {
            jdbc.update("UPDATE club SET owner_id = ? WHERE id = ?", adminId, club);
        }
        pending.expireBatch();
        assertFalse(exists(stuck));
    }
}
