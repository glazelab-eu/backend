package eu.glazelab.api.controller;

import eu.glazelab.api.dto.ClubDtos.WishlistEntryView;
import eu.glazelab.api.dto.ClubDtos.WishlistView;
import eu.glazelab.api.model.Text;
import eu.glazelab.api.model.WishlistEntry;
import eu.glazelab.api.model.WishlistLinks;
import eu.glazelab.api.security.CurrentUser;
import eu.glazelab.api.service.GlazeService;
import eu.glazelab.api.service.WishlistService;
import eu.glazelab.api.support.ApiTestBase;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.test.web.servlet.client.RestTestClient.ResponseSpec;

import static org.junit.jupiter.api.Assertions.*;

class ClubWishlistApiTest extends ApiTestBase {
    private record Club(UUID id, RestTestClient admin, RestTestClient member, RestTestClient other) {}

    private Club club() {
        var admin = user("WishAdmin");
        var member = user("WishMember");
        var other = user("WishOther");
        UUID id = createClub(admin, unique("Wish kiln"));
        joinClub(admin, id, member);
        joinClub(admin, id, other);
        return new Club(id, admin, member, other);
    }

    static WishlistView wishlist(RestTestClient client, UUID club) {
        return body(client.get().uri("/api/clubs/{id}/wishlist", club).exchange().expectStatus().isOk(), WishlistView.class);
    }

    static ResponseSpec add(RestTestClient client, UUID club, Map<String, Object> body) {
        return client.post().uri("/api/clubs/{id}/wishlist", club).body(body).exchange();
    }

    static WishlistEntryView addGlazeEntry(RestTestClient client, UUID club, UUID glaze) {
        return body(add(client, club, Map.of("type", "GLAZE", "glazeId", glaze)).expectStatus().isOk(), WishlistEntryView.class);
    }

    static WishlistEntryView addItem(RestTestClient client, UUID club, String name, String link) {
        return body(add(client, club, Map.of("type", "ITEM", "name", name, "link", link)).expectStatus().isOk(), WishlistEntryView.class);
    }

    private static ResponseSpec acquired(RestTestClient client, UUID club, UUID entry, boolean acquired) {
        return client.put().uri("/api/clubs/{id}/wishlist/{entry}/acquired", club, entry).body(Map.of("acquired", acquired)).exchange();
    }

    private static ResponseSpec remove(RestTestClient client, UUID club, UUID entry) {
        return client.delete().uri("/api/clubs/{id}/wishlist/{entry}", club, entry).exchange();
    }

    private static WishlistEntryView entry(RestTestClient client, UUID club, UUID entry) {
        return wishlist(client, club).entries().stream().filter(e -> e.id().equals(entry)).findFirst().orElseThrow();
    }

    @Test void aMemberAddsAGlazeAndEveryMemberSeesItWithItsAdder() {
        var c = club();
        UUID glaze = addGlaze(c.admin(), unique("Wish maker"), "Blue Rutile", "pc 20");
        var added = body(add(c.member(), c.id(), Map.of("type", "GLAZE", "glazeId", glaze, "note", "  For the beginners' course  "))
                .expectStatus().isOk(), WishlistEntryView.class);
        assertEquals(WishlistEntry.Type.GLAZE, added.type());
        assertEquals(glaze, added.glazeId());
        assertEquals("PC-20", added.code());
        assertEquals("Blue Rutile", added.name());
        assertNull(added.link());
        assertEquals("For the beginners' course", added.note());
        assertEquals(me(c.member()).id(), added.addedById());
        assertNotNull(added.addedAt());
        assertNull(added.acquiredAt());
        assertTrue(added.canManage());
        var seen = wishlist(c.other(), c.id());
        assertEquals(WishlistService.OPEN_LIMIT, seen.openLimit());
        var forOther = seen.entries().stream().filter(e -> e.id().equals(added.id())).findFirst().orElseThrow();
        assertEquals(me(c.member()).displayName(), forOther.addedBy());
        assertFalse(forOther.canManage());
        assertTrue(entry(c.admin(), c.id(), added.id()).canManage());
    }

