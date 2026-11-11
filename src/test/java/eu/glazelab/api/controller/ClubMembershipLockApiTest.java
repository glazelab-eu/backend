package eu.glazelab.api.controller;

import eu.glazelab.api.dto.ClubDtos.InviteView;
import eu.glazelab.api.model.Club;
import eu.glazelab.api.repository.ClubRepository;
import eu.glazelab.api.service.ClubService;
import eu.glazelab.api.support.ApiTestBase;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.client.RestTestClient;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Its own context: membership and role changes committed while a club write waits for the club lock are seen
 * under it (memberships and roles that gate a write are read fresh after the lock, never before).
 */
class ClubMembershipLockApiTest extends ApiTestBase {
    @MockitoSpyBean ClubRepository clubs;
    @Autowired EntityManager entities;

    private record Members(RestTestClient admin, UUID adminId, RestTestClient member, UUID memberId, UUID club) {}

    private Members club() {
        var admin = user("LockAdmin");
        var member = user("LockMember");
        var club = createClub(admin, unique("Lock club"));
        joinClub(admin, club, member);
        return new Members(admin, me(admin).id(), member, me(member).id(), club);
    }

    private void setRole(UUID club, UUID user, String role) {
        jdbc.update("UPDATE membership SET role = ? WHERE club_id = ? AND user_id = ?", role, club, user);
    }

    private void removeMember(UUID club, UUID user) {
        jdbc.update("DELETE FROM membership WHERE club_id = ? AND user_id = ?", club, user);
    }

    @Test void adminDemotedBeforeTheLockCannotChangeTheClub() {
        var c = club();
        beforeTheClubLock(clubs, entities, c.club(), () -> {
            setRole(c.club(), c.memberId(), "ADMIN");
            setRole(c.club(), c.adminId(), "MEMBER");
        });
        try {
            assertError(c.admin().put().uri("/api/clubs/{id}", c.club()).body(Map.of("name", "Renamed",
                    "houseRules", "", "invitesEnabled", true)).exchange(), 403, "Club admin role required");
        } finally { reset(clubs); }
        assertNotEquals("Renamed", jdbc.queryForObject("SELECT name FROM club WHERE id = ?", String.class, c.club()));
    }

    @Test void lastAdminGuardCountsAdminsDemotedBeforeTheLock() {
        var c = club();
        setRole(c.club(), c.memberId(), "ADMIN");
        beforeTheClubLock(clubs, entities, c.club(), () -> setRole(c.club(), c.memberId(), "MEMBER"));
        try {
            assertError(c.admin().put().uri("/api/clubs/{id}/members/{userId}", c.club(), c.adminId())
                    .body(Map.of("role", "MEMBER")).exchange(), 409, "A club must have at least one admin");
        } finally { reset(clubs); }
        assertEquals("ADMIN", jdbc.queryForObject("SELECT role FROM membership WHERE club_id = ? AND user_id = ?",
                String.class, c.club(), c.adminId()));
    }

    @Test void inviteDisabledBeforeTheLockCannotBeUsed() {
        var c = club();
        c.admin().put().uri("/api/clubs/{id}", c.club()).body(Map.of("name", unique("Open club"), "houseRules", "",
                "invitesEnabled", true)).exchange().expectStatus().isOk();
        String token = body(c.admin().post().uri("/api/clubs/{id}/invite", c.club()).exchange().expectStatus().isOk(),
                InviteView.class).token();
        var joiner = user("LateJoiner");
        // The invite lookup by hash is the club lock (PESSIMISTIC_WRITE).
        doAnswer(call -> {
            jdbc.update("UPDATE club SET invites_enabled = FALSE WHERE id = ?", c.club());
            return Optional.of(entities.find(Club.class, c.club(), LockModeType.PESSIMISTIC_WRITE));
        }).when(clubs).findByInviteHash(any());
        try {
            assertError(joiner.post().uri("/api/clubs/join").body(Map.of("token", token)).exchange(), 404,
                    "This invite link is inactive or invalid");
        } finally { reset(clubs); }
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM membership WHERE club_id = ?", Integer.class, c.club()));
    }

    @Test void memberRemovedBeforeTheLockCannotUploadAClubPhoto() {
        var c = club();
        var combination = addCombination(c.member(), unique("Lock tile"), addGlaze(c.member(), unique("Lock"), "Blue", "1"));
        beforeTheClubLock(clubs, entities, c.club(), () -> removeMember(c.club(), c.memberId()));
        try {
            assertError(upload(c.member(), combination, Map.of("visibility", "CLUB", "kind", "TEST_TILE",
                    "clubId", c.club().toString()), PNG), 403, "Club membership required");
        } finally { reset(clubs); }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM image WHERE combination_id = ?", Integer.class, combination));
    }

