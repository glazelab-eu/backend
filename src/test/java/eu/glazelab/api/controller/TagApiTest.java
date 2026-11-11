package eu.glazelab.api.controller;

import eu.glazelab.api.dto.GlazeDtos.GlazeView;
import eu.glazelab.api.dto.CombinationDtos.CombinationView;
import eu.glazelab.api.dto.TagDtos.TagsView;
import eu.glazelab.api.model.Tags;
import eu.glazelab.api.support.ApiTestBase;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.junit.jupiter.api.Assertions.*;

class TagApiTest extends ApiTestBase {
    private static RestTestClient.ResponseSpec edit(RestTestClient client, String kind, UUID id,
            List<String> add, List<String> remove) {
        var body = new HashMap<String, Object>();
        if (add != null) body.put("add", add);
        if (remove != null) body.put("remove", remove);
        return client.patch().uri("/api/" + kind + "/{id}/tags", id).body(body).exchange();
    }

    private static List<String> edited(RestTestClient client, String kind, UUID id, List<String> add, List<String> remove) {
        return body(edit(client, kind, id, add, remove).expectStatus().isOk(), TagsView.class).tags();
    }

    private static List<String> glazeTags(RestTestClient client, UUID id) {
        return glazes(client).stream().filter(g -> g.id().equals(id)).findFirst().map(GlazeView::tags).orElseThrow();
    }

    private static List<String> combinationTags(RestTestClient client, UUID id) {
        return combination(client, id).map(CombinationView::tags).orElseThrow();
    }

    @Test void newGlazesAndCombinationsStoreNormalizedDistinctTags() {
        RestTestClient ada = user("Ada");
        String manufacturer = unique("Tagged");
        UUID glaze = created(ada.post().uri("/api/glazes").body(Map.of("newManufacturer",
                Map.of("name", manufacturer, "shortId", shortId()), "name", "Seladon", "code", "1",
                "tags", List.of("  Satin Matte ", "satin_matte", "Glänzend", "glanzend"))).exchange());
        assertEquals(List.of("glanzend", "glänzend", "satin-matte"), glazeTags(guest(), glaze));

        UUID combination = created(ada.post().uri("/api/combinations").body(Map.of("title", unique("Layered"),
                "steps", List.of(Map.of("glazeId", glaze, "coats", 2)), "tags", List.of("Cone 6", "cone--6"))).exchange());
        assertEquals(List.of("cone-6"), combinationTags(guest(), combination));
        // Without tags, the lists are empty rather than missing.
        assertEquals(List.of(), combinationTags(ada, addCombination(ada, unique("Plain"), glaze)));
    }

    @Test void anySignedInUserAddsAndRemovesTagsOfAnyGlazeOrCombination() {
        RestTestClient ada = user("Ada");
        RestTestClient bea = user("Bea");
        UUID glaze = addGlaze(ada, unique("Shared"), "Blue", "PC-20");
        UUID combination = addCombination(ada, unique("Shared"), glaze);
        for (String kind : List.of("glazes", "combinations")) {
            UUID id = kind.equals("glazes") ? glaze : combination;
            assertEquals(List.of("food-safe", "glossy"), edited(bea, kind, id, List.of("Glossy", " #food safe"), null));
            // Removals apply first and are normalized too; absent tags and repeated adds change nothing.
            assertEquals(List.of("food-safe", "satin"), edited(ada, kind, id, List.of("satin", "food-safe"), List.of("GLOSSY", "absent")));
            assertEquals(List.of("satin"), edited(ada, kind, id, null, List.of("Food Safe")));
        }
        assertEquals(List.of("satin"), glazeTags(guest(), glaze));
        assertEquals(List.of("satin"), combinationTags(guest(), combination));
    }