    @Test void aGlazeThatIsStillWantedCannotBeAddedTwiceButAnAcquiredOneCan() {
        var c = club();
        UUID glaze = addGlaze(c.admin(), unique("Wish maker"), "Blue", "1");
        var first = addGlazeEntry(c.member(), c.id(), glaze);
        assertError(add(c.other(), c.id(), Map.of("type", "GLAZE", "glazeId", glaze)), 409, WishlistService.ALREADY_WISHED);
        acquired(c.admin(), c.id(), first.id(), true).expectStatus().isOk();
        var second = addGlazeEntry(c.other(), c.id(), glaze);
        // Wanted again while the second entry is open: refused, nothing changed.
        assertError(acquired(c.admin(), c.id(), first.id(), false), 409, WishlistService.ALREADY_WISHED);
        assertNotNull(entry(c.admin(), c.id(), first.id()).acquiredAt());
        // Another club wants the same glaze independently.
        UUID otherClub = createClub(c.other(), unique("Other kiln"));
        addGlazeEntry(c.other(), otherClub, glaze);
        assertNotEquals(first.id(), second.id());
    }

    @Test void anItemNeedsANameAndAStrictWebLink() {
        var c = club();
        var item = addItem(c.member(), c.id(), "  Kiln shelf 40 x 40 cm ", " https://shop.example.test/kiln/shelf?size=40#specs ");
        assertEquals(WishlistEntry.Type.ITEM, item.type());
        assertEquals("Kiln shelf 40 x 40 cm", item.name());
        assertEquals("https://shop.example.test/kiln/shelf?size=40#specs", item.link());
        assertNull(item.glazeId());
        for (String link : new String[]{"javascript:alert(1)", "data:text/html,<b>x</b>", "ftp://example.test/file",
                "https://user:pass@example.test/", "https://user@example.test/", "https://exa mple.test/", "https://example.test/a b",
                "example.test", "https://example.test/" + "a".repeat(WishlistLinks.MAX_LENGTH)})
            assertError(add(c.member(), c.id(), Map.of("type", "ITEM", "name", "Brush", "link", link)), 400, WishlistLinks.INVALID);
        assertError(add(c.member(), c.id(), Map.of("type", "ITEM", "name", "Brush", "link", "https://example.test/\na")), 400, Text.INVALID_TEXT);
        assertError(add(c.member(), c.id(), Map.of("type", "ITEM", "name", "Brush")), 400, WishlistLinks.REQUIRED);
        assertError(add(c.member(), c.id(), Map.of("type", "ITEM", "name", "Brush", "link", "  ")), 400, WishlistLinks.REQUIRED);
        assertError(add(c.member(), c.id(), Map.of("type", "ITEM", "name", "  ", "link", "https://example.test/")), 400, WishlistService.NAME_REQUIRED);
        assertError(add(c.member(), c.id(), Map.of("type", "ITEM", "link", "https://example.test/")), 400, WishlistService.NAME_REQUIRED);
        assertError(add(c.member(), c.id(), Map.of("type", "ITEM", "name", "Tab\tname", "link", "https://example.test/")), 400, Text.INVALID_TEXT);
        add(c.member(), c.id(), Map.of("type", "ITEM", "name", "x".repeat(121), "link", "https://example.test/")).expectStatus().isBadRequest();
        add(c.member(), c.id(), Map.of("type", "ITEM", "name", "Brush", "link", "https://example.test/", "note", "n".repeat(201)))
                .expectStatus().isBadRequest();
        add(c.member(), c.id(), Map.of("type", "BOOK", "name", "Brush", "link", "https://example.test/")).expectStatus().isBadRequest();
        add(c.member(), c.id(), Map.of("name", "Brush", "link", "https://example.test/")).expectStatus().isBadRequest();
        // A glaze entry takes a glaze and nothing of an item; an item takes no glaze.
        UUID glaze = addGlaze(c.admin(), unique("Wish maker"), "Blue", "1");
        assertError(add(c.member(), c.id(), Map.of("type", "GLAZE", "glazeId", glaze, "link", "https://example.test/")), 400,
                WishlistService.GLAZE_FIELDS);
        assertError(add(c.member(), c.id(), Map.of("type", "GLAZE", "glazeId", glaze, "name", "Blue")), 400, WishlistService.GLAZE_FIELDS);
        assertError(add(c.member(), c.id(), Map.of("type", "GLAZE")), 400, WishlistService.GLAZE_REQUIRED);
        assertError(add(c.member(), c.id(), Map.of("type", "ITEM", "glazeId", glaze, "name", "Blue", "link", "https://example.test/")), 400,
                WishlistService.ITEM_FIELDS);
        assertError(add(c.member(), c.id(), Map.of("type", "GLAZE", "glazeId", UUID.randomUUID())), 404, GlazeService.UNKNOWN_GLAZE);
        assertEquals(1, wishlist(c.member(), c.id()).entries().size());
    }

