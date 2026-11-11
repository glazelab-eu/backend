package eu.glazelab.api.controller;

import static eu.glazelab.api.controller.BuddyApiTest.befriend;
import static org.junit.jupiter.api.Assertions.*;

import eu.glazelab.api.dto.FeedDtos.FeedBadgeView;
import eu.glazelab.api.dto.FeedDtos.FeedEntryView;
import eu.glazelab.api.dto.FeedDtos.FeedPageView;
import eu.glazelab.api.dto.FeedDtos.FeedType;
import eu.glazelab.api.exception.ApiErrorHandler;
import eu.glazelab.api.security.CurrentUser;
import eu.glazelab.api.service.FeedService;
import eu.glazelab.api.support.ApiTestBase;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * The activity feed's visibility matrix (backend guide "Activity feed"): every entry type for buddies, ex-buddies,
 * members, ex-members and outsiders; drafts, private, used-up and removed items; deleted and banned actors; the
 * sources, the window, paging and the seen position.
 */
class FeedApiTest extends ApiTestBase {
    record Seen(FeedType type, UUID itemId) {}

    static FeedPageView page(RestTestClient client, String before) {
        return body(client.get().uri(before == null ? "/api/feed" : "/api/feed?before={b}", before == null ? new Object[0] : new Object[] {before})
                .exchange().expectStatus().isOk(), FeedPageView.class);
    }

    /** The poll's answer with nothing new and no request waiting. */
    static final FeedBadgeView NOTHING = new FeedBadgeView(0, null, null, null, null, 0);

    static FeedBadgeView badge(RestTestClient client) {
        return body(client.get().uri("/api/feed/unread").exchange().expectStatus().isOk(), FeedBadgeView.class);
    }

    static List<FeedEntryView> entries(RestTestClient client) {
        return page(client, null).entries();
    }

    static List<Seen> seen(RestTestClient client) {
        return entries(client).stream().map(e -> new Seen(e.type(), e.itemId())).toList();
    }

    private UUID glaze(RestTestClient client, String name) {
        return addGlaze(client, unique("Feed maker"), name, "F" + shortId());
    }

    private UUID publish(RestTestClient client, UUID glaze) {
        return addCombination(client, unique("Feed combination"), glaze);
    }

    private static UUID draft(RestTestClient client, UUID glaze) {
        return created(client.post().uri("/api/combinations").body(Map.of("title", unique("Feed draft"), "draft", true,
                "steps", List.of(Map.of("glazeId", glaze, "coats", 1)))).exchange());
    }

    private static UUID personal(RestTestClient client, UUID glaze, int grams) {
        return created(client.post().uri("/api/stock").body(Map.of("glazeId", glaze, "quantityG", grams, "ownerType", "personal")).exchange());
    }

    private static UUID clubStock(RestTestClient client, UUID club, UUID glaze) {
        return created(client.post().uri("/api/stock").body(Map.of("glazeId", glaze, "quantityG", 300, "ownerType", "club", "clubId", club)).exchange());
    }

    private static void settings(RestTestClient client, boolean buddies, boolean clubs) {
        client.put().uri("/api/account/feed").body(Map.of("buddies", buddies, "clubs", clubs)).exchange().expectStatus().isNoContent();
    }

    private static void markSeen(RestTestClient client, Instant through) {
        client.post().uri("/api/feed/seen").body(Map.of("through", through.toString())).exchange().expectStatus().isNoContent();
    }

