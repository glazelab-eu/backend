package eu.glazelab.api.controller;

import eu.glazelab.api.dto.CombinationDtos.CombinationView;
import eu.glazelab.api.model.FinalFiring;
import eu.glazelab.api.model.FiringAtmosphere;
import eu.glazelab.api.model.PhotoDetails;
import eu.glazelab.api.support.ApiTestBase;
import eu.glazelab.api.support.TestImages;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.client.RestTestClient;
import static org.junit.jupiter.api.Assertions.*;

/** The final firing: optional fields on the combination, checked, trimmed and replaced as a whole. */
class CombinationFiringApiTest extends ApiTestBase {
    private static Map<String, Object> body(String title, UUID glaze, Map<String, ?> firing) {
        var body = new HashMap<String, Object>(Map.of("title", title,
                "steps", List.of(Map.of("glazeId", glaze, "coats", 2))));
        body.putAll(firing);
        return body;
    }
    private static RestTestClient.ResponseSpec create(RestTestClient client, Map<String, Object> body) {
        return client.post().uri("/api/combinations").body(body).exchange();
    }
    private static RestTestClient.ResponseSpec update(RestTestClient client, UUID id, Map<String, Object> body) {
        return client.put().uri("/api/combinations/{id}", id).body(body).exchange();
    }
    private static Map<String, Object> full() {
        return Map.of("cone", " 6 ", "temperatureC", 1222, "atmosphere", "OXIDATION",
                "clayBody", "  Speckled stoneware ", "kiln", " Club electric ", "firedOn", "2026-10-12");
    }
    private static void assertFiring(CombinationView view, String cone, Integer temperature,
            FiringAtmosphere atmosphere, String clayBody, String kiln, LocalDate firedOn) {
        assertEquals(cone, view.cone());
        assertEquals(temperature, view.temperatureC());
        assertEquals(atmosphere, view.atmosphere());
        assertEquals(clayBody, view.clayBody());
        assertEquals(kiln, view.kiln());
        assertEquals(firedOn, view.firedOn());
    }

    @Test void theFinalFiringIsSavedTrimmedAndShownWithThePublishedCombination() {
        var author = user("Author"); var other = user("Other");
        UUID g = addGlaze(author, unique("Firing"), "Blue", "PC-20");
        UUID id = created(create(author, body(unique("Fired"), g, full())));
        for (var client : List.of(author, other, guest()))
            assertFiring(combination(client, id).orElseThrow(), "6", 1222, FiringAtmosphere.OXIDATION,
                    "Speckled stoneware", "Club electric", LocalDate.of(2026, 10, 12));

        // Nothing recorded is null throughout, and blank text counts as nothing.
        UUID plain = created(create(author, body(unique("Plain"), g, Map.of("clayBody", "   ", "kiln", ""))));
        assertFiring(combination(other, plain).orElseThrow(), null, null, null, null, null, null);
    }

    @Test void anEditSetsTheFieldsItSendsClearsNullsAndKeepsMissingOnes() {
        var author = user("Author");
        UUID g = addGlaze(author, unique("Edit"), "Blue", "PC-20");
        UUID id = created(create(author, body(unique("Edited"), g, full())));

        // A client from before the final firing sends none of the fields: the stored firing stays.
        update(author, id, body("Edited", g, Map.of())).expectStatus().isNoContent();
        assertFiring(combination(author, id).orElseThrow(), "6", 1222, FiringAtmosphere.OXIDATION,
                "Speckled stoneware", "Club electric", LocalDate.of(2026, 10, 12));

        var changed = body("Edited", g, Map.of("cone", "04", "atmosphere", "REDUCTION", "kiln", "  "));
        changed.put("temperatureC", null);
        changed.put("firedOn", null);
        update(author, id, changed).expectStatus().isNoContent();
        assertFiring(combination(author, id).orElseThrow(), "04", null, FiringAtmosphere.REDUCTION,
                "Speckled stoneware", null, null);

        var cleared = body("Edited", g, Map.of());
        for (String field : List.of("cone", "temperatureC", "atmosphere", "clayBody", "kiln", "firedOn"))
            cleared.put(field, null);
        update(author, id, cleared).expectStatus().isNoContent();
        assertFiring(combination(author, id).orElseThrow(), null, null, null, null, null, null);
    }

    @Test void theFiringStaysEditableWhileOtherPeoplesPhotosLockTheSteps() {
        var author = user("Author"); var uploader = user("Uploader");
        UUID g = addGlaze(author, unique("Locked"), "Blue", "PC-20");
        UUID id = created(create(author, body(unique("Locked"), g, Map.of("cone", "6"))));
        created(upload(uploader, id, Map.of("visibility", "PUBLIC", "kind", "TEST_TILE"), TestImages.png(24, 24)));
        assertTrue(combination(author, id).orElseThrow().stepsLocked());

        update(author, id, body("Locked", g, Map.of("cone", "10", "clayBody", "Porcelain")))
                .expectStatus().isNoContent();
        var view = combination(author, id).orElseThrow();
        assertEquals("10", view.cone());
        assertEquals("Porcelain", view.clayBody());
    }