    @Test void memberRemovedBeforeTheLockCannotShareAPhotoWithTheClub() {
        var c = club();
        var combination = addCombination(c.member(), unique("Lock share"), addGlaze(c.member(), unique("Lock"), "Blue", "1"));
        var image = uploadTile(c.member(), combination, "PRIVATE", null);
        beforeTheClubLock(clubs, entities, c.club(), () -> removeMember(c.club(), c.memberId()));
        try {
            assertError(c.member().put().uri("/api/images/{id}/visibility", image)
                    .body(Map.of("visibility", "CLUB", "clubId", c.club())).exchange(), 403, "Club membership required");
        } finally { reset(clubs); }
        assertEquals("PRIVATE", jdbc.queryForObject("SELECT visibility FROM image WHERE id = ?", String.class, image));
    }

    @Test void clubAdminDemotedBeforeTheLockCannotDeleteAMembersClubPhoto() {
        var c = club();
        var combination = addCombination(c.member(), unique("Lock delete"), addGlaze(c.member(), unique("Lock"), "Blue", "1"));
        var image = uploadTile(c.member(), combination, "CLUB", c.club());
        beforeTheClubLock(clubs, entities, c.club(), () -> setRole(c.club(), c.adminId(), "MEMBER"));
        try {
            assertError(c.admin().delete().uri("/api/images/{id}", image).exchange(), 403, ImageController.DELETE_RIGHTS_REQUIRED);
        } finally { reset(clubs); }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM image WHERE id = ?", Integer.class, image));
    }

    @Test void memberPromotedBeforeTheLockMayDeleteAClubPhoto() {
        var c = club();
        var combination = addCombination(c.admin(), unique("Lock promote"), addGlaze(c.admin(), unique("Lock"), "Blue", "1"));
        var image = uploadTile(c.admin(), combination, "CLUB", c.club());
        beforeTheClubLock(clubs, entities, c.club(), () -> setRole(c.club(), c.memberId(), "ADMIN"));
        try {
            c.member().delete().uri("/api/images/{id}", image).exchange().expectStatus().isNoContent();
        } finally { reset(clubs); }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM image WHERE id = ?", Integer.class, image));
    }

    @Test void adminDemotedBeforeTheLockCannotRemoveAMember() {
        var c = club();
        var third = user("LockThird");
        joinClub(c.admin(), c.club(), third);
        setRole(c.club(), c.memberId(), "ADMIN");
        beforeTheClubLock(clubs, entities, c.club(), () -> setRole(c.club(), c.memberId(), "MEMBER"));
        try {
            assertError(c.member().delete().uri("/api/clubs/{id}/members/{userId}", c.club(), me(third).id()).exchange(),
                    403, ClubService.ADMIN_REQUIRED);
        } finally { reset(clubs); }
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM membership WHERE club_id = ?", Integer.class, c.club()));
    }

    @Test void leavingCountsAnAdminDemotedBeforeTheLock() {
        var c = club();
        setRole(c.club(), c.memberId(), "ADMIN");
        beforeTheClubLock(clubs, entities, c.club(), () -> setRole(c.club(), c.memberId(), "MEMBER"));
        try {
            assertError(c.admin().delete().uri("/api/clubs/{id}/members/{userId}", c.club(), c.adminId()).exchange(),
                    409, ClubService.LAST_ADMIN);
        } finally { reset(clubs); }
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM membership WHERE club_id = ?", Integer.class, c.club()));
    }

    @Test void memberWhoLeftBeforeTheLockIsNotRemovedTwice() {
        var c = club();
        beforeTheClubLock(clubs, entities, c.club(), () -> removeMember(c.club(), c.memberId()));
        try {
            assertError(c.admin().delete().uri("/api/clubs/{id}/members/{userId}", c.club(), c.memberId()).exchange(),
                    404, ClubService.MEMBER_NOT_FOUND);
        } finally { reset(clubs); }
    }

    /** The author's clubs are read under the destination club lock, which a leave takes too (23-61). */
    @Test void authorWhoLeftBeforeTheLockGetsNoClubPhotoOnTheirCombination() {
        var c = club();
        var combination = addCombination(c.member(), unique("Lock author"), addGlaze(c.member(), unique("Lock"), "Blue", "1"));
        beforeTheClubLock(clubs, entities, c.club(), () -> removeMember(c.club(), c.memberId()));
        try {
            assertError(upload(c.admin(), combination, Map.of("visibility", "CLUB", "kind", "TEST_TILE",
                    "clubId", c.club().toString()), PNG), 400, ImageController.AUTHOR_MUST_SEE);
        } finally { reset(clubs); }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM image WHERE combination_id = ?", Integer.class, combination));
    }