    @Test void aBuddySeesWhatTheOtherAddsAndNothingOnceItIsGone() {
        var ada = user("Ada"); var ben = user("Ben"); var cy = user("Cy");
        UUID benId = me(ben).id();
        UUID g = glaze(ada, "Celadon");
        // Before the pair: not announced (the feed starts when they became buddies).
        UUID before = publish(ben, g);
        personal(ben, glaze(ada, "Old shelf"), 100);
        befriend(ada, ben);
        UUID published = publish(ben, g);
        UUID hidden = draft(ben, g);
        UUID shelf = personal(ben, g, 250);

        var adas = entries(ada);
        assertEquals(List.of(new Seen(FeedType.BUDDY_GLAZE, g), new Seen(FeedType.BUDDY_COMBINATION, published),
                new Seen(FeedType.NEW_BUDDY, benId)), adas.stream().map(e -> new Seen(e.type(), e.itemId())).toList());
        FeedEntryView glazeEntry = adas.getFirst();
        assertEquals("Celadon", glazeEntry.glazeName());
        assertEquals(benId, glazeEntry.actorId());
        assertEquals(me(ben).displayName(), glazeEntry.actorName());
        assertNull(glazeEntry.clubId());
        assertFalse(adas.stream().anyMatch(e -> e.itemId().equals(before) || e.itemId().equals(hidden)));
        // No amount, jar size or address anywhere in the answer.
        String raw = ada.get().uri("/api/feed").exchange().expectBody(String.class).returnResult().getResponseBody();
        assertFalse(raw.contains("quantity") || raw.contains("jar") || raw.contains("keepPrivate") || raw.contains("@"), raw);
        // Nor the ID of the buddy's stock entry (no other answer names it to anyone but its owner).
        assertFalse(raw.contains(shelf.toString()), raw);
        // Ben accepted (his own action) and his own additions are not in his feed; a stranger sees nothing.
        assertEquals(List.of(), entries(ben));
        assertEquals(List.of(), entries(cy));

        // Kept private, used up: gone; visible again: back. Publication of the draft: it appears.
        ben.patch().uri("/api/stock/{id}", shelf).body(Map.of("keepPrivate", true)).exchange().expectStatus().is2xxSuccessful();
        assertFalse(seen(ada).contains(new Seen(FeedType.BUDDY_GLAZE, g)));
        ben.patch().uri("/api/stock/{id}", shelf).body(Map.of("keepPrivate", false)).exchange().expectStatus().is2xxSuccessful();
        assertTrue(seen(ada).contains(new Seen(FeedType.BUDDY_GLAZE, g)));
        ben.post().uri("/api/stock").body(Map.of("glazeId", g, "quantityG", 0, "ownerType", "personal")).exchange().expectStatus().is2xxSuccessful();
        assertFalse(seen(ada).contains(new Seen(FeedType.BUDDY_GLAZE, g)));
        ben.post().uri("/api/combinations/{id}/publish", hidden).exchange().expectStatus().is2xxSuccessful();
        assertTrue(seen(ada).contains(new Seen(FeedType.BUDDY_COMBINATION, hidden)));
        // A deleted combination: gone.
        ben.delete().uri("/api/combinations/{id}", published).exchange().expectStatus().isNoContent();
        assertFalse(seen(ada).contains(new Seen(FeedType.BUDDY_COMBINATION, published)));

        // Ex-buddy: nothing of the other any more.
        ada.delete().uri("/api/buddies/{id}", benId).exchange().expectStatus().isNoContent();
        assertEquals(List.of(), entries(ada));
    }

    @Test void aPendingRequestSharesNothingButItself() {
        var ada = user("Ada"); var ben = user("Ben");
        BuddyApiTest.asked(ada, BuddyApiTest.link(ben));
        publish(ben, glaze(ada, "Pending"));
        publish(ada, glaze(ada, "Pending too"));
        assertEquals(List.of(), entries(ada));
        assertEquals(List.of(new Seen(FeedType.BUDDY_REQUEST, me(ada).id())), seen(ben));
    }

    @Test void clubMembersSeeAdditionsSinceTheyJoined() {
        var ada = user("Ada"); var ben = user("Ben"); var cy = user("Cy"); var dee = user("Dee");
        UUID benId = me(ben).id(), cyId = me(cy).id();
        UUID g = glaze(ada, "Shino");
        UUID club = createClub(ada, unique("Feed club"));
        UUID earlier = publish(ben, g);
        joinClub(ada, club, ben);
        joinClub(ada, club, cy);
        UUID published = publish(ben, g);
        clubStock(ben, club, g);

        assertEquals(List.of(new Seen(FeedType.CLUB_GLAZE, g), new Seen(FeedType.CLUB_COMBINATION, published),
                new Seen(FeedType.CLUB_MEMBER, cyId), new Seen(FeedType.CLUB_MEMBER, benId)), seen(ada));
        FeedEntryView member = entries(ada).getLast();
        assertEquals(club, member.clubId());
        assertNotNull(member.clubName());
        // Ben joined before Cy: he sees Cy join, never Ada (the creator joined first), nor his own additions.
        assertEquals(List.of(new Seen(FeedType.CLUB_MEMBER, cyId)), seen(ben));
        // Cy joined after Ben: Ben's join is older than Cy's own, so not announced; the later additions are.
        assertEquals(List.of(new Seen(FeedType.CLUB_GLAZE, g), new Seen(FeedType.CLUB_COMBINATION, published)), seen(cy));
        // The combination Ben published before he joined is never announced; an outsider sees nothing.
        assertFalse(seen(ada).contains(new Seen(FeedType.CLUB_COMBINATION, earlier)));
        assertEquals(List.of(), entries(dee));

        // Ben leaves: his entries go (his name leaves the shelf entry, so it names nobody).
        ben.delete().uri("/api/clubs/{id}/members/{user}", club, benId).exchange().expectStatus().isNoContent();
        assertEquals(List.of(new Seen(FeedType.CLUB_MEMBER, cyId)), seen(ada));
        assertEquals(List.of(), entries(cy));
        // A viewer who left sees nothing of the club.
        UUID after = glaze(ada, "After");
        UUID later = clubStock(ada, club, after);
        assertEquals(List.of(new Seen(FeedType.CLUB_GLAZE, after)), seen(cy));
        cy.delete().uri("/api/clubs/{id}/members/{user}", club, cyId).exchange().expectStatus().isNoContent();
        assertEquals(List.of(), entries(cy));
        // A removed club entry: gone.
        ada.delete().uri("/api/stock/{id}", later).exchange().expectStatus().isNoContent();
        assertFalse(entries(ada).stream().anyMatch(e -> e.type() == FeedType.CLUB_GLAZE));
    }

