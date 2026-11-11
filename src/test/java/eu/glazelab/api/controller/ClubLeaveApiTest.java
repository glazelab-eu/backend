package eu.glazelab.api.controller;

import eu.glazelab.api.dto.ClubDtos.MemberView;
import eu.glazelab.api.dto.StockDtos.StockView;
import eu.glazelab.api.model.Membership;
import eu.glazelab.api.security.CurrentUser;
import eu.glazelab.api.service.ClubService;
import eu.glazelab.api.support.ApiTestBase;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.test.web.servlet.client.RestTestClient.ResponseSpec;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code DELETE /api/clubs/{id}/members/{userId}}: a member leaves (their own ID), a club admin removes anyone else, the
 * last admin can do neither. The club keeps its stock; the leaver keeps their personal stock.
 */
class ClubLeaveApiTest extends ApiTestBase {
    /** Ada created the club (its only admin); Bea and Cy are members. */
    private RestTestClient ada, bea, cy;
    private UUID adaId, beaId, cyId, club;

    private void club() {
        ada = user("Ada");
        bea = user("Bea");
        cy = user("Cy");
        adaId = me(ada).id();
        beaId = me(bea).id();
        cyId = me(cy).id();
        club = createClub(ada, unique("Kiln"));
        joinClub(ada, club, bea);
        joinClub(ada, club, cy);
    }

    private static ResponseSpec remove(RestTestClient client, UUID club, UUID member) {
        return client.delete().uri("/api/clubs/{id}/members/{userId}", club, member).exchange();
    }

    private static List<UUID> members(RestTestClient client, UUID club) {
        return body(client.get().uri("/api/clubs/{id}/members", club).exchange().expectStatus().isOk(),
                new ParameterizedTypeReference<List<MemberView>>() {}).stream().map(MemberView::id).toList();
    }

    private static List<StockView> stock(RestTestClient client) {
        return body(client.get().uri("/api/stock").exchange().expectStatus().isOk(), new ParameterizedTypeReference<List<StockView>>() {});
    }

    private static UUID saveStock(RestTestClient client, UUID glaze, UUID club) {
        var body = club == null ? Map.<String, Object>of("glazeId", glaze, "quantityG", 100, "ownerType", "personal")
                : Map.<String, Object>of("glazeId", glaze, "quantityG", 100, "ownerType", "club", "clubId", club);
        return created(client.post().uri("/api/stock").body(body).exchange());
    }

    private static void setRole(RestTestClient admin, UUID club, UUID member, String role) {
        admin.put().uri("/api/clubs/{id}/members/{userId}", club, member).body(Map.of("role", role)).exchange().expectStatus().isOk();
    }

    @Test void aMemberLeavesAndNoLongerSeesTheClub() {
        club();
        remove(bea, club, beaId).expectStatus().isNoContent();
        assertTrue(club(bea, club).isEmpty());
        assertError(bea.get().uri("/api/clubs/{id}/members", club).exchange(), 403, CurrentUser.CLUB_MEMBERSHIP_REQUIRED);
        assertEquals(List.of(adaId, cyId).stream().sorted().toList(), members(ada, club).stream().sorted().toList());
        // Gone is gone: a second leave finds no membership.
        assertError(remove(bea, club, beaId), 403, CurrentUser.CLUB_MEMBERSHIP_REQUIRED);
    }

    @Test void anAdminRemovesAnyOtherMemberIncludingAnotherAdmin() {
        club();
        remove(ada, club, cyId).expectStatus().isNoContent();
        assertTrue(club(cy, club).isEmpty());
        setRole(ada, club, beaId, "ADMIN");
        remove(ada, club, beaId).expectStatus().isNoContent();
        assertEquals(List.of(adaId), members(ada, club));
        // The removed admin no longer sees the club: its admin endpoints answer exactly as for an unknown club.
        var settings = Map.of("name", "Taken back", "houseRules", "", "invitesEnabled", true);
        for (UUID id : List.of(club, UUID.randomUUID())) {
            assertError(bea.put().uri("/api/clubs/{id}", id).body(settings).exchange(), 404, "Club not found");
            assertError(bea.post().uri("/api/clubs/{id}/invite", id).exchange(), 404, "Club not found");
            assertError(bea.put().uri("/api/clubs/{id}/members/{userId}", id, adaId).body(Map.of("role", "MEMBER")).exchange(),
                    404, "Club not found");
        }
        assertEquals(Membership.Role.ADMIN, club(ada, club).orElseThrow().role());
        assertNotEquals("Taken back", club(ada, club).orElseThrow().name());
    }