    @Test void theAdderOrAClubAdminRemovesAnEntryAndOtherMembersCannot() {
        var c = club();
        var mine = addItem(c.member(), c.id(), "Sponges", "https://example.test/sponges");
        var other = addItem(c.member(), c.id(), "Banding wheel", "https://example.test/wheel");
        assertError(remove(c.other(), c.id(), mine.id()), 403, WishlistService.MANAGE_RIGHTS_REQUIRED);
        remove(c.member(), c.id(), mine.id()).expectStatus().isNoContent();
        assertError(remove(c.member(), c.id(), mine.id()), 404, WishlistService.ENTRY_NOT_FOUND);
        remove(c.admin(), c.id(), other.id()).expectStatus().isNoContent();
        assertTrue(wishlist(c.member(), c.id()).entries().isEmpty());
        // An entry of another club is unknown here, even to that club's admin.
        UUID otherClub = createClub(c.admin(), unique("Second kiln"));
        var elsewhere = addItem(c.admin(), otherClub, "Gloves", "https://example.test/gloves");
        assertError(remove(c.admin(), c.id(), elsewhere.id()), 404, WishlistService.ENTRY_NOT_FOUND);
        assertError(acquired(c.admin(), c.id(), elsewhere.id(), true), 404, WishlistService.ENTRY_NOT_FOUND);
        assertError(remove(c.admin(), c.id(), UUID.randomUUID()), 404, WishlistService.ENTRY_NOT_FOUND);
    }

    @Test void theAdderOrAClubAdminMarksAnEntryAcquiredAndWantedAgain() {
        var c = club();
        var item = addItem(c.member(), c.id(), "Glaze scale", "https://example.test/scale");
        assertError(acquired(c.other(), c.id(), item.id(), true), 403, WishlistService.MANAGE_RIGHTS_REQUIRED);
        var marked = body(acquired(c.admin(), c.id(), item.id(), true).expectStatus().isOk(), WishlistEntryView.class);
        assertNotNull(marked.acquiredAt());
        assertTrue(marked.canManage());
        // Marking it again keeps the first time.
        assertEquals(marked.acquiredAt(), body(acquired(c.member(), c.id(), item.id(), true).expectStatus().isOk(),
                WishlistEntryView.class).acquiredAt());
        var wanted = body(acquired(c.member(), c.id(), item.id(), false).expectStatus().isOk(), WishlistEntryView.class);
        assertNull(wanted.acquiredAt());
        assertNull(body(acquired(c.member(), c.id(), item.id(), false).expectStatus().isOk(), WishlistEntryView.class).acquiredAt());
        c.admin().put().uri("/api/clubs/{id}/wishlist/{entry}/acquired", c.id(), item.id()).body(Map.of()).exchange().expectStatus().isBadRequest();
    }

    @Test void aWishlistHoldsAtMostOneHundredOpenEntries() {
        var c = club();
        UUID first = null;
        for (int i = 0; i < WishlistService.OPEN_LIMIT; i++) {
            var entry = addItem(c.member(), c.id(), "Item " + i, "https://example.test/" + i);
            if (first == null) first = entry.id();
        }
        assertError(add(c.other(), c.id(), Map.of("type", "ITEM", "name", "One more", "link", "https://example.test/more")), 409,
                WishlistService.FULL);
        acquired(c.admin(), c.id(), first, true).expectStatus().isOk();
        var last = addItem(c.other(), c.id(), "One more", "https://example.test/more");
        // Wanted again would pass the limit.
        assertError(acquired(c.admin(), c.id(), first, false), 409, WishlistService.FULL);
        remove(c.other(), c.id(), last.id()).expectStatus().isNoContent();
        acquired(c.admin(), c.id(), first, false).expectStatus().isOk();
        assertEquals(WishlistService.OPEN_LIMIT, wishlist(c.admin(), c.id()).entries().size());
    }

