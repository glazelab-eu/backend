package eu.glazelab.api.controller;

import eu.glazelab.api.dto.ClubDtos.ClubView;
import eu.glazelab.api.dto.ClubDtos.InvitePreviewView;
import eu.glazelab.api.dto.ClubDtos.InviteView;
import eu.glazelab.api.dto.ClubDtos.MemberView;
import eu.glazelab.api.model.Membership.Role;
import eu.glazelab.api.security.SecureTokens;
import eu.glazelab.api.support.ApiTestBase;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.junit.jupiter.api.Assertions.*;

class ClubApiTest extends ApiTestBase {
    private static RestTestClient.ResponseSpec setRole(RestTestClient client, UUID club, UUID user, String role) {
        return client.put().uri("/api/clubs/{id}/members/{user}", club, user).body(Map.of("role", role)).exchange();
    }

    private static RestTestClient.ResponseSpec updateClub(RestTestClient client, UUID club, Map<String, Object> settings) {
        return client.put().uri("/api/clubs/{id}", club).body(settings).exchange();
    }

    private static List<MemberView> members(RestTestClient admin, UUID club) {
        return body(admin.get().uri("/api/clubs/{id}/members", club).exchange().expectStatus().isOk(),
                new ParameterizedTypeReference<List<MemberView>>() {});
    }

    private static RestTestClient.ResponseSpec join(RestTestClient client, String token) {
        return client.post().uri("/api/clubs/join").body(Map.of("token", token)).exchange();
    }

    @Test void onlyAdminsManageSettingsAndAClubKeepsAnAdmin() {
        RestTestClient ada = user("Ada");
        RestTestClient bea = user("Bea");
        UUID club = createClub(ada, unique("Kiln"));
        joinClub(ada, club, bea);
        UUID adaId = me(ada).id();
        UUID beaId = me(bea).id();
        Map<String, Object> settings = Map.of("name", unique("Shared kiln"),
                "houseRules", "Clean the tools.\nLabel your tiles.", "invitesEnabled", true);

        updateClub(ada, club, settings).expectStatus().isOk();
        updateClub(bea, club, settings).expectStatus().isForbidden();
        bea.post().uri("/api/clubs/{id}/invite", club).exchange().expectStatus().isForbidden();
        setRole(bea, club, beaId, "ADMIN").expectStatus().isForbidden();
        ClubView view = club(bea, club).orElseThrow();
        assertEquals(Role.MEMBER, view.role());
        assertEquals(settings.get("houseRules"), view.houseRules());
        bea.get().uri("/api/clubs").exchange().expectBody()
                .jsonPath("$[*].inviteHash").isEmpty()
                .jsonPath("$[*].token").isEmpty();

        setRole(ada, club, adaId, "MEMBER").expectStatus().isEqualTo(409);
        setRole(ada, club, beaId, "ADMIN").expectStatus().isOk();
        setRole(bea, club, adaId, "MEMBER").expectStatus().isOk();
        updateClub(ada, club, settings).expectStatus().isForbidden();
        setRole(bea, club, beaId, "MEMBER").expectStatus().isEqualTo(409);
    }

    @Test void membersReadTheMemberListButNeverAnEmailOrAnOutsiderTheClub() {
        RestTestClient ada = user("Ada");
        RestTestClient bea = user("Bea");
        RestTestClient cy = user("Cy");
        UUID club = createClub(ada, unique("Readers"));
        joinClub(ada, club, bea);
        UUID adaId = me(ada).id();
        UUID beaId = me(bea).id();
        List<MemberView> expected = List.of(new MemberView(adaId, me(ada).displayName(), Role.ADMIN, null),
                new MemberView(beaId, me(bea).displayName(), Role.MEMBER, null));
        assertEquals(expected, members(bea, club));
        bea.get().uri("/api/clubs/{id}/members", club).exchange().expectBody().jsonPath("$[*].email").isEmpty();
        // A non-member gets the same answer for a real club and for an unknown ID: no club is confirmed.
        assertError(cy.get().uri("/api/clubs/{id}/members", club).exchange(), 403, "Club membership required");
        assertError(cy.get().uri("/api/clubs/{id}/members", UUID.randomUUID()).exchange(), 403, "Club membership required");
        guest().get().uri("/api/clubs/{id}/members", club).exchange().expectStatus().isUnauthorized();
        // The read is fresh: it follows the membership as it is now.
        assertEquals(expected, members(ada, club));
    }

