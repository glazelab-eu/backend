package eu.glazelab.api.controller;

import eu.glazelab.api.dto.BuddyDtos.BuddiesView;
import eu.glazelab.api.dto.BuddyDtos.BuddyGlazeView;
import eu.glazelab.api.dto.BuddyDtos.BuddyLinkPreviewView;
import eu.glazelab.api.dto.BuddyDtos.BuddyLinkView;
import eu.glazelab.api.dto.BuddyDtos.BuddyNameView;
import eu.glazelab.api.dto.BuddyDtos.BuddyRequestView;
import eu.glazelab.api.dto.BuddyDtos.BuddyView;
import eu.glazelab.api.dto.BuddyDtos.Relation;
import eu.glazelab.api.dto.StockDtos.StockView;
import eu.glazelab.api.exception.ApiErrorHandler;
import eu.glazelab.api.security.CurrentUser;
import eu.glazelab.api.security.SecureTokens;
import eu.glazelab.api.service.BuddyService;
import eu.glazelab.api.service.ClubService;
import eu.glazelab.api.service.StockService;
import eu.glazelab.api.support.ApiTestBase;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.test.web.servlet.client.RestTestClient.ResponseSpec;

import static org.junit.jupiter.api.Assertions.*;

/** Buddy links, requests and their transitions, the availability rules and the stock privacy flag (backend guide "Buddies"). */
class BuddyApiTest extends ApiTestBase {
    static String link(RestTestClient client) {
        return body(client.post().uri("/api/buddies/link").exchange().expectStatus().isOk(), BuddyLinkView.class).token();
    }

    static ResponseSpec preview(RestTestClient client, String token) {
        return client.post().uri("/api/buddies/link/preview").body(Map.of("token", token)).exchange();
    }

    static ResponseSpec requestByLink(RestTestClient client, String token) {
        return client.post().uri("/api/buddies/link/request").body(Map.of("token", token)).exchange();
    }

    static Relation asked(RestTestClient client, String token) {
        return body(requestByLink(client, token).expectStatus().isOk(), BuddyRequestView.class).relation();
    }

    static ResponseSpec requestMember(RestTestClient client, UUID club, UUID user) {
        return client.post().uri("/api/buddies/requests").body(Map.of("clubId", club, "userId", user)).exchange();
    }

    static ResponseSpec act(RestTestClient client, String action, UUID other) {
        return action.equals("remove") ? client.delete().uri("/api/buddies/{id}", other).exchange()
                : client.post().uri("/api/buddies/{id}/" + action, other).exchange();
    }

    static BuddiesView buddies(RestTestClient client) {
        return body(client.get().uri("/api/buddies").exchange().expectStatus().isOk(), BuddiesView.class);
    }

    static List<BuddyGlazeView> availability(RestTestClient client) {
        return body(client.get().uri("/api/buddies/availability").exchange().expectStatus().isOk(),
                new ParameterizedTypeReference<List<BuddyGlazeView>>() {});
    }

    static Map<UUID, List<BuddyNameView>> available(RestTestClient client) {
        var result = new HashMap<UUID, List<BuddyNameView>>();
        availability(client).forEach(glaze -> assertNull(result.put(glaze.glazeId(), glaze.buddies()), "one entry per glaze"));
        return result;
    }

    /** {@code asker} opens {@code owner}'s link and asks; {@code owner} accepts. */
    static void befriend(RestTestClient asker, RestTestClient owner) {
        assertEquals(Relation.OUTGOING, asked(asker, link(owner)));
        act(owner, "accept", me(asker).id()).expectStatus().isNoContent();
    }

    static List<UUID> ids(List<BuddyView> views) {
        return views.stream().map(BuddyView::id).toList();
    }

    private static UUID personalStock(RestTestClient client, UUID glaze, int grams) {
        return created(client.post().uri("/api/stock").body(Map.of("glazeId", glaze, "quantityG", grams, "ownerType", "personal")).exchange());
    }