    @Test void aBuddyWhoIsAlsoACoMemberIsListedOnce() {
        var ada = user("Ada"); var ben = user("Ben");
        UUID club = createClub(ada, unique("Feed shared"));
        joinClub(ada, club, ben);
        befriend(ada, ben);
        UUID published = publish(ben, glaze(ada, "Shared"));
        var found = entries(ada).stream().filter(e -> e.itemId().equals(published)).toList();
        assertEquals(1, found.size());
        assertEquals(FeedType.BUDDY_COMBINATION, found.getFirst().type());
        assertNull(found.getFirst().clubId());
        settings(ada, false, true);
        found = entries(ada).stream().filter(e -> e.itemId().equals(published)).toList();
        assertEquals(List.of(FeedType.CLUB_COMBINATION), found.stream().map(FeedEntryView::type).toList());
        assertEquals(club, found.getFirst().clubId());
    }

    @Test void theSourcesFilterTheListAndTheBadge() {
        var ada = user("Ada"); var ben = user("Ben"); var cy = user("Cy");
        UUID club = createClub(ada, unique("Feed sources"));
        befriend(ada, ben);
        joinClub(ada, club, cy);
        var user = me(ada);
        assertTrue(user.feedBuddies() && user.feedClubs());
        assertEquals(2, user.feedUnread());
        settings(ada, false, true);
        assertEquals(List.of(FeedType.CLUB_MEMBER), entries(ada).stream().map(FeedEntryView::type).toList());
        assertEquals(1, me(ada).feedUnread());
        settings(ada, true, false);
        assertEquals(List.of(FeedType.NEW_BUDDY), entries(ada).stream().map(FeedEntryView::type).toList());
        settings(ada, false, false);
        assertEquals(List.of(), entries(ada));
        user = me(ada);
        assertEquals(0, user.feedUnread());
        assertFalse(user.feedBuddies() || user.feedClubs());
        ada.put().uri("/api/account/feed").body(Map.of("buddies", true)).exchange().expectStatus().isBadRequest();
    }

    @Test void aDeletedAccountLeavesNothingBehind() {
        var ada = user("Ada"); var ben = user("Ben");
        UUID club = createClub(ada, unique("Feed deletion"));
        joinClub(ada, club, ben);
        befriend(ada, ben);
        UUID g = glaze(ada, "Deleted");
        UUID published = publish(ben, g);
        personal(ben, g, 100);
        clubStock(ben, club, glaze(ada, "Club deleted"));
        assertEquals(5, entries(ada).size());
        ben.post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange().expectStatus().isNoContent();
        assertEquals(List.of(), entries(ada));
        // The combination itself stays public, without its author.
        assertTrue(combination(ada, published).isPresent());
    }

    @Test void aBannedAccountLeavesNothingBehind() {
        var admin = siteAdmin(); var ada = user("Ada"); var ben = user("Ben");
        UUID club = createClub(ada, unique("Feed ban"));
        joinClub(ada, club, ben);
        befriend(ada, ben);
        publish(ben, glaze(ada, "Banned"));
        assertEquals(3, entries(ada).size());
        admin.post().uri("/api/admin/users/{id}/ban", me(ben).id()).body(Map.of("reason", "Spam")).exchange().expectStatus().isNoContent();
        assertEquals(List.of(), entries(ada));
    }

    @Test void membershipsOlderThanTheirTimeColumnAreNeverNew() {
        var ada = user("Ada"); var ben = user("Ben"); var cy = user("Cy");
        UUID club = createClub(ada, unique("Feed V31"));
        joinClub(ada, club, ben);
        joinClub(ada, club, cy);
        // V31 gave every existing membership the migration's time: the same instant for all of them.
        Timestamp migration = Timestamp.from(Instant.now().minus(Duration.ofDays(3)));
        assertEquals(3, jdbc.update("UPDATE membership SET joined_at = ? WHERE club_id = ?", migration, club));
        for (var client : List.of(ada, ben, cy))
            assertFalse(entries(client).stream().anyMatch(e -> e.type() == FeedType.CLUB_MEMBER));
        // Stock entries from before V34 carry no time and are never new.
        UUID entry = clubStock(ben, club, glaze(ada, "Old entry"));
        jdbc.update("UPDATE stock SET created_at = NULL WHERE id = ?", entry);
        assertEquals(List.of(), entries(ada));
    }

