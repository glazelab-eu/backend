package eu.glazelab.api.controller;

import eu.glazelab.api.service.GlazeService;
import eu.glazelab.api.dto.GlazeDtos.BarcodesView;
import eu.glazelab.api.dto.GlazeDtos.GlazeView;
import eu.glazelab.api.dto.ImageDtos.ImageView;
import eu.glazelab.api.model.Barcodes;
import eu.glazelab.api.support.ApiTestBase;
import eu.glazelab.api.support.TestImages;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.junit.jupiter.api.Assertions.*;

class BarcodeApiTest extends ApiTestBase {

    /** The payload with its GS1 check digit appended. */
    static String withCheckDigit(String payload) {
        int sum = 0;
        for (int i = payload.length() - 1, weight = 3; i >= 0; i--, weight = 4 - weight) sum += (payload.charAt(i) - '0') * weight;
        return payload + (10 - sum % 10) % 10;
    }

    /** A random valid EAN-13 (in-store prefix 2), so tests sharing the server never collide. */
    static String ean13() {
        return withCheckDigit(String.format("2%011d", ThreadLocalRandom.current().nextLong(100_000_000_000L)));
    }

    /** A random valid UPC-A (12 digits); stored as "0" + it. */
    static String upcA() {
        return withCheckDigit(String.format("2%010d", ThreadLocalRandom.current().nextLong(10_000_000_000L)));
    }

    private static RestTestClient.ResponseSpec lookup(RestTestClient client, String barcode) {
        return client.get().uri("/api/barcodes/{barcode}", barcode).exchange();
    }

    private static RestTestClient.ResponseSpec edit(RestTestClient client, UUID glaze, List<String> add, List<String> remove) {
        var body = new HashMap<String, Object>();
        if (add != null) body.put("add", add);
        if (remove != null) body.put("remove", remove);
        return client.patch().uri("/api/glazes/{id}/barcodes", glaze).body(body).exchange();
    }

    private static List<String> edited(RestTestClient client, UUID glaze, List<String> add, List<String> remove) {
        return body(edit(client, glaze, add, remove).expectStatus().isOk(), BarcodesView.class).barcodes();
    }

    private static List<String> listed(RestTestClient client, UUID glaze) {
        return glazes(client).stream().filter(g -> g.id().equals(glaze)).findFirst().orElseThrow().barcodes();
    }

    private UUID addGlazeWithBarcode(RestTestClient client, String barcode) {
        return created(client.post().uri("/api/glazes").body(Map.of("newManufacturer", Map.of("name", unique("Scanned"),
                "shortId", shortId()), "name", "Blue", "code", "PC-20", "barcode", barcode)).exchange());
    }

    @Test void aNewGlazeKeepsItsNormalizedBarcodeAndAnyoneFindsItByBarcode() {
        RestTestClient ada = user("Ada");
        String upc = upcA();
        UUID glaze = addGlazeWithBarcode(ada, " " + upc.substring(0, 6) + "-" + upc.substring(6) + " ");
        assertEquals(List.of("0" + upc), listed(guest(), glaze));
        // UPC-A and its EAN-13 form find the same glaze, for guests and signed-in users.
        for (String value : List.of(upc, "0" + upc)) {
            GlazeView found = body(lookup(guest(), value).expectStatus().isOk(), GlazeView.class);
            assertEquals(glaze, found.id());
            assertEquals(List.of("0" + upc), found.barcodes());
        }
        lookup(ada, upc).expectStatus().isOk().expectHeader().valueMatches("Cache-Control", ".*no-store.*");
        // Without a barcode the list is empty, and a blank barcode counts as none.
        UUID plain = created(ada.post().uri("/api/glazes").body(Map.of("newManufacturer", Map.of("name", unique("Plain"),
                "shortId", shortId()), "name", "Plain", "code", "1", "barcode", " ")).exchange());
        assertEquals(List.of(), listed(ada, plain));
    }

    @Test void lookupsAnswer404ForUnknownAnd400ForInvalidValues() {
        assertError(lookup(guest(), ean13()), 404, CatalogController.UNKNOWN_BARCODE);
        assertError(lookup(user("Ada"), ean13()), 404, CatalogController.UNKNOWN_BARCODE);
        String wrongCheck = ean13();
        wrongCheck = wrongCheck.substring(0, 12) + (char) ('0' + (wrongCheck.charAt(12) - '0' + 1) % 10);
        for (String invalid : List.of(wrongCheck, "123", "abcdefghijklm", "40063813339310"))
            assertError(lookup(guest(), invalid), 400, Barcodes.INVALID);
    }

    @Test void lookupsShowOnlyThePicturesTheCallerMaySee() {
        RestTestClient ada = user("Ada");
        RestTestClient bea = user("Bea");
        String barcode = ean13();
        UUID glaze = addGlazeWithBarcode(ada, barcode);
        UUID own = created(uploadGlazeImage(ada, glaze, Map.of("visibility", "PRIVATE", "kind", "TEST_TILE"), TestImages.png(24, 24)));
        UUID shared = created(uploadGlazeImage(ada, glaze, Map.of("visibility", "PUBLIC", "kind", "FINISHED"), TestImages.png(24, 24)));
        assertEquals(List.of(shared, own), body(lookup(ada, barcode).expectStatus().isOk(), GlazeView.class)
                .images().stream().map(ImageView::id).toList());
        for (RestTestClient other : List.of(bea, guest())) {
            assertEquals(List.of(shared), body(lookup(other, barcode).expectStatus().isOk(), GlazeView.class)
                    .images().stream().map(ImageView::id).toList());
        }
    }