    @Test void theLastAdminCanNeitherLeaveNorBeRemovedButAnAdminWithACoAdminMayLeave() {
        club();
        assertError(remove(ada, club, adaId), 409, ClubService.LAST_ADMIN);
        UUID alone = createClub(cy, unique("Solo"));
        assertError(remove(cy, alone, cyId), 409, ClubService.LAST_ADMIN);
        setRole(ada, club, beaId, "ADMIN");
        remove(ada, club, adaId).expectStatus().isNoContent();
        assertError(remove(bea, club, beaId), 409, ClubService.LAST_ADMIN);
        assertTrue(club(cy, alone).isPresent());
        assertEquals(List.of(beaId, cyId).stream().sorted().toList(), members(bea, club).stream().sorted().toList());
    }

    @Test void membersOutsidersAndOtherClubsAdminsCannotRemoveAnyone() {
        club();
        RestTestClient dee = user("Dee");
        UUID other = createClub(dee, unique("Other"));
        assertError(remove(bea, club, cyId), 403, ClubService.ADMIN_REQUIRED);
        assertError(remove(bea, club, adaId), 403, ClubService.ADMIN_REQUIRED);
        assertError(remove(dee, club, cyId), 403, CurrentUser.CLUB_MEMBERSHIP_REQUIRED);
        assertError(remove(dee, UUID.randomUUID(), me(dee).id()), 403, CurrentUser.CLUB_MEMBERSHIP_REQUIRED);
        assertError(remove(ada, other, me(dee).id()), 403, CurrentUser.CLUB_MEMBERSHIP_REQUIRED);
        assertError(remove(ada, club, me(dee).id()), 404, ClubService.MEMBER_NOT_FOUND);
        assertError(remove(ada, club, UUID.randomUUID()), 404, ClubService.MEMBER_NOT_FOUND);
        remove(guest(), club, cyId).expectStatus().isUnauthorized();
        ada.delete().uri("/api/clubs/{id}/members/{userId}", club, "not-a-uuid").exchange().expectStatus().isBadRequest();
        assertEquals(3, members(ada, club).size());
        assertEquals(List.of(me(dee).id()), members(dee, other));
    }

    @Test void clubStockStaysWithTheClubWithoutItsAdderAndPersonalStockStaysWithTheLeaver() {
        club();
        UUID glaze = addGlaze(bea, unique("Shelf"), "Blue", "PC-20");
        UUID shared = saveStock(bea, glaze, club);
        UUID personal = saveStock(bea, glaze, null);
        StockView before = stock(cy).getFirst();
        assertEquals(beaId, before.addedById());
        assertFalse(before.canRemove());

        remove(bea, club, beaId).expectStatus().isNoContent();
        assertEquals(List.of(personal), stock(bea).stream().map(StockView::id).toList());
        StockView kept = stock(cy).getFirst();
        assertEquals(shared, kept.id());
        assertEquals(club, kept.clubId());
        assertNull(kept.userId());
        assertNull(kept.addedById());
        assertFalse(kept.canRemove());
        assertTrue(stock(ada).getFirst().canRemove());
        assertError(bea.delete().uri("/api/stock/{id}", shared).exchange(), 404, "Unknown stock entry");

        // Rejoining gives the entry no adder back: only admins remove it now.
        joinClub(ada, club, bea);
        assertError(bea.delete().uri("/api/stock/{id}", shared).exchange(), 403, eu.glazelab.api.service.StockService.REMOVE_RIGHTS_REQUIRED);
        ada.delete().uri("/api/stock/{id}", shared).exchange().expectStatus().isNoContent();
    }

    @Test void clubStockSurvivesItsAdderDeletingTheirAccount() {
        club();
        UUID glaze = addGlaze(cy, unique("Deleted adder"), "Blue", "PC-20");
        UUID shared = saveStock(cy, glaze, club);
        saveStock(cy, glaze, null);
        cy.post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange().expectStatus().isNoContent();
        StockView kept = stock(bea).getFirst();
        assertEquals(shared, kept.id());
        assertNull(kept.addedById());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM stock WHERE glaze_id = ?", Integer.class, glaze));
    }