    @Test void authorWhoLeftBeforeTheLockKeepsSeeingAPhotoMovedToTheClub() {
        var c = club();
        var combination = addCombination(c.member(), unique("Lock author move"), addGlaze(c.member(), unique("Lock"), "Blue", "1"));
        var image = uploadTile(c.admin(), combination, "PUBLIC", null);
        beforeTheClubLock(clubs, entities, c.club(), () -> removeMember(c.club(), c.memberId()));
        try {
            assertError(c.admin().put().uri("/api/images/{id}/visibility", image)
                    .body(Map.of("visibility", "CLUB", "clubId", c.club())).exchange(), 400, ImageController.AUTHOR_MUST_SEE);
        } finally { reset(clubs); }
        assertEquals("PUBLIC", jdbc.queryForObject("SELECT visibility FROM image WHERE id = ?", String.class, image));
    }

    /** An admin removed while the write waited no longer sees the club: answered as for an unknown club. */
    @Test void adminRemovedBeforeTheLockGetsTheUnknownClubAnswer() {
        var c = club();
        setRole(c.club(), c.memberId(), "ADMIN");
        beforeTheClubLock(clubs, entities, c.club(), () -> removeMember(c.club(), c.adminId()));
        try {
            assertError(c.admin().put().uri("/api/clubs/{id}", c.club()).body(Map.of("name", "Renamed",
                    "houseRules", "", "invitesEnabled", true)).exchange(), 404, ClubService.CLUB_NOT_FOUND);
        } finally { reset(clubs); }
        assertNotEquals("Renamed", jdbc.queryForObject("SELECT name FROM club WHERE id = ?", String.class, c.club()));
    }

    /**
     * An outsider's request never takes, nor waits for, the lock of a club it names: its membership is read first,
     * without a lock, so a real club answers as fast as an unknown one and its lock queue tells nothing.
     */
    @Test void outsidersNeverTakeTheClubLock() {
        var c = club();
        var outsider = user("LockOutsider");
        UUID outsiderId = me(outsider).id();
        UUID glaze = addGlaze(outsider, unique("Lock outsider"), "Blue", "1");
        var combination = addCombination(outsider, unique("Lock outsider"), glaze);
        var photo = uploadTile(outsider, combination, "PRIVATE", null);
        UUID manufacturer = glazes(outsider).stream().filter(g -> g.id().equals(glaze)).findFirst().orElseThrow().manufacturer().id();
        clearInvocations(clubs);
        var settings = Map.of("name", "Renamed", "houseRules", "", "invitesEnabled", true);
        assertError(outsider.put().uri("/api/clubs/{id}", c.club()).body(settings).exchange(), 404, ClubService.CLUB_NOT_FOUND);
        assertError(outsider.post().uri("/api/clubs/{id}/invite", c.club()).exchange(), 404, ClubService.CLUB_NOT_FOUND);
        assertError(outsider.put().uri("/api/clubs/{id}/members/{userId}", c.club(), c.memberId()).body(Map.of("role", "ADMIN"))
                .exchange(), 404, ClubService.CLUB_NOT_FOUND);
        String member = eu.glazelab.api.security.CurrentUser.CLUB_MEMBERSHIP_REQUIRED;
        assertError(outsider.delete().uri("/api/clubs/{id}/members/{userId}", c.club(), c.memberId()).exchange(), 403, member);
        assertError(outsider.delete().uri("/api/clubs/{id}/members/{userId}", c.club(), outsiderId).exchange(), 403, member);
        assertError(outsider.post().uri("/api/stock").body(Map.of("glazeId", glaze, "quantityG", 5, "ownerType", "club",
                "clubId", c.club())).exchange(), 403, member);
        assertError(outsider.post().uri("/api/glazes").body(Map.of("manufacturerId", manufacturer, "name", "Late",
                "code", unique("L"), "stock", Map.of("quantityG", 5, "clubId", c.club()))).exchange(), 403, member);
        assertError(outsider.put().uri("/api/images/{id}/visibility", photo).body(Map.of("visibility", "CLUB", "clubId", c.club()))
                .exchange(), 403, member);
        assertError(upload(outsider, combination, Map.of("visibility", "CLUB", "kind", "TEST_TILE", "clubId", c.club().toString()),
                PNG), 403, member);
        assertError(outsider.post().uri("/api/buddies/requests").body(Map.of("clubId", c.club(), "userId", c.memberId())).exchange(),
                403, member);
        assertError(outsider.post().uri("/api/clubs/{id}/wishlist", c.club()).body(Map.of("type", "GLAZE", "glazeId", glaze)).exchange(),
                403, member);
        assertError(outsider.delete().uri("/api/clubs/{id}/wishlist/{entry}", c.club(), UUID.randomUUID()).exchange(), 403, member);
        assertError(outsider.put().uri("/api/clubs/{id}/wishlist/{entry}/acquired", c.club(), UUID.randomUUID())
                .body(Map.of("acquired", true)).exchange(), 403, member);
        verify(clubs, never()).lockById(c.club());
        // Deleting a club's photo by its ID: an outsider gets 404 without the club lock, as for an unknown picture.
        var clubPhoto = uploadTile(c.member(), addCombination(c.member(), unique("Lock club photo"),
                addGlaze(c.member(), unique("Lock club photo"), "Blue", "1")), "CLUB", c.club());
        clearInvocations(clubs);
        assertError(outsider.delete().uri("/api/images/{id}", clubPhoto).exchange(), 404, "Image not found");
        verify(clubs, never()).lockById(c.club());
        // A plain member is refused before the lock too; an admin takes it.
        assertError(c.member().put().uri("/api/clubs/{id}", c.club()).body(settings).exchange(), 403, ClubService.ADMIN_REQUIRED);
        verify(clubs, never()).lockById(c.club());
        c.admin().put().uri("/api/clubs/{id}", c.club()).body(settings).exchange().expectStatus().isOk();
        verify(clubs).lockById(c.club());
    }

