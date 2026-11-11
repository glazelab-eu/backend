package eu.glazelab.api.controller;

import eu.glazelab.api.controller.FavoriteController.FavoritesView;
import eu.glazelab.api.support.ApiTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.junit.jupiter.api.Assertions.*;

/** Its own database with statement statistics, so the favourites listing's statement count can be measured. */
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:favoritequeries;DB_CLOSE_DELAY=-1",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.jpa.properties.hibernate.session.events.log=false" })
class FavoriteQueryApiTest extends ApiTestBase {
    private static FavoritesView favorites(RestTestClient client) {
        return body(client.get().uri("/api/favorites").exchange().expectStatus().isOk(), FavoritesView.class);
    }

    private static UUID draft(RestTestClient client, UUID glaze) {
        return created(client.post().uri("/api/combinations").body(Map.of("title", unique("Draft"), "draft", true,
                "steps", List.of(Map.of("glazeId", glaze, "coats", 2)))).exchange());
    }

    /** Stars {@code count} more published combinations of {@code author} and returns their IDs. */
    private static List<UUID> star(RestTestClient reader, RestTestClient author, UUID glaze, int count) {
        var ids = new ArrayList<UUID>();
        for (int i = 0; i < count; i++) {
            UUID id = addCombination(author, unique("Starred"), glaze);
            reader.put().uri("/api/favorites/combinations/{id}", id).exchange().expectStatus().isOk();
            ids.add(id);
        }
        return ids;
    }

    @Test void theListingNeedsNoQueryPerFavouriteCombination() {
        var ada = user("Ada"); var bea = user("Bea");
        UUID glaze = addGlaze(ada, unique("Counted"), "Blue", "FQ-1");
        ada.put().uri("/api/favorites/glazes/{id}", glaze).exchange().expectStatus().isOk();
        // Someone else's draft cannot be starred through the API: it is a stale row, which stays out of the answer.
        UUID foreignDraft = draft(bea, glaze);
        UUID ownDraft = draft(ada, glaze);
        ada.put().uri("/api/favorites/combinations/{id}", ownDraft).exchange().expectStatus().isOk();
        jdbc.update("insert into favorite (id, user_id, combination_id) values (?, ?, ?)",
                UUID.randomUUID().toString(), me(ada).id().toString(), foreignDraft.toString());

        var expected = new ArrayList<>(star(ada, bea, glaze, 1));
        expected.add(ownDraft);
        var one = measured(() -> favorites(ada));
        assertEquals(expected.stream().sorted().toList(), one.result().combinationIds());
        assertEquals(List.of(glaze), one.result().glazeIds());

        expected.addAll(star(ada, bea, glaze, 29));
        var many = measured(() -> favorites(ada));
        assertEquals(expected.stream().sorted().toList(), many.result().combinationIds());
        assertFalse(many.result().combinationIds().contains(foreignDraft));
        assertEquals(one.statements(), many.statements());

        // The author's own draft is kept for the author only.
        assertTrue(favorites(bea).combinationIds().isEmpty());
    }
}