    @Test void invalidFieldsAreRefusedWithTheirReason() {
        var author = user("Author");
        UUID g = addGlaze(author, unique("Invalid"), "Blue", "PC-20");
        // Public listings also contain other tests' valid fixtures; they are not failed writes by this author.
        created(create(user("Other"), body(unique("Bad upload"), g, Map.of())));
        for (String cone : List.of("0", "00", "006", "123", "6/7", "cone 6", "6½", "-6"))
            assertError(create(author, body("Bad cone", g, Map.of("cone", cone))), 400, FinalFiring.INVALID_CONE);
        for (int temperature : List.of(99, 1601, -20))
            assertError(create(author, body("Bad heat", g, Map.of("temperatureC", temperature))), 400,
                    FinalFiring.INVALID_TEMPERATURE);
        assertError(create(author, body("Long clay", g, Map.of("clayBody", "c".repeat(121)))), 400,
                FinalFiring.CLAY_BODY_TOO_LONG);
        assertError(create(author, body("Long kiln", g, Map.of("kiln", "k".repeat(81)))), 400,
                FinalFiring.KILN_TOO_LONG);
        // Unknown and numeric atmospheres and impossible dates are the generic invalid input.
        for (Object atmosphere : List.of("SMOKY", "oxidation", 0))
            assertError(create(author, body("Bad air", g, Map.of("atmosphere", atmosphere))), 400,
                    "Please check the form fields and try again.");
        assertError(create(author, body("Bad date", g, Map.of("firedOn", "2026-13-01"))), 400,
                "Please check the form fields and try again.");
        assertTrue(combinations(author).stream().filter(CombinationView::own).noneMatch(view -> view.title().startsWith("Bad")
                || view.title().startsWith("Long")));

        // The limits themselves pass; the length counts after trimming.
        UUID edge = created(create(author, body(unique("Edge"), g, Map.of("cone", "022", "temperatureC", 100,
                "clayBody", " " + "c".repeat(120) + " ", "kiln", "k".repeat(80)))));
        var view = combination(author, edge).orElseThrow();
        assertEquals("022", view.cone());
        assertEquals(120, view.clayBody().length());

        update(author, edge, body("Edge", g, Map.of("temperatureC", 1600))).expectStatus().isNoContent();
        assertEquals(1600, combination(author, edge).orElseThrow().temperatureC());

        // An edit is checked the same way and leaves the stored firing as it was.
        assertError(update(author, edge, body("Edge", g, Map.of("temperatureC", 1601))), 400,
                FinalFiring.INVALID_TEMPERATURE);
        assertEquals("022", combination(author, edge).orElseThrow().cone());
    }

    @Test void theFiringDateIsWithinThePhotoRangeAndAnythingElseIsA400() {
        var author = user("Author");
        UUID g = addGlaze(author, unique("Dates"), "Blue", "PC-20");
        // Too early and too late, including years PostgreSQL cannot store (a 409 before this check).
        for (String date : List.of("1899-12-31", "0001-01-01", "-4714-01-01", "2101-01-01", "9999-12-31", "+5874898-01-01")) {
            assertError(create(author, body("Bad " + date, g, Map.of("firedOn", date))), 400, PhotoDetails.DATE_OUT_OF_RANGE);
            assertError(update(author, created(create(author, body(unique("Dated"), g, Map.of()))), body("Bad", g, Map.of("firedOn", date))),
                    400, PhotoDetails.DATE_OUT_OF_RANGE);
        }
        assertTrue(combinations(author).stream().filter(CombinationView::own).noneMatch(view -> view.title().startsWith("Bad")));

        // Both ends are inclusive; no date stays allowed.
        UUID edge = created(create(author, body(unique("Early"), g, Map.of("firedOn", "1900-01-01"))));
        assertEquals(PhotoDetails.EARLIEST, combination(author, edge).orElseThrow().firedOn());
        update(author, edge, body("Late", g, Map.of("firedOn", "2100-12-31"))).expectStatus().isNoContent();
        assertEquals(PhotoDetails.LATEST, combination(author, edge).orElseThrow().firedOn());
        var cleared = new HashMap<String, Object>();
        cleared.put("firedOn", null);
        update(author, edge, body("None", g, cleared)).expectStatus().isNoContent();
        assertNull(combination(author, edge).orElseThrow().firedOn());
    }

    @Test void aDraftsFiringReachesOnlyItsAuthor() {
        var author = user("Author"); var other = user("Other");
        UUID g = addGlaze(author, unique("Draft"), "Blue", "PC-20");
        String secret = unique("Secret clay");
        var draft = body(unique("Draft"), g, Map.of("clayBody", secret, "kiln", secret, "cone", "9"));
        draft.put("draft", true);
        UUID id = created(create(author, draft));
        assertEquals(secret, combination(author, id).orElseThrow().clayBody());

        for (var client : List.of(other, guest())) {
            String listing = client.get().uri("/api/combinations").exchange().expectStatus().isOk()
                    .expectBody(String.class).returnResult().getResponseBody();
            assertNotNull(listing);
            assertFalse(listing.contains(secret));
            assertTrue(combination(client, id).isEmpty());
        }
        author.post().uri("/api/combinations/{id}/publish", id).exchange().expectStatus().isNoContent();
        assertEquals(secret, combination(other, id).orElseThrow().clayBody());
    }

    @Test void aVariationKeepsOnlyTheFiringItSends() {
        var author = user("Author");
        UUID g = addGlaze(author, unique("Variation"), "Blue", "PC-20");
        UUID source = created(create(author, body(unique("Source"), g, full())));
        // The client copies the source's fields into the form; the API stores what it receives.
        var copied = body(unique("Copy"), g, Map.of("cone", "6", "atmosphere", "OXIDATION"));
        copied.put("basedOnId", source);
        UUID variation = created(create(author, copied));
        assertFiring(combination(author, variation).orElseThrow(), "6", null, FiringAtmosphere.OXIDATION,
                null, null, null);
        assertEquals(source, combination(author, variation).orElseThrow().basedOnId());
    }
}