    @Test void entriesOlderThanTheWindowAreNotShown() {
        var ada = user("Ada"); var ben = user("Ben");
        befriend(ada, ben);
        UUID published = publish(ben, glaze(ada, "Window"));
        Timestamp old = Timestamp.from(Instant.now().minus(Duration.ofDays(31)));
        jdbc.update("UPDATE buddy SET created_at = ?, accepted_at = ? WHERE requested_by = ?", old, old, me(ada).id());
        jdbc.update("UPDATE combination SET published_at = ? WHERE id = ?", Timestamp.from(Instant.now().minus(Duration.ofDays(30)).minusSeconds(5)), published);
        assertEquals(List.of(), entries(ada));
        assertEquals(0, me(ada).feedUnread());
    }

    @Test void pagesFollowTheCursorWithoutRepeats() {
        var ada = user("Ada"); var ben = user("Ben");
        UUID club = createClub(ada, unique("Feed pages"));
        joinClub(ada, club, ben);
        String maker = unique("Feed pages maker");
        for (int n = 0; n < 34; n++) clubStock(ben, club, addGlaze(ada, maker, "Page " + n, "P" + n));
        var first = page(ada, null);
        assertEquals(30, first.entries().size());
        assertNotNull(first.next());
        var second = page(ada, first.next());
        assertEquals(5, second.entries().size());
        assertNull(second.next());
        var ids = new HashSet<String>();
        var all = new ArrayList<FeedEntryView>(first.entries());
        all.addAll(second.entries());
        all.forEach(e -> assertTrue(ids.add(e.id()), "no repeat"));
        for (int i = 1; i < all.size(); i++) assertFalse(all.get(i).at().isAfter(all.get(i - 1).at()), "newest first");
        // Ties in time keep a stable order across pages (one instant after everyone joined).
        jdbc.update("UPDATE stock SET created_at = ? WHERE club_id = ?", Timestamp.from(Instant.now()), club);
        var tied = page(ada, null);
        assertNotNull(tied.next());
        var rest = page(ada, tied.next());
        var tiedIds = new HashSet<String>();
        tied.entries().forEach(e -> tiedIds.add(e.id()));
        rest.entries().forEach(e -> assertTrue(tiedIds.add(e.id()), "no repeat among ties"));
        assertEquals(35, tiedIds.size());
        for (String bad : List.of("x", "1.NOPE.0", "1.NOT_A_TYPE." + UUID.randomUUID(), "99999999999999999999.CLUB_GLAZE." + UUID.randomUUID(), "1.CLUB_GLAZE.'; --"))
            assertError(ada.get().uri("/api/feed?before={b}", bad).exchange(), 400, ApiErrorHandler.INVALID_INPUT);
    }

    @Test void theSeenPositionOnlyMovesForward() {
        var ada = user("Ada"); var ben = user("Ben"); var cy = user("Cy");
        befriend(ada, ben);
        publish(ben, glaze(ada, "Seen"));
        var page = page(ada, null);
        assertNull(page.seenAt());
        assertEquals(2, me(ada).feedUnread());
        Instant newest = page.entries().getFirst().at();
        markSeen(ada, page.entries().getLast().at());
        assertEquals(1, me(ada).feedUnread());
        markSeen(ada, newest);
        assertEquals(0, me(ada).feedUnread());
        assertEquals(newest, page(ada, null).seenAt());
        // Never back, never past the server's now.
        markSeen(ada, newest.minusSeconds(3600));
        assertEquals(newest, page(ada, null).seenAt());
        markSeen(ada, Instant.now().plus(Duration.ofDays(365)));
        assertFalse(page(ada, null).seenAt().isAfter(Instant.now()));
        befriend(cy, ada);
        publish(ben, glaze(ada, "Later"));
        assertEquals(1, me(ada).feedUnread());
        ada.post().uri("/api/feed/seen").body(Map.of()).exchange().expectStatus().isBadRequest();
    }

    @Test void aBuddyRequestShowsToTheAskedAccountUntilItIsAnswered() {
        var ada = user("Ada"); var ben = user("Ben"); var cy = user("Cy");
        UUID adaId = me(ada).id(), benId = me(ben).id();
        // Ada asks Ben by link: Ben sees the request (and it counts), Ada never sees her own request.
        BuddyApiTest.asked(ada, BuddyApiTest.link(ben));
        var bens = entries(ben);
        assertEquals(List.of(new Seen(FeedType.BUDDY_REQUEST, adaId)), bens.stream().map(e -> new Seen(e.type(), e.itemId())).toList());
        assertEquals(me(ada).displayName(), bens.getFirst().actorName());
        assertEquals(1, me(ben).feedUnread());
        assertEquals(List.of(), entries(ada));
        assertEquals(List.of(), entries(cy));
        // Whatever the sources: the request is addressed to Ben.
        settings(ben, false, false);
        assertEquals(1, entries(ben).size());
        assertEquals(1, me(ben).feedUnread());
        settings(ben, true, true);
        // Accepted by Ben (his own action): the request goes and no new-buddy entry appears for him; Ada sees the new buddy.
        BuddyApiTest.act(ben, "accept", adaId).expectStatus().isNoContent();
        assertEquals(List.of(), entries(ben));
        assertEquals(List.of(new Seen(FeedType.NEW_BUDDY, benId)), seen(ada));
        // Declined or withdrawn: gone.
        UUID cyId = me(cy).id();
        BuddyApiTest.asked(cy, BuddyApiTest.link(ben));
        assertEquals(List.of(new Seen(FeedType.BUDDY_REQUEST, cyId)), seen(ben));
        BuddyApiTest.act(ben, "decline", cyId).expectStatus().isNoContent();
        assertEquals(List.of(), entries(ben));
        BuddyApiTest.asked(cy, BuddyApiTest.link(ben));
        BuddyApiTest.act(cy, "cancel", benId).expectStatus().isNoContent();
        assertEquals(List.of(), entries(ben));
    }