    @Test void barcodesAreUniqueInTheWholeLibrary() {
        RestTestClient ada = user("Ada");
        RestTestClient bea = user("Bea");
        String barcode = ean13();
        UUID first = addGlazeWithBarcode(ada, barcode);
        // Another manufacturer's new glaze, or another glaze of the same manufacturer, cannot take it.
        assertError(bea.post().uri("/api/glazes").body(Map.of("newManufacturer", Map.of("name", unique("Other"),
                "shortId", shortId()), "name", "Red", "code", "R1", "barcode", barcode)).exchange(), 409, GlazeService.BARCODE_TAKEN);
        UUID manufacturer = glazes(ada).stream().filter(g -> g.id().equals(first)).findFirst().orElseThrow().manufacturer().id();
        assertError(bea.post().uri("/api/glazes").body(Map.of("manufacturerId", manufacturer, "name", "Red", "code", "R2",
                "barcode", barcode)).exchange(), 409, GlazeService.BARCODE_TAKEN);
        UUID second = addGlaze(bea, unique("Second"), "Green", "G1");
        assertError(edit(bea, second, List.of(barcode), null), 409, GlazeService.BARCODE_TAKEN);
        assertEquals(List.of(), listed(bea, second));
        // Once removed from the first glaze, the barcode can move.
        assertEquals(List.of(), edited(bea, first, null, List.of(barcode)));
        assertEquals(List.of(barcode), edited(ada, second, List.of(barcode), null));
        assertEquals(second, body(lookup(guest(), barcode).expectStatus().isOk(), GlazeView.class).id());
        // Adding a barcode the glaze already has changes nothing.
        assertEquals(List.of(barcode), edited(ada, second, List.of(barcode), null));
    }

    @Test void concurrentClaimsOfOneBarcodeLeaveItOnOneGlaze() throws Exception {
        RestTestClient ada = user("Ada");
        String barcode = ean13();
        List<UUID> glazes = IntStream.range(0, 4).mapToObj(i -> addGlaze(ada, unique("Race"), "Blue", "B" + i)).toList();
        List<Integer> statuses;
        try (var pool = Executors.newFixedThreadPool(glazes.size())) {
            List<Future<Integer>> claims = glazes.stream().map(glaze -> pool.submit(() ->
                    edit(user("Racer"), glaze, List.of(barcode), null).returnResult(String.class).getStatus().value())).toList();
            statuses = claims.stream().map(claim -> { try { return claim.get(); } catch (Exception e) { throw new RuntimeException(e); } }).toList();
        }
        assertEquals(1, statuses.stream().filter(status -> status == 200).count(), statuses.toString());
        assertTrue(statuses.stream().allMatch(status -> status == 200 || status == 409), statuses.toString());
        assertEquals(1, glazes.stream().filter(glaze -> listed(ada, glaze).contains(barcode)).count());
    }

    @Test void anySignedInUserEditsBarcodesWithinTheRules() {
        RestTestClient ada = user("Ada");
        RestTestClient bea = user("Bea");
        UUID glaze = addGlaze(ada, unique("Edited"), "Blue", "PC-20");
        String pint = ean13(), gallon = ean13();
        assertEquals(List.of(gallon, pint).stream().sorted().toList(), edited(bea, glaze, List.of(pint, gallon), null));
        assertEquals(List.of(gallon), edited(ada, glaze, null, List.of(pint)));
        edit(guest(), glaze, List.of(ean13()), null).expectStatus().isUnauthorized();
        ada.patch().uri("/api/glazes/{id}/barcodes", glaze).header(CSRF_HEADER, "wrong")
                .body(Map.of("add", List.of(ean13()))).exchange().expectStatus().isForbidden();
        assertError(edit(ada, UUID.randomUUID(), List.of(ean13()), null), 404, GlazeService.UNKNOWN_GLAZE);
        assertError(edit(ada, glaze, List.of(ean13(), "123"), null), 400, Barcodes.INVALID);
        assertError(ada.post().uri("/api/glazes").body(Map.of("newManufacturer", Map.of("name", unique("Bad"),
                "shortId", shortId()), "name", "Bad", "code", "1", "barcode", "12345")).exchange(), 400, Barcodes.INVALID);
        assertEquals(10, edited(ada, glaze, IntStream.range(0, 9).mapToObj(i -> ean13()).toList(), null).size());
        assertError(edit(ada, glaze, List.of(ean13()), null), 400, Barcodes.TOO_MANY);
        assertEquals(10, listed(ada, glaze).size());
    }

    @Test void removingAGlazeFreesItsBarcodes() {
        RestTestClient ada = user("Ada");
        String barcode = ean13();
        UUID glaze = addGlazeWithBarcode(ada, barcode);
        RestTestClient admin = siteAdmin();
        admin.delete().uri("/api/glazes/{id}", glaze).exchange().expectStatus().isNoContent();
        assertError(lookup(guest(), barcode), 404, CatalogController.UNKNOWN_BARCODE);
        addGlazeWithBarcode(ada, barcode);
    }
}