    private static ResponseSpec keepPrivate(RestTestClient client, UUID stock, boolean keep) {
        return client.patch().uri("/api/stock/{id}", stock).body(Map.of("keepPrivate", keep)).exchange();
    }

    private static List<StockView> stock(RestTestClient client) {
        return body(client.get().uri("/api/stock").exchange().expectStatus().isOk(), new ParameterizedTypeReference<List<StockView>>() {});
    }

    private int pairs(UUID one, UUID other) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM buddy WHERE (user_a = ? AND user_b = ?) OR (user_a = ? AND user_b = ?)",
                Integer.class, one, other, other, one);
    }

    @Test void aLinkRequestWaitsForTheOwnerToAccept() {
        var ada = user("Ada"); var ben = user("Ben");
        UUID adaId = me(ada).id(), benId = me(ben).id();
        assertFalse(buddies(ben).hasLink());
        String token = link(ben);
        assertEquals(43, token.length());
        assertTrue(buddies(ben).hasLink());
        // Only the hash is stored.
        assertEquals(SecureTokens.hash(token), jdbc.queryForObject("SELECT token_hash FROM buddy_link WHERE user_id = ?", String.class, benId));

        var preview = body(preview(ada, token).expectStatus().isOk(), BuddyLinkPreviewView.class);
        assertEquals(new BuddyLinkPreviewView(me(ben).displayName(), Relation.NONE, null), preview);
        assertEquals(Relation.SELF, body(preview(ben, token).expectStatus().isOk(), BuddyLinkPreviewView.class).relation());
        // A preview writes nothing.
        assertEquals(0, pairs(adaId, benId));

        assertEquals(Relation.OUTGOING, asked(ada, token));
        // Asking again changes nothing.
        assertEquals(Relation.OUTGOING, asked(ada, token));
        assertEquals(1, pairs(adaId, benId));
        assertEquals(Relation.OUTGOING, body(preview(ada, token).expectStatus().isOk(), BuddyLinkPreviewView.class).relation());
        var adas = buddies(ada); var bens = buddies(ben);
        assertEquals(List.of(benId), ids(adas.outgoing()));
        assertTrue(adas.incoming().isEmpty() && adas.buddies().isEmpty());
        assertEquals(List.of(benId), ids(adas.outgoing()));
        assertEquals(new BuddyView(adaId, me(ada).displayName(), bens.incoming().getFirst().since(), null), bens.incoming().getFirst());
        assertTrue(bens.outgoing().isEmpty() && bens.buddies().isEmpty());
        assertTrue(available(ada).isEmpty());

        act(ben, "accept", adaId).expectStatus().isNoContent();
        // A second accept (another device) is a no-op.
        act(ben, "accept", adaId).expectStatus().isNoContent();
        assertEquals(List.of(benId), ids(buddies(ada).buddies()));
        assertEquals(List.of(adaId), ids(buddies(ben).buddies()));
        assertTrue(buddies(ben).incoming().isEmpty() && buddies(ada).outgoing().isEmpty());
        assertNotNull(buddies(ben).buddies().getFirst().since());
        assertEquals(Relation.BUDDIES, body(preview(ada, token).expectStatus().isOk(), BuddyLinkPreviewView.class).relation());
        assertEquals(Relation.BUDDIES, asked(ada, token));
        assertEquals("ACCEPTED", jdbc.queryForObject("SELECT status FROM buddy WHERE user_a = ? OR user_b = ?", String.class, adaId, adaId));
    }

    @Test void noOneIsTheirOwnBuddy() {
        var ada = user("Ada");
        UUID adaId = me(ada).id();
        UUID club = createClub(ada, unique("Self club"));
        assertError(requestByLink(ada, link(ada)), 400, BuddyService.NOT_YOURSELF);
        assertError(requestMember(ada, club, adaId), 400, BuddyService.NOT_YOURSELF);
        assertError(act(ada, "accept", adaId), 404, BuddyService.REQUEST_NOT_FOUND);
        for (String action : List.of("decline", "cancel", "remove")) act(ada, action, adaId).expectStatus().isNoContent();
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM buddy WHERE user_a = ? OR user_b = ?", Integer.class, adaId, adaId));
    }

    @Test void replacedAndUnknownLinksAnswerAlike() {
        var ada = user("Ada"); var ben = user("Ben");
        String first = link(ben);
        String second = link(ben);
        assertNotEquals(first, second);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM buddy_link WHERE user_id = ?", Integer.class, me(ben).id()));
        String unknown = SecureTokens.generate();
        for (String token : List.of(first, unknown)) {
            String previewed = preview(ada, token).expectStatus().isNotFound().expectBody(String.class).returnResult().getResponseBody();
            assertEquals("{\"error\":\"" + BuddyService.INVALID_LINK + "\"}", previewed);
            String requested = requestByLink(ada, token).expectStatus().isNotFound().expectBody(String.class).returnResult().getResponseBody();
            assertEquals(previewed, requested);
        }
        // A malformed token never reaches a lookup.
        preview(ada, "short").expectStatus().isBadRequest();
        requestByLink(ada, first + "x").expectStatus().isBadRequest();
        assertEquals(0, pairs(me(ada).id(), me(ben).id()));
        assertEquals(Relation.OUTGOING, asked(ada, second));
    }

    @Test void crossedRequestsMakeBuddies() {
        var ada = user("Ada"); var ben = user("Ben");
        assertEquals(Relation.OUTGOING, asked(ada, link(ben)));
        assertEquals(Relation.INCOMING, body(preview(ben, link(ada)).expectStatus().isOk(), BuddyLinkPreviewView.class).relation());
        assertEquals(Relation.BUDDIES, asked(ben, link(ada)));
        assertEquals(List.of(me(ben).id()), ids(buddies(ada).buddies()));
        assertTrue(buddies(ada).outgoing().isEmpty() && buddies(ben).incoming().isEmpty());

        // The same through a shared club.
        var cleo = user("Cleo"); var dan = user("Dan");
        UUID club = createClub(cleo, unique("Crossed club"));
        joinClub(cleo, club, dan);
        assertEquals(Relation.OUTGOING, body(requestMember(cleo, club, me(dan).id()).expectStatus().isOk(), BuddyRequestView.class).relation());
        assertEquals(Relation.BUDDIES, body(requestMember(dan, club, me(cleo).id()).expectStatus().isOk(), BuddyRequestView.class).relation());
        assertEquals(List.of(me(dan).id()), ids(buddies(cleo).buddies()));
    }

    @Test void onlyTheAskedAccountAccepts() {
        var ada = user("Ada"); var ben = user("Ben");
        UUID benId = me(ben).id();
        asked(ada, link(ben));
        assertError(act(ada, "accept", benId), 404, BuddyService.REQUEST_NOT_FOUND);
        assertError(act(ada, "accept", UUID.randomUUID()), 404, BuddyService.REQUEST_NOT_FOUND);
        assertError(act(user("Cleo"), "accept", me(ada).id()), 404, BuddyService.REQUEST_NOT_FOUND);
        assertEquals(List.of(benId), ids(buddies(ada).outgoing()));
        assertTrue(buddies(ada).buddies().isEmpty());
    }

    @Test void declineIsSilentAndTheAskerMayAskAgain() {
        var ada = user("Ada"); var ben = user("Ben");
        UUID adaId = me(ada).id(), benId = me(ben).id();
        String token = link(ben);
        asked(ada, token);
        // Declining is for the asked account; for the asker it is their own request.
        assertError(act(ada, "decline", benId), 409, ApiErrorHandler.CHANGED_MEANWHILE);
        act(ben, "decline", adaId).expectStatus().isNoContent();
        act(ben, "decline", adaId).expectStatus().isNoContent();
        assertEquals(0, pairs(adaId, benId));
        assertTrue(buddies(ada).outgoing().isEmpty() && buddies(ben).incoming().isEmpty());
        assertEquals(Relation.OUTGOING, asked(ada, token));
    }

    @Test void theAskerCancelsTheirOwnRequestOnly() {
        var ada = user("Ada"); var ben = user("Ben");
        UUID adaId = me(ada).id(), benId = me(ben).id();
        asked(ada, link(ben));
        assertError(act(ben, "cancel", adaId), 409, ApiErrorHandler.CHANGED_MEANWHILE);
        assertError(act(ada, "remove", benId), 409, ApiErrorHandler.CHANGED_MEANWHILE);
        act(ada, "cancel", benId).expectStatus().isNoContent();
        assertEquals(0, pairs(adaId, benId));
        act(ada, "cancel", benId).expectStatus().isNoContent();
        assertError(act(ben, "accept", adaId), 404, BuddyService.REQUEST_NOT_FOUND);
    }

    @Test void eitherBuddyRemovesAndRemovalShowsNothingMore() {
        var ada = user("Ada"); var ben = user("Ben");
        UUID adaId = me(ada).id(), benId = me(ben).id();
        UUID glaze = addGlaze(ben, unique("Removal"), "Blue", "R-1");
        personalStock(ben, glaze, 100);
        befriend(ada, ben);
        assertEquals(List.of(new BuddyNameView(benId, me(ben).displayName())), available(ada).get(glaze));
        // Neither a decline nor a cancel ends an accepted pair.
        assertError(act(ada, "decline", benId), 409, ApiErrorHandler.CHANGED_MEANWHILE);
        assertError(act(ben, "cancel", adaId), 409, ApiErrorHandler.CHANGED_MEANWHILE);
        act(ada, "remove", benId).expectStatus().isNoContent();
        act(ben, "remove", adaId).expectStatus().isNoContent();
        assertEquals(0, pairs(adaId, benId));
        assertTrue(available(ada).isEmpty());
        assertTrue(buddies(ben).buddies().isEmpty());
        // Asking again starts over: a new request, not a buddy.
        assertEquals(Relation.OUTGOING, asked(ada, link(ben)));
        assertTrue(available(ada).isEmpty());
    }

    @Test void clubMembersAskEachOtherOnlyInASharedClub() {
        var ada = user("Ada"); var ben = user("Ben"); var cleo = user("Cleo"); var dan = user("Dan");
        UUID adaId = me(ada).id(), benId = me(ben).id(), cleoId = me(cleo).id(), danId = me(dan).id();
        UUID club = createClub(ada, unique("Studio"));
        UUID other = createClub(dan, unique("Other studio"));
        joinClub(ada, club, ben);
        joinClub(dan, other, ada);
        // A non-member gets 403 for a real and an unknown club, whatever the target.
        assertError(requestMember(cleo, club, adaId), 403, CurrentUser.CLUB_MEMBERSHIP_REQUIRED);
        assertError(requestMember(cleo, UUID.randomUUID(), adaId), 403, CurrentUser.CLUB_MEMBERSHIP_REQUIRED);
        assertError(requestMember(cleo, club, UUID.randomUUID()), 403, CurrentUser.CLUB_MEMBERSHIP_REQUIRED);
        // A member asking someone who is not in that club: an outsider, an unknown account, a member of another club.
        assertError(requestMember(ada, club, cleoId), 404, ClubService.MEMBER_NOT_FOUND);
        assertError(requestMember(ada, club, UUID.randomUUID()), 404, ClubService.MEMBER_NOT_FOUND);
        assertError(requestMember(ben, club, danId), 404, ClubService.MEMBER_NOT_FOUND);
        assertError(requestMember(ada, club, danId), 404, ClubService.MEMBER_NOT_FOUND);
        ada.post().uri("/api/buddies/requests").body(Map.of("clubId", club)).exchange().expectStatus().isBadRequest();
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM buddy WHERE user_a IN (?, ?) OR user_b IN (?, ?)", Integer.class, cleoId, danId, cleoId, danId));

        assertEquals(Relation.OUTGOING, body(requestMember(ben, club, adaId).expectStatus().isOk(), BuddyRequestView.class).relation());
        assertEquals(Relation.OUTGOING, body(requestMember(ben, club, adaId).expectStatus().isOk(), BuddyRequestView.class).relation());
        assertEquals(List.of(benId), ids(buddies(ada).incoming()));
        assertEquals(Relation.OUTGOING, body(requestMember(ada, other, danId).expectStatus().isOk(), BuddyRequestView.class).relation());
        // After leaving, the club no longer connects them; an existing request stays for the asked account to decide.
        ben.delete().uri("/api/clubs/{id}/members/{userId}", club, benId).exchange().expectStatus().isNoContent();
        assertError(requestMember(ben, club, adaId), 403, CurrentUser.CLUB_MEMBERSHIP_REQUIRED);
        assertError(requestMember(ada, club, benId), 404, ClubService.MEMBER_NOT_FOUND);
        assertEquals(List.of(benId), ids(buddies(ada).incoming()));
    }

    @Test void aPendingAccountIsNoMemberToAsk() {
        var ada = user("Ada");
        UUID club = createClub(ada, unique("Pending club"));
        String email = email("Pending");
        register(guest(), email, "Pending").expectStatus().isOk();
        UUID pending = jdbc.queryForObject("SELECT id FROM app_user WHERE email = ?", UUID.class, email);
        // Only a bug could give it a membership (it cannot sign in to join).
        jdbc.update("INSERT INTO membership (club_id, user_id, role) VALUES (?, ?, 'MEMBER')", club, pending);
        assertError(requestMember(ada, club, pending), 404, ClubService.MEMBER_NOT_FOUND);
        assertEquals(0, pairs(me(ada).id(), pending));
    }

    @Test void guestsAndForgedRequestsAreRefused() {
        var guest = guest();
        UUID someone = UUID.randomUUID();
        String token = SecureTokens.generate();
        for (var call : List.<ResponseSpec>of(guest.get().uri("/api/buddies").exchange(),
                guest.get().uri("/api/buddies/availability").exchange(),
                guest.post().uri("/api/buddies/link").exchange(),
                preview(guest, token), requestByLink(guest, token), requestMember(guest, someone, someone),
                act(guest, "accept", someone), act(guest, "decline", someone), act(guest, "cancel", someone), act(guest, "remove", someone),
                guest.patch().uri("/api/stock/{id}", someone).body(Map.of("keepPrivate", true)).exchange()))
            assertError(call, 401, CurrentUser.SIGN_IN_REQUIRED);
        var ada = user("Ada");
        String own = link(ada);
        var forged = guestWithoutCsrf();
        login(forged, csrf(forged), me(ada).email(), PASSWORD).expectStatus().isOk();
        forged.post().uri("/api/buddies/link").exchange().expectStatus().isForbidden();
        forged.post().uri("/api/buddies/link/request").body(Map.of("token", own)).exchange().expectStatus().isForbidden();
        assertEquals(Relation.SELF, body(preview(ada, own).expectStatus().isOk(), BuddyLinkPreviewView.class).relation());
    }

    @Test void buddiesSeeOnlyGlazesOnTheOtherPersonalShelfThatAreNotUsedUpOrPrivate() {
        var owner = user("Owner"); var buddy = user("Buddy"); var stranger = user("Stranger");
        var asker = user("Asker"); var asked = user("Asked"); var buddyOfBuddy = user("Friend");
        UUID ownerId = me(owner).id(), buddyId = me(buddy).id();
        String m = unique("Shelf");
        UUID onShelf = addGlaze(owner, m, "On shelf", "A-1"), usedUp = addGlaze(owner, m, "Used up", "A-2"),
                hidden = addGlaze(owner, m, "Hidden", "A-3"), clubOnly = addGlaze(owner, m, "Club only", "A-4"),
                buddysOwn = addGlaze(owner, m, "Buddy's own", "A-5");
        personalStock(owner, onShelf, 120);
        personalStock(owner, usedUp, 0);
        UUID hiddenEntry = personalStock(owner, hidden, 50);
        keepPrivate(owner, hiddenEntry, true).expectStatus().isNoContent();
        // Club stock is never shared with buddies, not even with a buddy in the same club.
        UUID club = createClub(owner, unique("Shelf club"));
        joinClub(owner, club, buddy);
        owner.post().uri("/api/stock").body(Map.of("glazeId", clubOnly, "quantityG", 500, "ownerType", "club", "clubId", club)).exchange().expectStatus().isOk();
        personalStock(buddy, buddysOwn, 10);
        befriend(buddy, owner);
        assertEquals(Relation.OUTGOING, asked(asker, link(owner)));
        assertEquals(Relation.OUTGOING, asked(owner, link(asked)));
        befriend(buddyOfBuddy, buddy);

        String raw = buddy.get().uri("/api/buddies/availability").exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertFalse(raw.contains("quantity") || raw.contains("jar") || raw.contains("email"), raw);
        var seen = available(buddy);
        assertEquals(Map.of(onShelf, List.of(new BuddyNameView(ownerId, me(owner).displayName()))), seen);
        // Mutual: the owner sees the buddy's shelf.
        assertEquals(Map.of(buddysOwn, List.of(new BuddyNameView(buddyId, me(buddy).displayName()))), available(owner));
        // A stranger, both sides of a pending request and a buddy of the buddy see nothing of the owner.
        assertTrue(available(stranger).isEmpty());
        assertTrue(available(asker).isEmpty());
        assertTrue(available(asked).isEmpty());
        assertEquals(Map.of(buddysOwn, List.of(new BuddyNameView(buddyId, me(buddy).displayName()))), available(buddyOfBuddy));

        // Showing it again, refilling and using up change what the buddy sees at once.
        keepPrivate(owner, hiddenEntry, false).expectStatus().isNoContent();
        personalStock(owner, usedUp, 30);
        personalStock(owner, onShelf, 0);
        assertEquals(java.util.Set.of(hidden, usedUp), available(buddy).keySet());
    }

    @Test void aGlazeKeptPrivateOnThePersonalShelfStaysHiddenWhenTheSameGlazeIsOnASharedClubShelf() {
        var owner = user("Owner"); var buddy = user("Buddy");
        UUID glaze = addGlaze(owner, unique("Both shelves"), "Ash", "B-1");
        UUID entry = personalStock(owner, glaze, 200);
        keepPrivate(owner, entry, true).expectStatus().isNoContent();
        UUID club = createClub(owner, unique("Both club"));
        joinClub(owner, club, buddy);
        owner.post().uri("/api/stock").body(Map.of("glazeId", glaze, "quantityG", 300, "ownerType", "club", "clubId", club))
                .exchange().expectStatus().isOk();
        befriend(buddy, owner);
        assertTrue(available(buddy).isEmpty());
        // The buddy still sees the club entry as a member; only the buddy channel stays closed.
        assertTrue(stock(buddy).stream().anyMatch(s -> glaze.equals(s.glazeId()) && club.equals(s.clubId()) && !s.keepPrivate()));
    }

    @Test void aLinkPreviewNamesTheOwnerButNeverTheirIdOrEmail() {
        var ada = user("Ada"); var ben = user("Ben");
        String token = link(ben);
        String raw = preview(ada, token).expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        assertFalse(raw.contains(me(ben).id().toString()) || raw.contains(me(ben).email()), raw);
        assertEquals(new BuddyLinkPreviewView(me(ben).displayName(), Relation.NONE, null), body(preview(ada, token).expectStatus().isOk(), BuddyLinkPreviewView.class));
    }

    @Test void severalBuddiesWithOneGlazeAreListedByName() {
        var me = user("Viewer"); var zoe = user("Zoe"); var amy = user("Amy");
        UUID glaze = addGlaze(zoe, unique("Shared"), "Celadon", "S-1");
        personalStock(zoe, glaze, 10);
        personalStock(amy, glaze, 10);
        befriend(me, zoe);
        befriend(me, amy);
        assertEquals(List.of(me(amy).displayName(), me(zoe).displayName()),
                available(me).get(glaze).stream().map(BuddyNameView::displayName).toList());
    }

    @Test void onlyTheOwnerKeepsAPersonalEntryPrivate() {
        var owner = user("Owner"); var other = user("Other"); var outsider = user("Outsider");
        UUID glaze = addGlaze(owner, unique("Private"), "Shino", "P-1");
        UUID entry = personalStock(owner, glaze, 80);
        assertFalse(stock(owner).getFirst().keepPrivate());
        assertError(keepPrivate(other, entry, true), 404, "Unknown stock entry");
        assertError(keepPrivate(owner, UUID.randomUUID(), true), 404, "Unknown stock entry");
        owner.patch().uri("/api/stock/{id}", entry).body(Map.of()).exchange().expectStatus().isBadRequest();
        keepPrivate(owner, entry, true).expectStatus().isNoContent();
        assertTrue(stock(owner).getFirst().keepPrivate());
        // A save keeps the flag.
        personalStock(owner, glaze, 40);
        assertTrue(stock(owner).getFirst().keepPrivate());

        UUID club = createClub(owner, unique("Private club"));
        joinClub(owner, club, other);
        UUID clubEntry = created(other.post().uri("/api/stock").body(Map.of("glazeId", glaze, "quantityG", 5, "ownerType", "club", "clubId", club)).exchange());
        assertError(keepPrivate(owner, clubEntry, true), 400, StockService.ONLY_PERSONAL_PRIVATE);
        assertError(keepPrivate(other, clubEntry, false), 400, StockService.ONLY_PERSONAL_PRIVATE);
        assertError(keepPrivate(outsider, clubEntry, true), 404, "Unknown stock entry");
        assertFalse(jdbc.queryForObject("SELECT keep_private FROM stock WHERE id = ?", Boolean.class, clubEntry));
        // The database refuses it as well.
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE stock SET keep_private = TRUE WHERE id = ?", clubEntry));
    }

    @Test void deletingAnAccountRemovesItsLinkRequestsAndBuddies() {
        var leaving = user("Leaving"); var buddy = user("Buddy"); var asked = user("Asked"); var asker = user("Asker");
        UUID leavingId = me(leaving).id();
        UUID glaze = addGlaze(leaving, unique("Gone"), "Tenmoku", "G-1");
        personalStock(leaving, glaze, 100);
        befriend(buddy, leaving);
        String token = link(leaving);
        asked(leaving, link(asked));
        asked(asker, token);
        assertEquals(Map.of(glaze, List.of(new BuddyNameView(leavingId, me(leaving).displayName()))), available(buddy));

        leaving.post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange().expectStatus().isNoContent();
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM buddy WHERE user_a = ? OR user_b = ? OR requested_by = ?", Integer.class, leavingId, leavingId, leavingId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM buddy_link WHERE user_id = ?", Integer.class, leavingId));
        assertTrue(available(buddy).isEmpty());
        for (var other : List.of(buddy, asked, asker)) {
            var view = buddies(other);
            assertTrue(view.buddies().isEmpty() && view.incoming().isEmpty() && view.outgoing().isEmpty());
        }
        assertError(preview(asker, token), 404, BuddyService.INVALID_LINK);
        assertError(act(buddy, "accept", leavingId), 404, BuddyService.REQUEST_NOT_FOUND);
    }
}