    @Test void outsidersGetTheMemberListsAnswerAndAMemberWhoLeftLosesAccessAndTheirName() {
        var c = club();
        var outsider = user("WishOutsider");
        var entry = addItem(c.member(), c.id(), "Calipers", "https://example.test/calipers");
        String refused = "403 {\"error\":\"" + CurrentUser.CLUB_MEMBERSHIP_REQUIRED + "\"}";
        UUID unknown = UUID.randomUUID();
        Map<String, java.util.function.BiFunction<RestTestClient, UUID, ResponseSpec>> calls = Map.of(
                "list", (client, id) -> client.get().uri("/api/clubs/{id}/wishlist", id).exchange(),
                "add", (client, id) -> add(client, id, Map.of("type", "ITEM", "name", "Mine", "link", "https://example.test/")),
                "remove", (client, id) -> remove(client, id, entry.id()),
                "acquired", (client, id) -> acquired(client, id, entry.id(), true));
        calls.forEach((what, call) -> {
            assertEquals(refused, statusAndBody(call.apply(outsider, c.id())), what);
            assertEquals(statusAndBody(call.apply(outsider, unknown)), statusAndBody(call.apply(outsider, c.id())), what);
        });
        guest().get().uri("/api/clubs/{id}/wishlist", c.id()).exchange().expectStatus().isUnauthorized();
        // The adder leaves: the entry stays with the club without their name, and they lose access and their rights.
        c.member().delete().uri("/api/clubs/{id}/members/{user}", c.id(), me(c.member()).id()).exchange().expectStatus().isNoContent();
        calls.forEach((what, call) -> assertEquals(refused, statusAndBody(call.apply(c.member(), c.id())), what));
        var kept = entry(c.admin(), c.id(), entry.id());
        assertNull(kept.addedById());
        assertNull(kept.addedBy());
        assertFalse(entry(c.other(), c.id(), entry.id()).canManage());
        assertTrue(kept.canManage());
        // Rejoining gives neither the name nor the right back.
        joinClub(c.admin(), c.id(), c.member());
        assertFalse(entry(c.member(), c.id(), entry.id()).canManage());
    }

    @Test void accountDeletionKeepsTheEntriesWithoutTheirAdderAndAClubDeletionTakesThem() {
        var c = club();
        UUID memberId = me(c.member()).id();
        var entry = addItem(c.member(), c.id(), "Wax resist", "https://example.test/wax");
        c.member().post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange().expectStatus().isNoContent();
        var kept = entry(c.admin(), c.id(), entry.id());
        assertNull(kept.addedById());
        assertNull(kept.addedBy());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM club_wishlist WHERE added_by = ?", Integer.class, memberId));
        // A sole member's club goes with their account, wishlist included.
        var loner = user("WishLoner");
        UUID own = createClub(loner, unique("Own kiln"));
        var gone = addItem(loner, own, "Kiln wash", "https://example.test/wash");
        loner.post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange().expectStatus().isNoContent();
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM club_wishlist WHERE id = ?", Integer.class, gone.id()));
    }

    @Test void aBannedMemberLosesAccessAndTheirEntriesStayAnonymous() {
        var c = club();
        var entry = addItem(c.member(), c.id(), "Hake brush", "https://example.test/hake");
        UUID memberId = me(c.member()).id();
        BanApiTest.ban(siteAdmin(), memberId, "Spam", null).expectStatus().is2xxSuccessful();
        c.member().get().uri("/api/clubs/{id}/wishlist", c.id()).exchange().expectStatus().isUnauthorized();
        assertNull(entry(c.admin(), c.id(), entry.id()).addedById());
    }

    @Test void aGlazeRemovedFromTheCatalogueLeavesTheWishlist() {
        var c = club();
        UUID glaze = addGlaze(c.admin(), unique("Wish maker"), "Gone", "G-1");
        var entry = addGlazeEntry(c.member(), c.id(), glaze);
        siteAdmin().delete().uri("/api/glazes/{id}", glaze).exchange().expectStatus().is2xxSuccessful();
        assertTrue(wishlist(c.member(), c.id()).entries().stream().noneMatch(e -> e.id().equals(entry.id())));
    }

    @Test void wishlistWritesNeedTheCurrentTermsAndReadsDoNot() {
        var c = club();
        var body = new HashMap<String, Object>(Map.of("type", "ITEM", "name", "Kidney", "link", "https://example.test/kidney"));
        UUID memberId = me(c.member()).id();
        jdbc.update("UPDATE app_user SET accepted_terms_version = NULL, terms_accepted_at = NULL WHERE id = ?", memberId);
        add(c.member(), c.id(), body).expectStatus().isEqualTo(428);
        // Reading needs no new terms.
        wishlist(c.member(), c.id());
    }
}