    /**
     * A waiting request is shown for as long as it waits, older than the window too (the buddy list counts it, so the
     * page must show it); it counts as new only while it is later than the seen position, so looking clears the badge.
     */
    @Test void aWaitingRequestOlderThanTheWindowStaysUntilAnswered() {
        var ada = user("Ada"); var ben = user("Ben"); var cy = user("Cy");
        UUID adaId = me(ada).id(), cyId = me(cy).id();
        BuddyApiTest.asked(ada, BuddyApiTest.link(ben));
        BuddyApiTest.asked(cy, BuddyApiTest.link(ben));
        Instant old = Instant.now().minus(Duration.ofDays(45)).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        jdbc.update("UPDATE buddy SET created_at = ? WHERE requested_by = ?", Timestamp.from(old), adaId);
        jdbc.update("UPDATE buddy SET created_at = ? WHERE requested_by = ?", Timestamp.from(old.minus(Duration.ofDays(400))), cyId);
        assertEquals(List.of(new Seen(FeedType.BUDDY_REQUEST, adaId), new Seen(FeedType.BUDDY_REQUEST, cyId)), seen(ben));
        assertEquals(2, me(ben).feedUnread());
        assertEquals(new FeedBadgeView(2, old, adaId, me(ada).displayName(), old, 2), badge(ben));
        settings(ben, false, false);
        assertEquals(2, entries(ben).size());
        // Looked at: the newest entry shown is the old request; the badge clears and the entries stay.
        markSeen(ben, page(ben, null).entries().getFirst().at());
        assertEquals(0, me(ben).feedUnread());
        assertEquals(NOTHING, badge(ben));
        assertEquals(old, page(ben, null).seenAt());
        assertEquals(2, entries(ben).size());
        // Answered: gone; nothing else older than the window comes with it.
        BuddyApiTest.act(ben, "accept", adaId).expectStatus().isNoContent();
        BuddyApiTest.act(ben, "decline", cyId).expectStatus().isNoContent();
        settings(ben, true, true);
        assertEquals(List.of(), entries(ben));
    }

    @Test void aRequestFromAClubMemberShowsAndLeavesWithTheRequestersAccount() {
        var admin = siteAdmin(); var ada = user("Ada"); var ben = user("Ben"); var cy = user("Cy");
        UUID club = createClub(ada, unique("Feed requests"));
        joinClub(ada, club, ben);
        UUID benId = me(ben).id();
        BuddyApiTest.requestMember(ben, club, me(ada).id()).expectStatus().isOk();
        assertTrue(seen(ada).contains(new Seen(FeedType.BUDDY_REQUEST, benId)));
        ben.post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange().expectStatus().isNoContent();
        assertFalse(seen(ada).stream().anyMatch(s -> s.type() == FeedType.BUDDY_REQUEST));
        // A banned requester: the same.
        UUID cyId = me(cy).id();
        BuddyApiTest.asked(cy, BuddyApiTest.link(ada));
        assertTrue(seen(ada).contains(new Seen(FeedType.BUDDY_REQUEST, cyId)));
        admin.post().uri("/api/admin/users/{id}/ban", cyId).body(Map.of("reason", "Spam")).exchange().expectStatus().isNoContent();
        assertFalse(seen(ada).stream().anyMatch(s -> s.type() == FeedType.BUDDY_REQUEST));
    }

    @Test void theBadgeAloneAnswersTheCountAndTheNewestEntry() {
        var ada = user("Ada"); var ben = user("Ben");
        var empty = body(ada.get().uri("/api/feed/unread").exchange().expectStatus().isOk(), FeedBadgeView.class);
        assertEquals(NOTHING, empty);
        befriend(ada, ben);
        publish(ben, glaze(ada, "Badge"));
        var badge = body(ada.get().uri("/api/feed/unread").exchange().expectStatus().isOk(), FeedBadgeView.class);
        assertEquals(2, badge.unread());
        assertEquals(page(ada, null).entries().getFirst().at(), badge.newest());
        markSeen(ada, badge.newest());
        assertEquals(NOTHING, badge(ada));
        assertError(guest().get().uri("/api/feed/unread").exchange(), 401, CurrentUser.SIGN_IN_REQUIRED);
    }