    @Test void inviteLinksCanBeDisabledReplacedAndRequireExplicitJoining() {
        RestTestClient ada = user("Ada");
        RestTestClient bea = user("Bea");
        String name = unique("Open studio");
        UUID club = createClub(bea, name);
        var settings = new HashMap<String, Object>(Map.of("name", name, "houseRules", "Wash your hands.",
                "invitesEnabled", false));
        String token = invite(bea, club);
        // The settings switch turns the link off without replacing it, and on again.
        updateClub(bea, club, settings).expectStatus().isOk();
        join(ada, token).expectStatus().isNotFound();
        settings.put("invitesEnabled", true);
        updateClub(bea, club, settings).expectStatus().isOk();
        assertEquals(new InvitePreviewView(name, "Wash your hands."), body(ada.post().uri("/api/clubs/invitations/preview")
                .body(Map.of("token", token)).exchange().expectStatus().isOk(), InvitePreviewView.class));
        join(guest(), token).expectStatus().isUnauthorized();

        String replacement = invite(bea, club);
        assertNotEquals(token, replacement);
        join(ada, token).expectStatus().isNotFound();
        join(ada, replacement).expectStatus().isOk();
        join(ada, replacement).expectStatus().isOk();
        assertEquals(Role.MEMBER, club(ada, club).orElseThrow().role());
        assertEquals(2, members(bea, club).size());

        settings.put("invitesEnabled", false);
        updateClub(bea, club, settings).expectStatus().isOk();
        ada.post().uri("/api/clubs/invitations/preview").body(Map.of("token", replacement)).exchange()
                .expectStatus().isNotFound();
        join(ada, "invalid").expectStatus().isBadRequest();
    }

    /** A new club starts with invites off; generating a link turns them on, so the first link works at once. */
    @Test void generatingAnInviteLinkTurnsInviteLinksOn() {
        RestTestClient ada = user("Ada");
        RestTestClient bea = user("Bea");
        RestTestClient cy = user("Cy");
        String name = unique("First link");
        UUID club = createClub(ada, name);
        ClubView created = club(ada, club).orElseThrow();
        assertFalse(created.invitesEnabled());
        assertFalse(created.hasInviteLink());
        assertNull(created.inviteVersion());

        String first = invite(ada, club);
        ClubView invited = club(ada, club).orElseThrow();
        assertTrue(invited.invitesEnabled());
        assertTrue(invited.hasInviteLink());
        join(bea, first).expectStatus().isOk();

        // The switch stays the off switch; a member can neither generate a link nor turn invites on.
        updateClub(ada, club, Map.of("name", name, "houseRules", "", "invitesEnabled", false)).expectStatus().isOk();
        assertError(bea.post().uri("/api/clubs/{id}/invite", club).exchange(), 403, "Club admin role required");
        assertFalse(club(ada, club).orElseThrow().invitesEnabled());
        join(cy, first).expectStatus().isNotFound();

        // Generating again turns invites back on with a new link; the old one stays dead.
        String second = invite(ada, club);
        assertTrue(club(ada, club).orElseThrow().invitesEnabled());
        join(cy, first).expectStatus().isNotFound();
        join(cy, second).expectStatus().isOk();
        assertEquals(3, members(ada, club).size());
    }

    /**
     * The club names its active link by a version that the generating answer shares, changes with each link and is
     * neither the token nor the stored hash; only admins get it (the invite panel is theirs), plain members null.
     */
    @Test void theInviteVersionNamesTheActiveLinkWithoutRevealingIt() {
        RestTestClient ada = user("Ada");
        RestTestClient bea = user("Bea");
        UUID club = createClub(ada, unique("Versions"));
        joinClub(ada, club, bea);
        UUID beaId = me(bea).id();
        InviteView first = body(ada.post().uri("/api/clubs/{id}/invite", club).exchange().expectStatus().isOk(), InviteView.class);
        assertEquals(first.version(), club(ada, club).orElseThrow().inviteVersion());
        ClubView member = club(bea, club).orElseThrow();
        assertNull(member.inviteVersion());
        assertTrue(member.hasInviteLink());
        assertFalse(first.version().contains(first.token()) || first.token().contains(first.version()));
        // Nor any part of the stored hash, the key the club is looked up by.
        assertFalse(SecureTokens.hash(first.token()).contains(first.version()));
        String stored = jdbc.queryForObject("SELECT invite_hash FROM club WHERE id = ?", String.class, club);
        assertFalse(stored.contains(first.version()) || first.version().contains(stored));
        InviteView second = body(ada.post().uri("/api/clubs/{id}/invite", club).exchange().expectStatus().isOk(), InviteView.class);
        assertNotEquals(first.version(), second.version());
        assertEquals(second.version(), club(ada, club).orElseThrow().inviteVersion());
        // The version follows the role: a promoted member gets it, a demoted admin no longer does.
        setRole(ada, club, beaId, "ADMIN").expectStatus().isOk();
        assertEquals(second.version(), club(bea, club).orElseThrow().inviteVersion());
        setRole(bea, club, me(ada).id(), "MEMBER").expectStatus().isOk();
        assertNull(club(ada, club).orElseThrow().inviteVersion());
        setRole(bea, club, me(ada).id(), "ADMIN").expectStatus().isOk();
        // Saving the settings keeps the link and so its version.
        updateClub(ada, club, Map.of("name", club(ada, club).orElseThrow().name(), "houseRules", "x", "invitesEnabled", false))
                .expectStatus().isOk();
        assertEquals(second.version(), club(ada, club).orElseThrow().inviteVersion());
    }

