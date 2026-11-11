package eu.glazelab.api.controller;

import static eu.glazelab.api.controller.BuddyApiTest.befriend;
import static org.junit.jupiter.api.Assertions.*;

import eu.glazelab.api.dto.AuthDtos.UserView;
import eu.glazelab.api.dto.FeedDtos.FeedBadgeView;
import eu.glazelab.api.dto.FeedDtos.FeedPageView;
import eu.glazelab.api.support.ApiTestBase;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * A feed page needs the same statements however many buddies, clubs and entries there are, and the badge costs exactly
 * one statement, on {@code GET /api/auth/me} and alone on {@code GET /api/feed/unread} (the app's poll).
 */
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:feedqueries;DB_CLOSE_DELAY=-1",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.jpa.properties.hibernate.session.events.log=false" })
class FeedQueryApiTest extends ApiTestBase {
    private void activity(RestTestClient viewer, String maker, int n) {
        var buddy = user("Buddy" + n);
        befriend(viewer, buddy);
        UUID glaze = addGlaze(viewer, maker, "Glaze " + n, "Q" + n);
        addCombination(buddy, unique("Feed query"), glaze);
        buddy.post().uri("/api/stock").body(Map.of("glazeId", glaze, "quantityG", 100, "ownerType", "personal")).exchange().expectStatus().isOk();
        UUID club = createClub(viewer, unique("Feed query club"));
        var member = user("Member" + n);
        joinClub(viewer, club, member);
        member.post().uri("/api/stock").body(Map.of("glazeId", glaze, "quantityG", 100, "ownerType", "club", "clubId", club)).exchange().expectStatus().isOk();
        addCombination(member, unique("Feed query member"), glaze);
    }

    @Test void pagesAndTheBadgeUseConstantStatements() {
        var viewer = user("Viewer");
        String maker = unique("Feed queries");
        activity(viewer, maker, 0);
        Measured<FeedPageView> small = measured(() -> FeedApiTest.page(viewer, null));
        Measured<UserView> smallMe = measured(() -> me(viewer));
        assertEquals(6, small.result().entries().size());
        assertEquals(6, smallMe.result().feedUnread());

        for (int n = 1; n < 8; n++) activity(viewer, maker, n);
        Measured<FeedPageView> large = measured(() -> FeedApiTest.page(viewer, null));
        Measured<FeedPageView> second = measured(() -> FeedApiTest.page(viewer, large.result().next()));
        Measured<UserView> largeMe = measured(() -> me(viewer));
        assertEquals(30, large.result().entries().size());
        assertEquals(18, second.result().entries().size());
        assertEquals(48, largeMe.result().feedUnread());
        assertTrue(small.statements() > 0, "statistics must count");
        assertEquals(small.statements(), large.statements(), "page statements");
        assertEquals(small.statements(), second.statements(), "next page statements");
        assertEquals(smallMe.statements(), largeMe.statements(), "me statements");

        // The poll's badge is the session's statements and one more, like a page; /me adds its picture to that.
        Measured<FeedBadgeView> badge = measured(() -> body(viewer.get().uri("/api/feed/unread").exchange().expectStatus().isOk(), FeedBadgeView.class));
        assertEquals(48, badge.result().unread());
        assertNotNull(badge.result().newest());
        assertEquals(small.statements(), badge.statements(), "badge statements");
        assertEquals(badge.statements() + 1, largeMe.statements(), "me: the badge's one statement beside the picture's");
        // FEED-2: the newest waiting request rides in the same statement.
        assertNull(badge.result().requesterId());
        for (int n = 0; n < 3; n++) BuddyApiTest.asked(user("Asker" + n), BuddyApiTest.link(viewer));
        Measured<FeedBadgeView> asked = measured(() -> body(viewer.get().uri("/api/feed/unread").exchange().expectStatus().isOk(), FeedBadgeView.class));
        assertNotNull(asked.result().requesterId());
        assertEquals(3, asked.result().requests());
        assertEquals(badge.statements(), asked.statements(), "badge statements with requests");
        // Without sources the same one statement (requests addressed to the account still count).
        viewer.put().uri("/api/account/feed").body(Map.of("buddies", false, "clubs", false)).exchange().expectStatus().isNoContent();
        Measured<UserView> off = measured(() -> me(viewer));
        assertEquals(3, off.result().feedUnread());
        assertEquals(largeMe.statements(), off.statements(), "me without sources");
        System.out.println("Feed statements: page " + small.statements() + ", badge " + badge.statements() + ", /api/auth/me " + largeMe.statements());
    }
}
