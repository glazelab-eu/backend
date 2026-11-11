package eu.glazelab.api.controller;

import eu.glazelab.api.dto.CombinationDtos.CombinationView;
import eu.glazelab.api.dto.CombinationDtos.StepView;
import eu.glazelab.api.model.StepType;
import eu.glazelab.api.support.ApiTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.junit.jupiter.api.Assertions.*;

class CombinationApiTest extends ApiTestBase {
    private static List<UUID> listedIds(RestTestClient client, List<UUID> among) {
        return combinations(client).stream().map(CombinationView::id).filter(among::contains).toList();
    }

    private static RestTestClient.ResponseSpec create(RestTestClient client, Map<String, Object> body) {
        return client.post().uri("/api/combinations").body(body).exchange();
    }

    private static Map<String, Object> custom(String label) {
        return Map.of("type", "CUSTOM", "label", label);
    }

    @Test void stepsKeepTheirOrderAndAreListedNewestFirstToEveryone() {
        RestTestClient ada = user("Ada");
        String brand = unique("Steps");
        UUID blue = addGlaze(ada, brand, "Blue", "PC-20");
        UUID white = addGlaze(ada, brand, "White", "PC-21");
        UUID first = addCombination(ada, unique("Layered"), blue, white);
        UUID second = addCombination(ada, unique("Single"), white);

        List<StepView> steps = combination(guest(), first).orElseThrow().steps();
        assertEquals(List.of(1, 2), steps.stream().map(StepView::position).toList());
        assertEquals(List.of(2, 1), steps.stream().map(StepView::coats).toList());
        assertEquals(List.of(StepType.GLAZE, StepType.GLAZE), steps.stream().map(StepView::type).toList());
        assertEquals(List.of(second, first), listedIds(guest(), List.of(first, second)));
    }

    @Test void glazeAndCustomStepsInterleaveAndRoundTripInOrder() {
        RestTestClient ada = user("Ada");
        String brand = unique("Mixed");
        UUID blue = addGlaze(ada, brand, "Blue", "PC-20");
        UUID id = created(create(ada, Map.of("title", unique("Fired twice"), "steps", List.of(
                Map.of("type", "CUSTOM", "label", "  Bisque firing ", "notes", "Cone 04"),
                Map.of("glazeId", blue, "coats", 2, "method", "Dip"),
                Map.of("type", "GLAZE", "glazeId", blue, "coats", 1),
                Map.of("type", "CUSTOM", "label", "Glaze firing")))));
        List<StepView> steps = combination(guest(), id).orElseThrow().steps();
        assertEquals(List.of(
                new StepView(1, StepType.CUSTOM, null, null, "Bisque firing", null, "Cone 04", null, null),
                new StepView(2, StepType.GLAZE, blue, 2, null, "Dip", null, brand, "Blue"),
                new StepView(3, StepType.GLAZE, blue, 1, null, null, null, brand, "Blue"),
                new StepView(4, StepType.CUSTOM, null, null, "Glaze firing", null, null, null, null)), steps);
    }

    @Test void aSequenceOfOnlyCustomStepsIsValidAndListed() {
        RestTestClient ada = user("Ada");
        UUID id = created(create(ada, Map.of("title", unique("Firing only"),
                "steps", List.of(custom("Bisque firing"), custom("Re-firing")))));
        CombinationView view = combination(guest(), id).orElseThrow();
        assertEquals(List.of("Bisque firing", "Re-firing"), view.steps().stream().map(StepView::label).toList());
        assertTrue(view.steps().stream().allMatch(s -> s.glazeId() == null && s.coats() == null));
    }

    @Test void authorshipIsNamedAndOwnOnlyInTheAuthorsSession() {
        RestTestClient ada = user("Ada");
        RestTestClient bea = user("Bea");
        UUID adaId = me(ada).id();
        UUID combo = addCombination(ada, unique("Authored"), addGlaze(ada, unique("Authored"), "Blue", "PC-20"));
        CombinationView asAuthor = combination(ada, combo).orElseThrow();
        assertEquals(me(ada).displayName(), asAuthor.author());
        assertEquals(adaId, asAuthor.authorId());
        assertTrue(asAuthor.own());
        for (RestTestClient other : List.of(bea, guest())) {
            CombinationView seen = combination(other, combo).orElseThrow();
            assertEquals(adaId, seen.authorId());
            assertEquals(asAuthor.author(), seen.author());
            assertFalse(seen.own());
        }
    }

