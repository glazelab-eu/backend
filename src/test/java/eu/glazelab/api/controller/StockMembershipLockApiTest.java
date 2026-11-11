package eu.glazelab.api.controller;

import eu.glazelab.api.repository.ClubRepository;
import eu.glazelab.api.service.StockService;
import eu.glazelab.api.support.ApiTestBase;
import jakarta.persistence.EntityManager;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.client.RestTestClient;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Its own context: a member removed or demoted while a stock request waits for the club lock is refused under it. */
class StockMembershipLockApiTest extends ApiTestBase {
    @MockitoSpyBean ClubRepository clubs;
    @Autowired EntityManager entities;

    private record Shelf(RestTestClient member, UUID memberId, UUID club, UUID glaze, UUID stock) {}

    private Shelf shelf() {
        var admin = user("LockAdmin");
        var member = user("LockMember");
        var club = createClub(admin, unique("Lock club"));
        joinClub(admin, club, member);
        var glaze = addGlaze(admin, unique("Lock"), "Blue", "1");
        var stock = created(admin.post().uri("/api/stock")
                .body(Map.of("ownerType", "club", "clubId", club, "glazeId", glaze, "quantityG", 250)).exchange());
        return new Shelf(member, me(member).id(), club, glaze, stock);
    }

    /** Commits the membership removal while the request waits for the club lock, then grants it. */
    private void removeMemberBeforeTheLock(Shelf shelf) {
        beforeTheClubLock(clubs, entities, shelf.club(),
                () -> jdbc.update("DELETE FROM membership WHERE club_id = ? AND user_id = ?", shelf.club(), shelf.memberId()));
    }

    private int quantity(UUID stock) {
        return jdbc.queryForObject("SELECT quantity_g FROM stock WHERE id = ?", Integer.class, stock);
    }

    @Test void memberRemovedBeforeTheLockCannotSaveClubStock() {
        var shelf = shelf();
        removeMemberBeforeTheLock(shelf);
        try {
            shelf.member().post().uri("/api/stock").body(Map.of("ownerType", "club", "clubId", shelf.club(),
                            "glazeId", shelf.glaze(), "quantityG", 0)).exchange()
                    .expectStatus().isForbidden().expectBody().jsonPath("$.error").isEqualTo("Club membership required");
        } finally { reset(clubs); }
        assertEquals(250, quantity(shelf.stock()));
    }

    @Test void memberRemovedBeforeTheLockCannotAddAJarToClubStock() {
        var shelf = shelf();
        removeMemberBeforeTheLock(shelf);
        try {
            shelf.member().post().uri("/api/stock/add").body(Map.of("ownerType", "club", "clubId", shelf.club(),
                            "glazeId", shelf.glaze(), "addG", 250)).exchange()
                    .expectStatus().isForbidden().expectBody().jsonPath("$.error").isEqualTo("Club membership required");
        } finally { reset(clubs); }
        assertEquals(250, quantity(shelf.stock()));
    }

    @Test void memberRemovedBeforeTheLockCannotAddAGlazeWithClubStock() {
        var shelf = shelf();
        String name = unique("Locked out");
        removeMemberBeforeTheLock(shelf);
        try {
            shelf.member().post().uri("/api/glazes").body(Map.of("newManufacturer", Map.of("name", name, "shortId", shortId()),
                            "name", "Green", "code", "L1", "stock", Map.of("quantityG", 5, "clubId", shelf.club()))).exchange()
                    .expectStatus().isForbidden().expectBody().jsonPath("$.error").isEqualTo("Club membership required");
        } finally { reset(clubs); }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM manufacturer WHERE name = ?", Integer.class, name));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM stock WHERE club_id = ?", Integer.class, shelf.club()));
    }

    @Test void memberRemovedBeforeTheLockCannotRemoveClubStock() {
        var shelf = shelf();
        removeMemberBeforeTheLock(shelf);
        try {
            shelf.member().delete().uri("/api/stock/{id}", shelf.stock()).exchange()
                    .expectStatus().isNotFound().expectBody().jsonPath("$.error").isEqualTo("Unknown stock entry");
        } finally { reset(clubs); }
        assertEquals(250, quantity(shelf.stock()));
    }

    @Test void adminDemotedBeforeTheLockCannotRemoveSomeoneElsesClubStock() {
        var shelf = shelf();
        jdbc.update("UPDATE membership SET role = 'ADMIN' WHERE club_id = ? AND user_id = ?", shelf.club(), shelf.memberId());
        beforeTheClubLock(clubs, entities, shelf.club(), () -> jdbc.update(
                "UPDATE membership SET role = 'MEMBER' WHERE club_id = ? AND user_id = ?", shelf.club(), shelf.memberId()));
        try {
            assertError(shelf.member().delete().uri("/api/stock/{id}", shelf.stock()).exchange(), 403, StockService.REMOVE_RIGHTS_REQUIRED);
        } finally { reset(clubs); }
        assertEquals(250, quantity(shelf.stock()));
    }

    /** The adder is read under the lock too: one who left and rejoined meanwhile lost the right with the leave. */
    @Test void adderWhoLeftAndRejoinedBeforeTheLockCannotRemoveTheirFormerEntry() {
        var shelf = shelf();
        var own = created(shelf.member().post().uri("/api/stock").body(Map.of("ownerType", "club", "clubId", shelf.club(),
                "glazeId", addGlaze(shelf.member(), unique("Lock adder"), "Blue", "2"), "quantityG", 40)).exchange());
        beforeTheClubLock(clubs, entities, shelf.club(), () -> jdbc.update("UPDATE stock SET added_by = NULL WHERE id = ?", own));
        try {
            assertError(shelf.member().delete().uri("/api/stock/{id}", own).exchange(), 403, StockService.REMOVE_RIGHTS_REQUIRED);
        } finally { reset(clubs); }
        assertEquals(40, quantity(own));
    }
}