    @Test void aRemovedMemberMayRejoinWhileTheInviteLinkIsActive() {
        club();
        String link = invite(ada, club);
        remove(ada, club, cyId).expectStatus().isNoContent();
        cy.post().uri("/api/clubs/join").body(Map.of("token", link)).exchange().expectStatus().isOk();
        assertTrue(club(cy, club).isPresent());
        // Replacing the link is how an admin keeps them out.
        remove(ada, club, cyId).expectStatus().isNoContent();
        invite(ada, club);
        cy.post().uri("/api/clubs/join").body(Map.of("token", link)).exchange().expectStatus().isNotFound();
        assertTrue(club(ada, club).orElseThrow().hasInviteLink());
    }

    /**
     * A page left open after leaving: every write that names the club is refused under its lock, nothing of the
     * club is listed any more, and a site admin outside the club has no removal power either.
     */
    @Test void aLeaverReachesNothingOfTheClubAndASiteAdminRemovesNobody() {
        club();
        UUID glaze = addGlaze(bea, unique("Open page"), "Blue", "PC-20");
        UUID combo = addCombination(bea, "Left open", glaze);
        UUID clubPhoto = uploadTile(bea, combo, "CLUB", club);
        UUID publicPhoto = uploadTile(bea, combo, "PUBLIC", null);
        saveStock(bea, glaze, club);
        remove(bea, club, beaId).expectStatus().isNoContent();

        String member = CurrentUser.CLUB_MEMBERSHIP_REQUIRED;
        assertError(bea.post().uri("/api/stock").body(Map.of("glazeId", glaze, "quantityG", 5, "ownerType", "club",
                "clubId", club)).exchange(), 403, member);
        assertError(bea.post().uri("/api/glazes").body(Map.of("manufacturerId", glazes(bea).getFirst().manufacturer().id(),
                "name", "Late", "code", unique("L"), "stock", Map.of("quantityG", 5, "clubId", club))).exchange(), 403, member);
        assertError(upload(bea, combo, Map.of("visibility", "CLUB", "clubId", club.toString(), "kind", "TEST_TILE"), PNG), 403, member);
        assertError(bea.put().uri("/api/images/{id}/visibility", publicPhoto).body(Map.of("visibility", "CLUB", "clubId", club))
                .exchange(), 403, member);
        // The club's admin endpoints answer the leaver as for an unknown club.
        for (UUID id : List.of(club, UUID.randomUUID())) {
            assertError(bea.post().uri("/api/clubs/{id}/invite", id).exchange(), 404, "Club not found");
            assertError(bea.put().uri("/api/clubs/{id}/members/{userId}", id, cyId).body(Map.of("role", "ADMIN")).exchange(),
                    404, "Club not found");
        }

        assertTrue(stock(bea).isEmpty());
        // The leaver still sees their own club photo, not the club's other content.
        assertEquals(java.util.Set.of(publicPhoto, clubPhoto), combination(bea, combo).orElseThrow().images().stream().map(image -> image.id()).collect(java.util.stream.Collectors.toSet()));
        bea.get().uri("/api/images/{id}", clubPhoto).exchange().expectStatus().isOk();
        assertTrue(combination(cy, combo).orElseThrow().images().stream().anyMatch(image -> image.id().equals(clubPhoto)));

        RestTestClient admin = siteAdmin();
        assertError(remove(admin, club, cyId), 403, member);
        assertEquals(2, members(ada, club).size());
    }

    @Test void theCreatorWhoLeavesIsNoLongerTheClubsOwner() {
        club();
        assertEquals(adaId, club(bea, club).orElseThrow().ownerId());
        setRole(ada, club, beaId, "ADMIN");
        remove(ada, club, adaId).expectStatus().isNoContent();
        assertNull(club(bea, club).orElseThrow().ownerId());
        // Deleting the former creator's account leaves the club alone (it is not theirs any more).
        ada.post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange().expectStatus().isNoContent();
        assertEquals(List.of(beaId, cyId).stream().sorted().toList(), members(bea, club).stream().sorted().toList());
    }
}