    @Test void createdCombinationsAreReturnedCompletely() {
        RestTestClient ada = user("Ada");
        String brand = unique("Complete");
        UUID glaze = addGlaze(ada, brand, "Shino", "PC-40");
        String title = unique("Full");
        UUID id = created(create(ada, Map.of("title", "  " + title + " ", "description", "Cone 6",
                "steps", List.of(Map.of("glazeId", glaze, "coats", 3, "method", "Pour", "notes", "Thick")))));
        CombinationView view = combination(guest(), id).orElseThrow();
        assertEquals(title, view.title());
        assertEquals("Cone 6", view.description());
        assertEquals(me(ada).displayName(), view.author());
        assertNotNull(view.createdAt());
        assertEquals(List.of(new StepView(1, StepType.GLAZE, glaze, 3, null, "Pour", "Thick", brand, "Shino")), view.steps());
        assertEquals(List.of(), view.images());
    }

    @Test void invalidCombinationsAreRejectedWithoutSavingAnything() {
        RestTestClient ada = user("Ada");
        UUID glaze = addGlaze(ada, unique("Invalid"), "Shino", "PC-40");
        String title = unique("Rejected");
        Map<String, Object> layer = Map.of("glazeId", glaze, "coats", 1);
        List<Object> withNull = new ArrayList<>();
        withNull.add(null);
        List<Object> tooMany = new ArrayList<>();
        for (int i = 0; i < 31; i++) tooMany.add(layer);

        create(ada, Map.of("title", title, "steps", List.of())).expectStatus().isBadRequest();
        create(ada, Map.of("title", title, "steps", withNull)).expectStatus().isBadRequest();
        create(ada, Map.of("title", title, "steps", tooMany)).expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("steps: Maximum 30 steps");
        create(ada, Map.of("title", " ", "steps", List.of(layer))).expectStatus().isBadRequest();
        create(ada, Map.of("title", title, "steps", List.of(Map.of("glazeId", glaze, "coats", 0)))).expectStatus().isBadRequest();
        create(ada, Map.of("title", title, "steps", List.of(layer, Map.of("glazeId", UUID.randomUUID(), "coats", 1))))
                .expectStatus().isBadRequest();
        create(guest(), Map.of("title", title, "steps", List.of(layer))).expectStatus().isUnauthorized();
        assertTrue(combinations(guest()).stream().noneMatch(c -> c.title().equals(title)));
    }

    @Test void stepFieldsAreValidatedByType() {
        RestTestClient ada = user("Ada");
        UUID glaze = addGlaze(ada, unique("Typed"), "Shino", "PC-40");
        String title = unique("Typed");
        Map<String, Object> glazeStep = Map.of("glazeId", glaze, "coats", 1);
        List<Map<String, Object>> invalid = List.of(
                Map.of("type", "GLAZE", "coats", 1),
                Map.of("type", "GLAZE", "glazeId", glaze),
                Map.of("glazeId", glaze, "coats", 1, "label", "Bisque firing"),
                Map.of("type", "CUSTOM"),
                Map.of("type", "CUSTOM", "label", "   "),
                Map.of("type", "CUSTOM", "label", "x".repeat(81)),
                Map.of("type", "CUSTOM", "label", "Firing", "glazeId", glaze),
                Map.of("type", "CUSTOM", "label", "Firing", "coats", 1),
                Map.of("type", "CUSTOM", "label", "Firing", "method", "Brush"),
                Map.of("type", "CUSTOM", "label", "Firing", "notes", "x".repeat(501)),
                Map.of("type", "KILN", "label", "Firing"));
        for (Map<String, Object> step : invalid)
            create(ada, Map.of("title", title, "steps", List.of(glazeStep, step))).expectStatus().isBadRequest();
        List<Object> thirty = new ArrayList<>(), thirtyOne = new ArrayList<>();
        for (int i = 0; i < 30; i++) thirty.add(custom("Step " + i));
        thirtyOne.addAll(thirty);
        thirtyOne.add(custom("One too many"));
        create(ada, Map.of("title", title, "steps", thirtyOne)).expectStatus().isBadRequest();
        assertTrue(combinations(guest()).stream().noneMatch(c -> c.title().equals(title)));
        UUID ok = created(create(ada, Map.of("title", unique("Thirty"), "steps", thirty)));
        assertEquals(30, combination(guest(), ok).orElseThrow().steps().size());
    }
}