    /** A wishlist write reads the membership, the role and the entry's adder under the club lock. */
    @Test void wishlistWritesDecideByTheMembershipAndRoleUnderTheClubLock() {
        var c = club();
        UUID glaze = addGlaze(c.member(), unique("Lock wish"), "Blue", "1");
        // The member leaves while the addition waits: refused as a non-member, nothing added.
        beforeTheClubLock(clubs, entities, c.club(), () -> removeMember(c.club(), c.memberId()));
        try {
            assertError(c.member().post().uri("/api/clubs/{id}/wishlist", c.club()).body(Map.of("type", "GLAZE", "glazeId", glaze)).exchange(),
                    403, eu.glazelab.api.security.CurrentUser.CLUB_MEMBERSHIP_REQUIRED);
        } finally { reset(clubs); }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM club_wishlist WHERE club_id = ?", Integer.class, c.club()));
        // An admin demoted while a removal of someone else's entry waits: refused like any member.
        var d = club();
        UUID entry = created(d.member().post().uri("/api/clubs/{id}/wishlist", d.club()).body(Map.of("type", "GLAZE", "glazeId", glaze)).exchange());
        jdbc.update("INSERT INTO membership (club_id, user_id, role, joined_at) SELECT ?, id, 'ADMIN', CURRENT_TIMESTAMP FROM app_user WHERE id = ?",
                d.club(), c.memberId());
        beforeTheClubLock(clubs, entities, d.club(), () -> setRole(d.club(), d.adminId(), "MEMBER"));
        try {
            assertError(d.admin().delete().uri("/api/clubs/{id}/wishlist/{entry}", d.club(), entry).exchange(), 403,
                    eu.glazelab.api.service.WishlistService.MANAGE_RIGHTS_REQUIRED);
        } finally { reset(clubs); }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM club_wishlist WHERE id = ?", Integer.class, entry));
    }

    @Test void aBuddyRequestToAClubMemberReadsBothMembershipsUnderTheClubLock() {
        var c = club();
        // The asker leaves while the request waits for the club lock: refused as a non-member.
        beforeTheClubLock(clubs, entities, c.club(), () -> removeMember(c.club(), c.memberId()));
        try {
            assertError(c.member().post().uri("/api/buddies/requests").body(Map.of("clubId", c.club(), "userId", c.adminId())).exchange(),
                    403, eu.glazelab.api.security.CurrentUser.CLUB_MEMBERSHIP_REQUIRED);
        } finally { reset(clubs); }
        // The asked member leaves meanwhile: they are no longer a member to ask.
        var d = club();
        beforeTheClubLock(clubs, entities, d.club(), () -> removeMember(d.club(), d.memberId()));
        try {
            assertError(d.admin().post().uri("/api/buddies/requests").body(Map.of("clubId", d.club(), "userId", d.memberId())).exchange(),
                    404, eu.glazelab.api.service.ClubService.MEMBER_NOT_FOUND);
        } finally { reset(clubs); }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM buddy WHERE user_a IN (?, ?, ?, ?) OR user_b IN (?, ?, ?, ?)", Integer.class,
                c.adminId(), c.memberId(), d.adminId(), d.memberId(), c.adminId(), c.memberId(), d.adminId(), d.memberId()));
    }
}
