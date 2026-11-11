package eu.glazelab.api.controller;

import eu.glazelab.api.controller.FavoriteController.FavoritesView;
import eu.glazelab.api.dto.FavoriteDtos.FavoriteCountsView;
import eu.glazelab.api.dto.FavoriteDtos.FavoriteCountView;
import eu.glazelab.api.support.ApiTestBase;
import eu.glazelab.api.repository.AppUserRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doReturn;

@TestPropertySource(properties = {"spring.jpa.properties.hibernate.generate_statistics=true", "spring.jpa.properties.hibernate.session.events.log=false"})
class FavoriteApiTest extends ApiTestBase {
    @MockitoSpyBean AppUserRepository users;

    private static FavoritesView favorites(RestTestClient client) {
        return body(client.get().uri("/api/favorites").exchange().expectStatus().isOk(), FavoritesView.class);
    }

    @Test void publicCountsAggregateAccountsAndNeverRevealDraftsOrWhoStarred() {
        var ada = user("CountAda");
        var bea = user("CountBea");
        UUID glaze = addGlaze(ada, unique("Count glaze"), "Blue", "PC-20");
        UUID combination = addCombination(ada, unique("Count combination"), glaze);
        UUID draft = created(ada.post().uri("/api/combinations").body(Map.of(
                "title", "Hidden draft", "draft", true, "steps", List.of()))
                .exchange());
        for (var client : List.of(ada, bea)) {
            client.put().uri("/api/favorites/glazes/{id}", glaze).exchange().expectStatus().isOk();
            client.put().uri("/api/favorites/combinations/{id}", combination).exchange().expectStatus().isOk();
        }
        // A repeated star is still one favourite.
        ada.put().uri("/api/favorites/glazes/{id}", glaze).exchange().expectStatus().isOk();
        ada.put().uri("/api/favorites/combinations/{id}", draft).exchange().expectStatus().isOk();
        var measured = measured(() -> body(guest().get().uri("/api/favorites/counts").exchange().expectStatus().isOk(), FavoriteCountsView.class));
        assertEquals(2, measured.statements());
        var counts = measured.result();
        assertTrue(counts.glazes().contains(new FavoriteCountView(glaze, 2)));
        assertTrue(counts.combinations().contains(new FavoriteCountView(combination, 2)));
        assertTrue(counts.combinations().stream().noneMatch(row -> row.id().equals(draft)));
        String json = guest().get().uri("/api/favorites/counts").exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        assertNotNull(json);
        assertFalse(json.contains(me(ada).id().toString()));
        assertFalse(json.contains("CountAda"));
        assertFalse(json.contains("user"));
        bea.delete().uri("/api/favorites/glazes/{id}", glaze).exchange().expectStatus().isOk();
        counts = body(guest().get().uri("/api/favorites/counts").exchange().expectStatus().isOk(), FavoriteCountsView.class);
        assertTrue(counts.glazes().contains(new FavoriteCountView(glaze, 1)));
    }

    @Test void favoritesArePrivateAndIdempotent() {
        RestTestClient ada = user("Ada");
        RestTestClient bea = user("Bea");
        UUID glaze = addGlaze(ada, unique("Favorites"), "Blue", "PC-20");
        UUID combo = addCombination(ada, unique("Starred"), glaze);
        String glazePath = "/api/favorites/glazes/" + glaze;
        String comboPath = "/api/favorites/combinations/" + combo;

        guest().get().uri("/api/favorites").exchange().expectStatus().isUnauthorized();
        guest().put().uri(glazePath).exchange().expectStatus().isUnauthorized();
        for (String path : List.of(glazePath, comboPath)) {
            ada.put().uri(path).exchange().expectStatus().isOk();
            ada.put().uri(path).exchange().expectStatus().isOk();
        }
        assertEquals(new FavoritesView(List.of(glaze), List.of(combo)), favorites(ada));
        assertEquals(new FavoritesView(List.of(), List.of()), favorites(bea));

        bea.delete().uri(glazePath).exchange().expectStatus().isOk();
        assertEquals(List.of(glaze), favorites(ada).glazeIds());
        bea.put().uri(comboPath).exchange().expectStatus().isOk();

        for (String path : List.of(glazePath, comboPath)) {
            ada.delete().uri(path).exchange().expectStatus().isOk();
            ada.delete().uri(path).exchange().expectStatus().isOk();
        }
        assertEquals(new FavoritesView(List.of(), List.of()), favorites(ada));
        assertEquals(List.of(combo), favorites(bea).combinationIds());
    }

    @Test void unknownTargetsAreNotFound() {
        RestTestClient ada = user("Ada");
        ada.put().uri("/api/favorites/glazes/{id}", UUID.randomUUID()).exchange().expectStatus().isNotFound();
        ada.put().uri("/api/favorites/combinations/{id}", UUID.randomUUID()).exchange().expectStatus().isNotFound();
        ada.delete().uri("/api/favorites/combinations/{id}", UUID.randomUUID()).exchange().expectStatus().isOk();
    }

    @Test void accountGoneBeforeTheLockAnswersSignInRequired() {
        RestTestClient ada = user("Ada");
        UUID glaze = addGlaze(ada, unique("Gone"), "Blue", "PC-21");
        UUID combo = addCombination(ada, unique("Gone combo"), glaze);
        // The session filter still finds the account; it is deleted before the mutation locks it.
        UUID adaId = me(ada).id();
        doReturn(Optional.empty()).when(users).lockById(adaId);
        for (String path : List.of("/api/favorites/glazes/" + glaze, "/api/favorites/combinations/" + combo)) {
            ada.put().uri(path).exchange().expectStatus().isUnauthorized();
            ada.delete().uri(path).exchange().expectStatus().isUnauthorized();
        }
    }
}
