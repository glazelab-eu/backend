package eu.glazelab.api.controller;

import eu.glazelab.api.support.ApiTestBase;
import eu.glazelab.api.support.TestImages;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.client.RestTestClient;
import static org.junit.jupiter.api.Assertions.*;

/** A variation remembers its source for those who may see it, and photos name their uploader. */
class CombinationVariationApiTest extends ApiTestBase {
    private static UUID save(RestTestClient client, String title, UUID glaze, UUID basedOn, boolean draft) {
        var body = new HashMap<String, Object>(Map.of("title", title, "draft", draft,
                "steps", List.of(Map.of("glazeId", glaze, "coats", 2))));
        if (basedOn != null) body.put("basedOnId", basedOn);
        return created(client.post().uri("/api/combinations").body(body).exchange());
    }
    private static UUID basedOn(RestTestClient client, UUID id) {
        return combination(client, id).orElseThrow().basedOnId();
    }

    @Test void aVariationNamesItsPublishedSourceToEveryone() {
        var author = user("Author"); var other = user("Other");
        UUID g = addGlaze(author, unique("Source"), "Blue", "PC-20");
        UUID source = addCombination(author, unique("Original"), g);
        UUID mine = save(author, unique("Mine"), g, source, false);
        UUID theirs = save(other, unique("Theirs"), g, source, false);
        UUID original = addCombination(other, unique("Other original"), g);

        for (var client : List.of(author, other, guest())) {
            assertEquals(source, basedOn(client, mine));
            assertEquals(source, basedOn(client, theirs));
            assertNull(basedOn(client, source));
            assertNull(basedOn(client, original));
        }
        // Editing keeps the source and ignores any other one the request names.
        author.put().uri("/api/combinations/{id}", mine).body(Map.of("title", "Renamed", "basedOnId", original,
                "steps", List.of(Map.of("glazeId", g, "coats", 2)))).exchange().expectStatus().isNoContent();
        assertEquals(source, basedOn(author, mine));
        assertEquals("Renamed", combination(author, mine).orElseThrow().title());
    }

    @Test void anUnknownOrInvisibleSourceIsLeftOutWithoutAnError() {
        var author = user("Author"); var other = user("Other");
        UUID g = addGlaze(author, unique("Hidden"), "Blue", "PC-20");
        UUID secret = save(author, unique("Secret draft"), g, null, true);

        UUID unknown = save(other, unique("Unknown source"), g, UUID.randomUUID(), false);
        UUID guessed = save(other, unique("Guessed draft"), g, secret, false);
        assertNull(basedOn(other, unknown));
        assertNull(basedOn(other, guessed));
        assertNull(basedOn(author, guessed));
    }

    @Test void aDraftSourceIsNamedOnlyToItsAuthorUntilPublished() {
        var author = user("Author"); var other = user("Other");
        UUID g = addGlaze(author, unique("Draft"), "Blue", "PC-20");
        UUID source = save(author, unique("Draft source"), g, null, true);
        UUID variation = save(author, unique("Published variation"), g, source, false);
        UUID draftVariation = save(author, unique("Draft variation"), g, source, true);

        assertEquals(source, basedOn(author, variation));
        assertEquals(source, basedOn(author, draftVariation));
        // Others see the published variation but are not told its source exists.
        assertNull(basedOn(other, variation));
        assertNull(basedOn(guest(), variation));
        assertTrue(combination(other, draftVariation).isEmpty());

        author.post().uri("/api/combinations/{id}/publish", source).exchange().expectStatus().isNoContent();
        assertEquals(source, basedOn(other, variation));
        assertEquals(source, basedOn(guest(), variation));
        assertTrue(combination(other, draftVariation).isEmpty());
    }

    @Test void deletingTheSourceClearsTheLinkOfItsVariations() {
        var author = user("Author"); var other = user("Other");
        UUID g = addGlaze(author, unique("Removed"), "Blue", "PC-20");
        UUID source = addCombination(author, unique("To delete"), g);
        UUID variation = save(other, unique("Variation"), g, source, false);
        assertEquals(source, basedOn(other, variation));

        author.delete().uri("/api/combinations/{id}", source).exchange().expectStatus().isNoContent();
        assertTrue(combination(other, source).isEmpty());
        assertNull(basedOn(other, variation));
        assertNull(basedOn(guest(), variation));
    }

    @Test void deletingAnAccountWithADraftSourceKeepsItsPublishedVariationWithoutTheLink() {
        var author = user("Author"); var other = user("Other");
        UUID g = addGlaze(other, unique("Account"), "Blue", "PC-20");
        UUID source = save(author, unique("Draft source"), g, null, true);
        UUID draftVariation = save(author, unique("Draft variation"), g, source, true);
        UUID variation = save(author, unique("Published variation"), g, source, false);
        UUID theirs = save(other, unique("Their variation"), g, variation, false);

        author.post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange().expectStatus().isNoContent();
        assertNull(basedOn(other, variation));
        assertEquals(variation, basedOn(other, theirs));
        assertEquals(0, jdbc.queryForObject("select count(*) from combination where id in (?, ?)",
                Integer.class, source, draftVariation));
    }

    @Test void photosNameTheirUploaderOnlyWhereTheViewerMaySeeThem() {
        var author = user("Author");
        var uploader = user("Uploader");
        var other = user("Other");
        UUID g = addGlaze(author, unique("Photos"), "Blue", "PC-20");
        UUID id = addCombination(author, unique("Photographed"), g);
        var tile = Map.of("visibility", "PUBLIC", "kind", "TEST_TILE");
        UUID shared = created(upload(uploader, id, tile, TestImages.png(24, 24)));
        UUID mine = created(upload(author, id, Map.of("visibility", "PUBLIC", "kind", "FINISHED"), TestImages.png(24, 24)));
        // A photo on someone else's combination must stay visible to its author: a club both share.
        UUID club = createClub(author, unique("Shared"));
        joinClub(author, club, uploader);
        UUID hidden = created(upload(uploader, id, Map.of("visibility", "CLUB", "clubId", club.toString(), "kind", "TEST_TILE"),
                TestImages.png(24, 24)));

        String name = me(uploader).displayName();
        for (var client : List.of(other, guest())) {
            var images = combination(client, id).orElseThrow().images();
            assertEquals(name, images.stream().filter(image -> image.id().equals(shared)).findFirst().orElseThrow().uploader());
            // The club photo is not listed outside the club, so its uploader name goes nowhere.
            assertEquals(2, images.size());
            assertTrue(images.stream().noneMatch(image -> image.id().equals(hidden)));
        }
        for (var client : List.of(uploader, author))
            assertEquals(name, combination(client, id).orElseThrow().images().stream()
                    .filter(image -> image.id().equals(hidden)).findFirst().orElseThrow().uploader());
        var own = combination(author, id).orElseThrow().images().stream()
                .filter(image -> image.id().equals(mine)).findFirst().orElseThrow();
        assertTrue(own.own());
        assertEquals(me(author).displayName(), own.uploader());

        // A deleted account leaves its shared photo behind without a name.
        uploader.post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange().expectStatus().isNoContent();
        var after = combination(other, id).orElseThrow().images();
        assertNull(after.stream().filter(image -> image.id().equals(shared)).findFirst().orElseThrow().uploader());
        assertTrue(after.stream().noneMatch(image -> image.id().equals(hidden)));
    }
}