    /**
     * FEED-2: the poll names the newest waiting request addressed to the account that is later than the seen position, with
     * how many such wait, by the same rule as the feed's request entry: never one the account sent; answered, withdrawn,
     * seen, or with the asking account deleted or banned it is gone; the sources do not matter; display name only.
     */
    @Test void theBadgeNamesTheNewestUnseenRequestAddressedToTheAccount() {
        var admin = siteAdmin(); var ada = user("Ada"); var ben = user("Ben"); var cy = user("Cy"); var dee = user("Dee");
        UUID adaId = me(ada).id(), benId = me(ben).id(), cyId = me(cy).id(), deeId = me(dee).id();
        assertEquals(NOTHING, badge(ben));
        BuddyApiTest.asked(ada, BuddyApiTest.link(ben));
        Instant adas = Instant.now().minusSeconds(120).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        jdbc.update("UPDATE buddy SET created_at = ? WHERE requested_by = ?", Timestamp.from(adas), adaId);
        assertEquals(new FeedBadgeView(1, adas, adaId, me(ada).displayName(), adas, 1), badge(ben));
        // The asking account sees nothing of its own request.
        assertEquals(NOTHING, badge(ada));
        // A later request is the newest; both wait. The answer carries no address.
        BuddyApiTest.asked(cy, BuddyApiTest.link(ben));
        var two = badge(ben);
        assertEquals(cyId, two.requesterId());
        assertEquals(me(cy).displayName(), two.requesterName());
        assertEquals(entries(ben).getFirst().at(), two.requestedAt());
        assertEquals(2, two.requests());
        String raw = ben.get().uri("/api/feed/unread").exchange().expectBody(String.class).returnResult().getResponseBody();
        assertFalse(raw.contains("@"), raw);
        // Whatever the sources.
        settings(ben, false, false);
        assertEquals(two, badge(ben));
        settings(ben, true, true);
        // Withdrawn: the older one is the newest again.
        BuddyApiTest.act(cy, "cancel", benId).expectStatus().isNoContent();
        assertEquals(new FeedBadgeView(1, adas, adaId, me(ada).displayName(), adas, 1), badge(ben));
        // Declined: none.
        BuddyApiTest.act(ben, "decline", adaId).expectStatus().isNoContent();
        assertEquals(NOTHING, badge(ben));
        // Accepted: none for Ben; Dee's new buddy is an entry but no request.
        BuddyApiTest.asked(dee, BuddyApiTest.link(ben));
        assertEquals(deeId, badge(ben).requesterId());
        BuddyApiTest.act(ben, "accept", deeId).expectStatus().isNoContent();
        assertEquals(NOTHING, badge(ben));
        var dees = badge(dee);
        assertEquals(1, dees.unread());
        assertNull(dees.requesterId());
        assertEquals(0, dees.requests());
        // Ben asking someone himself is never his request to answer.
        BuddyApiTest.asked(ben, BuddyApiTest.link(cy));
        assertEquals(NOTHING, badge(ben));
        assertEquals(benId, badge(cy).requesterId());
        // Seen on the activity page: no longer named (it still waits in the feed).
        BuddyApiTest.asked(ada, BuddyApiTest.link(dee));
        var waiting = badge(dee);
        assertEquals(adaId, waiting.requesterId());
        markSeen(dee, waiting.requestedAt());
        assertNull(badge(dee).requesterId());
        assertEquals(0, badge(dee).requests());
        // The asking account deleted or banned: gone.
        assertEquals(NOTHING, badge(ben));
        BuddyApiTest.asked(ada, BuddyApiTest.link(ben));
        assertEquals(adaId, badge(ben).requesterId());
        ada.post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange().expectStatus().isNoContent();
        assertEquals(NOTHING, badge(ben));
        var eve = user("Eve");
        UUID eveId = me(eve).id();
        BuddyApiTest.asked(eve, BuddyApiTest.link(ben));
        assertEquals(eveId, badge(ben).requesterId());
        admin.post().uri("/api/admin/users/{id}/ban", eveId).body(Map.of("reason", "Spam")).exchange().expectStatus().isNoContent();
        assertEquals(NOTHING, badge(ben));
    }

