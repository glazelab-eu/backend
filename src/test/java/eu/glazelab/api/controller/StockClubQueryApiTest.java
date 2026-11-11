package eu.glazelab.api.controller;

import eu.glazelab.api.dto.ClubDtos.ClubView;
import eu.glazelab.api.dto.ClubDtos.MemberView;
import eu.glazelab.api.dto.StockDtos.StockView;
import eu.glazelab.api.support.ApiTestBase;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.junit.jupiter.api.Assertions.*;

/** Stock and club listings need a constant number of statements however many rows they return. */
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:stockclubqueries;DB_CLOSE_DELAY=-1",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.jpa.properties.hibernate.session.events.log=false" })
class StockClubQueryApiTest extends ApiTestBase {
    private static List<StockView> stock(RestTestClient client) {
        return body(client.get().uri("/api/stock").exchange().expectStatus().isOk(),
                new ParameterizedTypeReference<List<StockView>>() {});
    }

    private static List<ClubView> clubs(RestTestClient client) {
        return body(client.get().uri("/api/clubs").exchange().expectStatus().isOk(),
                new ParameterizedTypeReference<List<ClubView>>() {});
    }

    private static List<MemberView> members(RestTestClient admin, UUID club) {
        return body(admin.get().uri("/api/clubs/{id}/members", club).exchange().expectStatus().isOk(),
                new ParameterizedTypeReference<List<MemberView>>() {});
    }

    private static void stockGlaze(RestTestClient client, UUID glaze, Map<String, Object> owner) {
        var body = new java.util.HashMap<String, Object>(owner);
        body.put("glazeId", glaze);
        body.put("quantityG", 100);
        client.post().uri("/api/stock").body(body).exchange().expectStatus().isOk();
    }

    @Test void stockListingUsesConstantQueriesAndKeepsItsOrder() {
        RestTestClient ada = user("stock-ada");
        UUID club = createClub(ada, unique("Stock club"));
        UUID otherClub = createClub(ada, unique("Other stock club"));
        var manufacturers = List.of(unique("Zeta"), unique("Alpha"), unique("Mid"));
        UUID first = addGlaze(ada, manufacturers.get(0), "B glaze", "1");
        stockGlaze(ada, first, Map.of("ownerType", "personal"));
        stockGlaze(ada, first, Map.of("ownerType", "club", "clubId", club));
        Measured<List<StockView>> small = measured(() -> stock(ada));
        assertEquals(2, small.result().size());

        for (int i = 0; i < 28; i++) {
            UUID glaze = addGlaze(ada, manufacturers.get(i % 3), "Glaze " + (i % 10), "C" + i);
            Map<String, Object> owner = switch (i % 3) {
                case 0 -> Map.of("ownerType", "personal");
                case 1 -> Map.of("ownerType", "club", "clubId", club);
                default -> Map.of("ownerType", "club", "clubId", otherClub);
            };
            stockGlaze(ada, glaze, owner);
        }
        Measured<List<StockView>> large = measured(() -> stock(ada));
        assertEquals(30, large.result().size());
        assertEquals(small.statements(), large.statements());
        // Four for the listing and one for the request's stored session (LoginSessions looks it up once per request).
        assertTrue(large.statements() <= 5, "Stock listing should need at most four statements besides the session lookup");

        List<StockView> sorted = new ArrayList<>(large.result());
        sorted.sort(Comparator.comparing(StockView::manufacturer).thenComparing(StockView::name));
        assertEquals(sorted, large.result());
        // Personal entries come before club entries of the same glaze.
        List<StockView> same = large.result().stream().filter(s -> s.glazeId().equals(first)).toList();
        assertNull(same.get(0).clubId());
        assertEquals(club, same.get(1).clubId());
        assertNotNull(same.get(1).clubName());
        assertNull(same.get(1).userId());
    }

    @Test void clubListingAndMembersUseConstantQueries() {
        RestTestClient ada = user("club-ada");
        UUID club = createClub(ada, unique("Roster"));
        Measured<List<ClubView>> oneClub = measured(() -> clubs(ada));
        Measured<List<MemberView>> oneMember = measured(() -> members(ada, club));
        assertEquals(1, oneClub.result().size());
        assertEquals(1, oneMember.result().size());

        for (int i = 0; i < 5; i++) {
            joinClub(ada, club, user("roster-member" + i));
            createClub(ada, unique("More clubs " + i));
        }
        Measured<List<ClubView>> manyClubs = measured(() -> clubs(ada));
        Measured<List<MemberView>> manyMembers = measured(() -> members(ada, club));
        assertEquals(6, manyClubs.result().size());
        assertEquals(6, manyMembers.result().size());
        assertEquals(oneClub.statements(), manyClubs.statements());
        assertEquals(oneMember.statements(), manyMembers.statements());
        assertEquals(manyClubs.result().stream().map(ClubView::name).sorted().toList(),
                manyClubs.result().stream().map(ClubView::name).toList());
        assertEquals(manyMembers.result().stream().map(MemberView::displayName).sorted().toList(),
                manyMembers.result().stream().map(MemberView::displayName).toList());
    }
}