    /**
     * BE-9: the author's PUT with {@code baseTags} applies only what the form changed, so a tag another account added
     * (or removed) after the form opened stays as it is; without {@code baseTags} the PUT still replaces the set.
     */
    @Test void theAuthorsEditKeepsTagsOthersChangedMeanwhile() {
        RestTestClient ada = user("Ada");
        RestTestClient bea = user("Bea");
        UUID glaze = addGlaze(ada, unique("Kept"), "Blue", "PC-20");
        UUID combination = addCombination(ada, unique("Kept"), glaze);
        edited(ada, "combinations", combination, List.of("glossy", "satin"), null);
        // Ada's form opens with glossy and satin; meanwhile Bea adds celadon and removes satin.
        edited(bea, "combinations", combination, List.of("celadon"), List.of("satin"));
        var put = new HashMap<String, Object>(Map.of("title", unique("Kept fixed"), "steps", List.of(Map.of("glazeId", glaze, "coats", 2))));
        put.put("baseTags", List.of("glossy", "satin"));
        // Ada removes glossy and adds Food Safe; satin is still in her form.
        put.put("tags", List.of("satin", "Food Safe"));
        ada.put().uri("/api/combinations/{id}", combination).body(put).exchange().expectStatus().isNoContent();
        assertEquals(List.of("celadon", "food-safe"), combinationTags(guest(), combination));

        put.remove("baseTags");
        put.put("tags", List.of("satin"));
        ada.put().uri("/api/combinations/{id}", combination).body(put).exchange().expectStatus().isNoContent();
        assertEquals(List.of("satin"), combinationTags(guest(), combination));
    }

    @Test void tagEditsNeedASignedInUserCsrfAndAnExistingItem() {
        RestTestClient ada = user("Ada");
        UUID glaze = addGlaze(ada, unique("Guarded"), "Blue", "PC-20");
        UUID combination = addCombination(ada, unique("Guarded"), glaze);
        for (String kind : List.of("glazes", "combinations")) {
            UUID id = kind.equals("glazes") ? glaze : combination;
            edit(guest(), kind, id, List.of("guest"), null).expectStatus().isUnauthorized();
            ada.patch().uri("/api/" + kind + "/{id}/tags", id).header(CSRF_HEADER, "wrong")
                    .body(Map.of("add", List.of("forged"))).exchange().expectStatus().isForbidden();
        }
        assertError(edit(ada, "glazes", UUID.randomUUID(), List.of("x"), null), 404, "Unknown glaze");
        assertError(edit(ada, "combinations", UUID.randomUUID(), List.of("x"), null), 404, "Combination not found");
        assertEquals(List.of(), glazeTags(ada, glaze));
        assertEquals(List.of(), combinationTags(ada, combination));
    }

    @Test void invalidTagsAndTooManyTagsAreRejectedWithoutChanges() {
        RestTestClient ada = user("Ada");
        UUID glaze = addGlaze(ada, unique("Limits"), "Blue", "PC-20");
        assertError(edit(ada, "glazes", glaze, List.of("ok", " -- "), null), 400, Tags.EMPTY);
        assertError(edit(ada, "glazes", glaze, List.of("a".repeat(41)), null), 400, Tags.TOO_LONG);
        List<String> twenty = IntStream.range(0, 20).mapToObj(i -> "tag " + i).toList();
        assertEquals(20, edited(ada, "glazes", glaze, twenty, null).size());
        assertError(edit(ada, "glazes", glaze, List.of("one more"), null), 400, Tags.TOO_MANY);
        assertEquals(20, glazeTags(ada, glaze).size());
        assertEquals(20, edited(ada, "glazes", glaze, List.of("one more"), List.of("tag 0")).size());
        assertError(ada.post().uri("/api/glazes").body(Map.of("newManufacturer", Map.of("name", unique("Bad"),
                "shortId", shortId()), "name", "Bad", "code", "1", "tags", List.of("!!"))).exchange(), 400, Tags.EMPTY);
    }

    @Test void concurrentEditsOfOneItemAllApply() throws Exception {
        RestTestClient ada = user("Ada");
        UUID glaze = addGlaze(ada, unique("Busy"), "Blue", "PC-20");
        List<RestTestClient> makers = IntStream.range(0, 6).mapToObj(i -> user("Maker")).toList();
        try (var pool = Executors.newFixedThreadPool(makers.size())) {
            List<Future<?>> edits = IntStream.range(0, makers.size()).<Future<?>>mapToObj(i -> pool.submit(() ->
                    edit(makers.get(i), "glazes", glaze, List.of("shared", "own " + i), null).expectStatus().isOk())).toList();
            for (Future<?> future : edits) future.get();
        }
        assertEquals(7, glazeTags(ada, glaze).size());
    }
}