    /** Review FEED-1: the window's floor holds in every branch but the waiting requests, not only the ones the other tests date back. */
    @Test void everyBranchStopsAtTheWindow() {
        var ada = user("Ada"); var ben = user("Ben"); var cy = user("Cy");
        UUID adaId = me(ada).id(), benId = me(ben).id(), cyId = me(cy).id();
        UUID club = createClub(ada, unique("Feed window all"));
        joinClub(ada, club, ben);
        befriend(ada, ben);
        UUID g = glaze(ada, "Window all");
        UUID published = publish(ben, g);
        personal(ben, g, 100);
        clubStock(ben, club, glaze(ada, "Window club"));
        BuddyApiTest.asked(cy, BuddyApiTest.link(ada));
        assertEquals(Set.of(FeedType.BUDDY_COMBINATION, FeedType.BUDDY_GLAZE, FeedType.NEW_BUDDY, FeedType.BUDDY_REQUEST,
                FeedType.CLUB_MEMBER, FeedType.CLUB_GLAZE), types(ada));
        settings(ada, false, true);
        assertTrue(types(ada).contains(FeedType.CLUB_COMBINATION));

        // Every relation older than every addition, every addition just older than the window.
        Instant now = Instant.now();
        Timestamp relation = Timestamp.from(now.minus(Duration.ofDays(40))), added = Timestamp.from(now.minus(Duration.ofDays(31)));
        jdbc.update("UPDATE membership SET joined_at = ? WHERE club_id = ? AND user_id = ?", Timestamp.from(now.minus(Duration.ofDays(50))), club, adaId);
        jdbc.update("UPDATE membership SET joined_at = ? WHERE club_id = ? AND user_id = ?", Timestamp.from(now.minus(Duration.ofDays(31)).minusSeconds(60)), club, benId);
        jdbc.update("UPDATE buddy SET created_at = ?, accepted_at = ? WHERE requested_by = ?", relation, Timestamp.from(now.minus(Duration.ofDays(31)).minusSeconds(60)), adaId);
        jdbc.update("UPDATE buddy SET created_at = ? WHERE requested_by = ?", added, cyId);
        jdbc.update("UPDATE combination SET published_at = ? WHERE id = ?", added, published);
        jdbc.update("UPDATE stock SET created_at = ? WHERE user_id = ? OR club_id = ?", added, benId, club);
        // Only the waiting request outlives the window (it has none).
        for (boolean buddies : List.of(true, false)) {
            settings(ada, buddies, true);
            assertEquals(List.of(new Seen(FeedType.BUDDY_REQUEST, cyId)), seen(ada), "buddies " + buddies);
            assertEquals(1, me(ada).feedUnread());
        }
    }

    /** Review FEED-1: an addition at the very instant the viewer became buddy or member is not after it (strictly later). */
    @Test void anAdditionAtTheInstantOfTheRelationIsNotNew() {
        var ada = user("Ada"); var ben = user("Ben"); var cy = user("Cy");
        UUID adaId = me(ada).id(), benId = me(ben).id();
        befriend(ada, ben);
        UUID g = glaze(ada, "Same instant");
        UUID published = publish(ben, g);
        personal(ben, g, 100);
        UUID club = createClub(cy, unique("Feed instant"));
        joinClub(cy, club, ben);
        clubStock(ben, club, glaze(ada, "Same instant club"));
        UUID clubbed = publish(ben, glaze(ada, "Same instant club combination"));
        joinClub(cy, club, ada);
        Timestamp at = Timestamp.from(Instant.now().minus(Duration.ofDays(2)));
        jdbc.update("UPDATE buddy SET accepted_at = ? WHERE requested_by = ?", at, adaId);
        jdbc.update("UPDATE membership SET joined_at = ? WHERE club_id = ?", Timestamp.from(Instant.now().minus(Duration.ofDays(3))), club);
        jdbc.update("UPDATE membership SET joined_at = ? WHERE club_id = ? AND user_id = ?", at, club, adaId);
        jdbc.update("UPDATE combination SET published_at = ? WHERE id IN (?, ?)", at, published, clubbed);
        jdbc.update("UPDATE stock SET created_at = ? WHERE user_id = ? OR club_id = ?", at, benId, club);
        // NEW_BUDDY is dated by the acceptance itself and stays; nothing else is later than a relation.
        assertEquals(List.of(new Seen(FeedType.NEW_BUDDY, benId)), seen(ada));
        settings(ada, false, true);
        assertEquals(List.of(), entries(ada));
    }

    /** Review FEED-1: a club shelf entry or a member's publication from before the viewer (re)joined is never new to them. */
    @Test void aClubShelfEntryFromBeforeTheViewerJoinedIsNotNew() {
        var ada = user("Ada"); var ben = user("Ben"); var cy = user("Cy");
        UUID cyId = me(cy).id();
        UUID club = createClub(ada, unique("Feed shelf before"));
        joinClub(ada, club, ben);
        UUID g = glaze(ada, "Shelf before");
        clubStock(ben, club, g);
        UUID published = publish(ben, g);
        joinClub(ada, club, cy);
        assertEquals(List.of(), entries(cy));
        assertTrue(seen(ada).containsAll(List.of(new Seen(FeedType.CLUB_GLAZE, g), new Seen(FeedType.CLUB_COMBINATION, published))));
        // Away and back: what was added meanwhile is older than the new membership.
        cy.delete().uri("/api/clubs/{id}/members/{user}", club, cyId).exchange().expectStatus().isNoContent();
        UUID meanwhile = glaze(ada, "Shelf meanwhile");
        clubStock(ben, club, meanwhile);
        publish(ben, meanwhile);
        joinClub(ada, club, cy);
        assertEquals(List.of(), entries(cy));
    }