    /**
     * A club the caller cannot see answers exactly like an unknown club, so no request confirms that a club ID exists:
     * the admin endpoints 404, those that name a club as the place of a write 403 (membership required), byte for byte
     * the same for a real club and an unknown ID. A plain member, who sees the club, is told that an admin is needed.
     */
    @Test void aClubTheCallerCannotSeeAnswersLikeAnUnknownClub() {
        RestTestClient ada = user("Ada");
        RestTestClient bea = user("Bea");
        RestTestClient cy = user("Cy");
        String name = unique("Hidden");
        UUID club = createClub(ada, name);
        joinClub(ada, club, bea);
        UUID beaId = me(bea).id();
        UUID cyId = me(cy).id();
        UUID unknown = UUID.randomUUID();
        UUID glaze = addGlaze(cy, unique("Outsider"), "Blue", "O-1");
        UUID manufacturer = glazes(cy).stream().filter(g -> g.id().equals(glaze)).findFirst().orElseThrow().manufacturer().id();
        UUID combination = addCombination(cy, unique("Outsider tile"), glaze);
        UUID photo = uploadTile(cy, combination, "PRIVATE", null);
        Map<String, Object> settings = Map.of("name", unique("Taken over"), "houseRules", "", "invitesEnabled", true);
        UUID wish = created(ada.post().uri("/api/clubs/{id}/wishlist", club).body(Map.of("type", "GLAZE", "glazeId", glaze)).exchange());

        Map<String, BiFunction<RestTestClient, UUID, RestTestClient.ResponseSpec>> admin = Map.of(
                "settings", (client, id) -> updateClub(client, id, settings),
                "invite", (client, id) -> client.post().uri("/api/clubs/{id}/invite", id).exchange(),
                "role", (client, id) -> setRole(client, id, beaId, "ADMIN"));
        Map<String, BiFunction<RestTestClient, UUID, RestTestClient.ResponseSpec>> membership = Map.ofEntries(
                Map.entry("members", (client, id) -> client.get().uri("/api/clubs/{id}/members", id).exchange()),
                Map.entry("removal", (client, id) -> client.delete().uri("/api/clubs/{id}/members/{userId}", id, beaId).exchange()),
                Map.entry("leave", (client, id) -> client.delete().uri("/api/clubs/{id}/members/{userId}", id, cyId).exchange()),
                Map.entry("stock", (client, id) -> client.post().uri("/api/stock").body(Map.of("glazeId", glaze, "quantityG", 5,
                        "ownerType", "club", "clubId", id)).exchange()),
                Map.entry("glaze stock", (client, id) -> client.post().uri("/api/glazes").body(Map.of("manufacturerId", manufacturer,
                        "name", "Late", "code", unique("L"), "stock", Map.of("quantityG", 5, "clubId", id))).exchange()),
                Map.entry("upload", (client, id) -> upload(client, combination, Map.of("visibility", "CLUB", "kind", "TEST_TILE",
                        "clubId", id.toString()), PNG)),
                Map.entry("visibility", (client, id) -> client.put().uri("/api/images/{id}/visibility", photo)
                        .body(Map.of("visibility", "CLUB", "clubId", id)).exchange()),
                Map.entry("buddy request", (client, id) -> client.post().uri("/api/buddies/requests")
                        .body(Map.of("clubId", id, "userId", beaId)).exchange()),
                Map.entry("wishlist", (client, id) -> client.get().uri("/api/clubs/{id}/wishlist", id).exchange()),
                Map.entry("wishlist add", (client, id) -> client.post().uri("/api/clubs/{id}/wishlist", id)
                        .body(Map.of("type", "GLAZE", "glazeId", glaze)).exchange()),
                Map.entry("wishlist removal", (client, id) -> client.delete().uri("/api/clubs/{id}/wishlist/{entry}", id, wish).exchange()),
                Map.entry("wishlist acquired", (client, id) -> client.put().uri("/api/clubs/{id}/wishlist/{entry}/acquired", id, wish)
                        .body(Map.of("acquired", true)).exchange()));

        admin.forEach((what, call) -> {
            assertEquals("404 {\"error\":\"Club not found\"}", statusAndBody(call.apply(cy, club)), what);
            assertEquals(statusAndBody(call.apply(cy, unknown)), statusAndBody(call.apply(cy, club)), what);
            assertError(call.apply(bea, club), 403, "Club admin role required");
        });
        membership.forEach((what, call) -> {
            assertEquals("403 {\"error\":\"Club membership required\"}", statusAndBody(call.apply(cy, club)), what);
            assertEquals(statusAndBody(call.apply(cy, unknown)), statusAndBody(call.apply(cy, club)), what);
        });
        // Nothing of the club changed, and its admin still does all of it.
        assertEquals(name, club(ada, club).orElseThrow().name());
        assertEquals(2, members(ada, club).size());
        admin.forEach((what, call) -> call.apply(ada, club).expectStatus().isOk());
        assertEquals(Role.ADMIN, club(bea, club).orElseThrow().role());
    }