    /** Review FEED-1: an account that is not verified (a pending leftover with a row anyway) appears in no branch. */
    @Test void unverifiedAccountsNeverAppear() {
        var ada = user("Ada"); var ben = user("Ben"); var cy = user("Cy");
        UUID benId = me(ben).id(), cyId = me(cy).id();
        UUID club = createClub(ada, unique("Feed unverified"));
        joinClub(ada, club, ben);
        befriend(ada, ben);
        UUID g = glaze(ada, "Unverified");
        publish(ben, g);
        personal(ben, g, 100);
        clubStock(ben, club, glaze(ada, "Unverified club"));
        BuddyApiTest.asked(cy, BuddyApiTest.link(ada));
        assertEquals(6, entries(ada).size());
        assertEquals(2, jdbc.update("UPDATE app_user SET email_verified = FALSE WHERE id IN (?, ?)", benId, cyId));
        for (boolean buddies : List.of(true, false)) {
            settings(ada, buddies, true);
            assertEquals(List.of(), entries(ada), "buddies " + buddies);
            assertEquals(0, me(ada).feedUnread());
        }
    }

    /** Review FEED-1: a seen time the database cannot store is no error and moves nothing; a far-future one stops at now. */
    @Test void aSeenTimeOutOfRangeIsHarmless() {
        var ada = user("Ada"); var ben = user("Ben");
        befriend(ada, ben);
        for (String through : List.of("-999999999-01-01T00:00:00Z", "-4800-01-01T00:00:00Z", "0000-01-01T00:00:00Z")) {
            ada.post().uri("/api/feed/seen").body(Map.of("through", through)).exchange().expectStatus().isNoContent();
            assertEquals(1, me(ada).feedUnread(), through);
        }
        ada.post().uri("/api/feed/seen").body(Map.of("through", "+1000000000-12-31T23:59:59.999999999Z")).exchange().expectStatus().isNoContent();
        assertEquals(0, me(ada).feedUnread());
        assertFalse(page(ada, null).seenAt().isAfter(Instant.now()));
    }

    /** Review FEED-1: a well-formed cursor at either end of time answers a page (empty or not), never a server error. */
    @Test void aCursorAtEitherEndOfTimeIsNoError() {
        var ada = user("Ada"); var ben = user("Ben");
        befriend(ada, ben);
        assertEquals(1, page(ada, "9223372036854775807.CLUB_GLAZE." + UUID.randomUUID()).entries().size());
        assertEquals(List.of(), page(ada, "0.BUDDY_COMBINATION." + UUID.randomUUID()).entries());
    }

    /** Review FEED-1: the badge counts at most {@code BADGE_CAP} entries (the poll's bound), on both answers. */
    @Test void theBadgeStopsAtItsCap() {
        var ada = user("Ada"); var ben = user("Ben");
        UUID club = createClub(ada, unique("Feed cap"));
        joinClub(ada, club, ben);
        UUID benId = me(ben).id();
        UUID maker = jdbc.queryForObject("SELECT manufacturer_id FROM glaze WHERE id = ?", UUID.class, glaze(ada, "Cap"));
        Timestamp at = Timestamp.from(Instant.now());
        for (int n = 0; n < FeedService.BADGE_CAP + 5; n++) {
            UUID g = UUID.randomUUID();
            jdbc.update("INSERT INTO glaze (id, name, code, manufacturer_id) VALUES (?, ?, ?, ?)", g, "Cap " + n, "CAP-" + shortId(), maker);
            jdbc.update("INSERT INTO stock (id, glaze_id, club_id, added_by, quantity_g, created_at) VALUES (?, ?, ?, ?, 100, ?)",
                    UUID.randomUUID(), g, club, benId, at);
        }
        assertEquals(FeedService.BADGE_CAP, me(ada).feedUnread());
        assertEquals(FeedService.BADGE_CAP, body(ada.get().uri("/api/feed/unread").exchange().expectStatus().isOk(), FeedBadgeView.class).unread());
    }

    private static Set<FeedType> types(RestTestClient client) {
        return entries(client).stream().map(FeedEntryView::type).collect(java.util.stream.Collectors.toSet());
    }

    @Test void guestsAreAskedToSignIn() {
        assertError(guest().get().uri("/api/feed").exchange(), 401, CurrentUser.SIGN_IN_REQUIRED);
        assertError(guest().post().uri("/api/feed/seen").body(Map.of("through", Instant.now().toString())).exchange(), 401, CurrentUser.SIGN_IN_REQUIRED);
        assertError(guest().put().uri("/api/account/feed").body(Map.of("buddies", true, "clubs", true)).exchange(), 401, CurrentUser.SIGN_IN_REQUIRED);
    }
}