    @Test void membersAreListedForAdminsWithRolesAndTrimmedNames() {
        RestTestClient ada = user("Ada");
        RestTestClient bea = user("Bea");
        String name = unique("Trimmed");
        UUID club = createClub(ada, "  " + name + "  ");
        UUID adaId = me(ada).id();
        UUID beaId = me(bea).id();
        assertEquals(new ClubView(club, name, adaId, "", Role.ADMIN, false, false, null), club(ada, club).orElseThrow());
        joinClub(ada, club, bea);
        assertEquals(List.of(new MemberView(adaId, me(ada).displayName(), Role.ADMIN, null),
                new MemberView(beaId, me(bea).displayName(), Role.MEMBER, null)), members(ada, club));
        assertTrue(club(ada, club).orElseThrow().hasInviteLink());
    }

    /** Membership comes only from the invite link: nobody adds an account by its address (no address oracle, no consent skipped). */
    @Test void membersCannotBeAddedByEmail() {
        RestTestClient admin = user("Ada");
        String beaEmail = email("Bea");
        RestTestClient bea = signUp(beaEmail);
        UUID club = createClub(admin, unique("No adding"));
        RestTestClient member = user("Cy");
        joinClub(admin, club, member);
        for (String address : List.of(beaEmail, email("Nobody"))) {
            for (RestTestClient client : List.of(admin, member))
                client.post().uri("/api/clubs/{id}/members", club).body(Map.of("email", address)).exchange()
                        .expectStatus().isEqualTo(405);
            guest().post().uri("/api/clubs/{id}/members", club).body(Map.of("email", address)).exchange()
                    .expectStatus().isUnauthorized();
        }
        assertEquals(2, members(admin, club).size());
        assertTrue(club(bea, club).isEmpty());
    }

    @Test void missingClubsMembersAndAccountsAreNotFound() {
        RestTestClient ada = user("Ada");
        RestTestClient bea = user("Bea");
        UUID club = createClub(ada, unique("Lookup"));
        UUID unknown = UUID.randomUUID();
        updateClub(ada, unknown, Map.of("name", "Nope", "houseRules", "", "invitesEnabled", true)).expectStatus().isNotFound();
        setRole(ada, club, me(bea).id(), "ADMIN").expectStatus().isNotFound();
        setRole(ada, club, me(bea).id(), "OWNER").expectStatus().isBadRequest();
        updateClub(ada, club, Map.of("name", " ", "houseRules", "", "invitesEnabled", true)).expectStatus().isBadRequest();
        guest().get().uri("/api/clubs").exchange().expectStatus().isUnauthorized();
        guest().post().uri("/api/clubs").body(Map.of("name", "Guest club")).exchange().expectStatus().isUnauthorized();
    }

    @Test void concurrentDemotionsLeaveAnAdmin() throws Exception {
        RestTestClient ada = user("Ada");
        RestTestClient bea = user("Bea");
        UUID club = createClub(ada, unique("Race"));
        joinClub(ada, club, bea);
        UUID adaId = me(ada).id();
        UUID beaId = me(bea).id();
        setRole(ada, club, beaId, "ADMIN").expectStatus().isOk();
        csrf(ada);
        csrf(bea);
        try (var executor = Executors.newFixedThreadPool(2)) {
            // Each admin demotes the other; the club row lock must serialize the last-admin check.
            var first = executor.submit(() -> setRole(ada, club, beaId, "MEMBER").returnResult().getStatus());
            var second = executor.submit(() -> setRole(bea, club, adaId, "MEMBER").returnResult().getStatus());
            assertEquals(List.of(200, 403), Stream.of(first.get(), second.get()).map(HttpStatusCode::value).sorted().toList());
        }
        assertEquals(1, Stream.of(ada, bea).filter(c -> club(c, club).orElseThrow().role() == Role.ADMIN).count());
    }
}
