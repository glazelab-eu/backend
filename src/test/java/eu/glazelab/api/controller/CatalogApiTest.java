package eu.glazelab.api.controller;

import eu.glazelab.api.service.GlazeService;
import eu.glazelab.api.dto.GlazeDtos.GlazeCreatedView;
import eu.glazelab.api.dto.GlazeDtos.GlazeView;
import eu.glazelab.api.dto.GlazeDtos.ManufacturerView;
import eu.glazelab.api.dto.StockDtos.StockView;
import eu.glazelab.api.service.StockService;
import eu.glazelab.api.support.ApiTestBase;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.junit.jupiter.api.Assertions.*;

class CatalogApiTest extends ApiTestBase {
    @Test void statementCountsRefuseToMeasureWithoutStatistics() {
        // This context leaves hibernate.generate_statistics off: a count of 0 would make query tests pass vacuously.
        assertThrows(IllegalStateException.class, () -> measured(() -> stock(user("Ada"))));
    }

    private static List<StockView> stock(RestTestClient client) {
        return body(client.get().uri("/api/stock").exchange().expectStatus().isOk(),
                new ParameterizedTypeReference<List<StockView>>() {});
    }

    private static RestTestClient.ResponseSpec saveStock(RestTestClient client, Map<String, Object> body) {
        return client.post().uri("/api/stock").body(body).exchange();
    }

    private static RestTestClient.ResponseSpec postGlaze(RestTestClient client, Map<String, Object> body) {
        return client.post().uri("/api/glazes").body(body).exchange();
    }

    private static GlazeView glaze(RestTestClient client, UUID id) {
        return glazes(client).stream().filter(g -> g.id().equals(id)).findFirst().orElseThrow();
    }

    @Test void aGlazeCreatesANewManufacturerOrUsesAnExistingOne() {
        Instant before = Instant.now();
        RestTestClient ada = user("Ada");
        RestTestClient bea = user("Bea");
        String name = unique("Botz");
        String shortId = shortId();
        UUID first = created(postGlaze(ada, Map.of("newManufacturer", Map.of("name", "  " + name + " ",
                "shortId", " " + shortId.toLowerCase() + " "), "name", " Seladon ", "code", " 9353 ")));
        ManufacturerView manufacturer = glaze(ada, first).manufacturer();
        assertEquals(new ManufacturerView(manufacturer.id(), shortId, name), manufacturer);
        GlazeView added = glaze(guest(), first);
        // Everyone sees when a glaze was added and by whom.
        assertEquals(new GlazeView(first, manufacturer, "Seladon", "9353", null, List.of(), List.of(), List.of(),
                added.createdAt(), me(ada).displayName(), null, null), added);
        assertFalse(added.createdAt().isBefore(before), "createdAt is set when the glaze is added");
        assertFalse(added.createdAt().isAfter(Instant.now()));

        // Any signed-in user may add glazes to an existing manufacturer; codes are stored upper case.
        UUID second = created(postGlaze(bea, Map.of("manufacturerId", manufacturer.id(), "name", "Tenmoku",
                "code", "bo-9.1", "notes", "Cone 6")));
        GlazeView tenmoku = glaze(bea, second);
        assertEquals(new GlazeView(second, manufacturer, "Tenmoku", "BO-9.1", "Cone 6", List.of(), List.of(), List.of(),
                tenmoku.createdAt(), me(bea).displayName(), null, null), tenmoku);
        assertFalse(tenmoku.createdAt().isBefore(added.createdAt()));
        assertEquals(1, glazes(ada).stream().map(GlazeView::manufacturer).filter(m -> m.name().equals(name)).distinct().count());
    }

    /** The add-glaze dialog selects the answer's ID in the picker right after reloading the listing. */
    @Test void theAnswerNamesTheNewGlazeSoAPickerCanSelectItAtOnce() {
        RestTestClient ada = user("Ada");
        String barcode = BarcodeApiTest.ean13();
        Map<String, Object> body = Map.of("newManufacturer", Map.of("name", unique("Picker"), "shortId", shortId()),
                "name", "Celadon", "code", "pk-1", "barcode", barcode);
        UUID id = created(postGlaze(ada, body));

        // The listing every client reloads holds exactly that glaze, and its barcode finds the same one.
        List<GlazeView> listed = glazes(ada).stream().filter(g -> g.id().equals(id)).toList();
        assertEquals(1, listed.size());
        assertEquals("PK-1", listed.get(0).code());
        assertEquals(id, body(ada.get().uri("/api/barcodes/{barcode}", barcode).exchange().expectStatus().isOk(),
                GlazeView.class).id());

        // A repeat names no ID: the dialog keeps the draft and shows the conflict instead of selecting anything.
        Map<String, Object> repeat = Map.of("manufacturerId", listed.get(0).manufacturer().id(),
                "name", "Another", "code", "PK-1");
        assertError(postGlaze(ada, repeat), 409, GlazeService.GLAZE_EXISTS);
        assertEquals(1, glazes(ada).stream().filter(g -> g.manufacturer().id().equals(listed.get(0).manufacturer().id())).count());
    }

    @Test void codesAreRequiredValidAndUniquePerManufacturer() {
        RestTestClient ada = user("Ada");
        String name = unique("Catalog");
        UUID blue = addGlaze(ada, name, "Blue", "PC-20");
        UUID manufacturer = glaze(ada, blue).manufacturer().id();
        assertError(postGlaze(ada, Map.of("manufacturerId", manufacturer, "name", "Dup", "code", " pc-20")), 409,
                GlazeService.GLAZE_EXISTS);
        assertError(postGlaze(ada, Map.of("manufacturerId", manufacturer, "name", "Missing code")), 400,
                "code: must not be blank");
        for (String code : new String[] {"???", "PC//", "-20", "PC-", "X".repeat(41)})
            postGlaze(ada, Map.of("manufacturerId", manufacturer, "name", "Bad", "code", code)).expectStatus().isBadRequest();
        assertError(postGlaze(ada, Map.of("manufacturerId", manufacturer, "name", "Bad", "code", "PC_20")), 400,
                "code: Use letters and digits for the glaze code, such as PC-20");
        // The same code belongs to a different glaze at another manufacturer.
        UUID other = addGlaze(ada, unique("Other"), "Blue", "PC-20");
        assertNotEquals(manufacturer, glaze(ada, other).manufacturer().id());
    }

    @Test void printedCodesNormalizeBeforeValidationAndDuplicateChecks() {
        RestTestClient ada = user("Ada");
        UUID first = created(postGlaze(ada, Map.of("newManufacturer", Map.of("name", unique("Duncan"),
                "shortId", shortId()), "name", "Blue", "code", " in 1036 ")));
        GlazeView added = glaze(ada, first);
        assertEquals("IN-1036", added.code());
        for (String code : new String[] {"in/1036", "IN -- 1036", " in /- 1036 "})
            assertError(postGlaze(ada, Map.of("manufacturerId", added.manufacturer().id(),
                    "name", "Duplicate", "code", code)), 409, GlazeService.GLAZE_EXISTS);
        UUID second = created(postGlaze(ada, Map.of("manufacturerId", added.manufacturer().id(),
                "name", "Green", "code", " pc // 20 ")));
        assertEquals("PC-20", glaze(ada, second).code());
        assertError(postGlaze(ada, Map.of("manufacturerId", added.manufacturer().id(),
                "name", "Duplicate", "code", "PC--20")), 409, GlazeService.GLAZE_EXISTS);
    }

    @Test void manufacturerShortIdsAndNamesAreValidatedAndUniqueInAnyLetterCase() {
        RestTestClient ada = user("Ada");
        String name = unique("Mayco");
        String shortId = shortId();
        UUID existing = addGlaze(ada, name, "Blue", "SW-101");
        String taken = glaze(ada, existing).manufacturer().shortId();
        int before = glazes(ada).size();

        assertError(postGlaze(ada, Map.of("newManufacturer", Map.of("name", unique("Other"), "shortId", taken.toLowerCase()),
                "name", "Blue", "code", "SW-1")), 409, GlazeService.SHORT_ID_TAKEN);
        assertError(postGlaze(ada, Map.of("newManufacturer", Map.of("name", " " + name.toUpperCase() + " ", "shortId", shortId),
                "name", "Blue", "code", "SW-1")), 409, GlazeService.NAME_TAKEN);
        for (String invalid : new String[] {"A", "1AB", "AB-C", "ÄBC", "ABCDEFGHIJK"})
            assertError(postGlaze(ada, Map.of("newManufacturer", Map.of("name", unique("Bad"), "shortId", invalid),
                    "name", "Blue", "code", "SW-1")), 400, "newManufacturer.shortId: Use a manufacturer ID of 2 to 10"
                    + " letters or digits, starting with a letter, such as AMACO");
        assertError(postGlaze(ada, Map.of("newManufacturer", Map.of("name", " ", "shortId", shortId),
                "name", "Blue", "code", "SW-1")), 400, "newManufacturer.name: must not be blank");
        assertError(postGlaze(ada, Map.of("name", "Blue", "code", "SW-1")), 400, GlazeService.MANUFACTURER_REQUIRED);
        assertError(postGlaze(ada, Map.of("manufacturerId", glaze(ada, existing).manufacturer().id(),
                "newManufacturer", Map.of("name", unique("Both"), "shortId", shortId), "name", "Blue", "code", "SW-1")),
                400, GlazeService.MANUFACTURER_REQUIRED);
        assertError(postGlaze(ada, Map.of("manufacturerId", UUID.randomUUID(), "name", "Blue", "code", "SW-1")), 400,
                GlazeService.UNKNOWN_MANUFACTURER);
        assertEquals(before, glazes(ada).size());
        // A rejected request reserved nothing: the unused short ID is still free.
        UUID created = created(postGlaze(ada, Map.of("newManufacturer", Map.of("name", unique("Fresh"), "shortId", shortId),
                "name", "Blue", "code", "SW-1")));
        assertEquals(shortId, glaze(ada, created).manufacturer().shortId());
    }

    @Test void glazesAreNormalizedAndSortedByManufacturerThenName() {
        RestTestClient ada = user("Ada");
        String brand = unique("Sorted");
        UUID white = addGlaze(ada, "  " + brand + "  ", "  White ", "pc-2");
        UUID blue = addGlaze(ada, brand, "Blue", "PC-1");
        List<GlazeView> mine = glazes(guest()).stream().filter(g -> g.id().equals(white) || g.id().equals(blue)).toList();
        ManufacturerView manufacturer = mine.getFirst().manufacturer();
        assertEquals(brand, manufacturer.name());
        String adder = me(ada).displayName();
        assertEquals(List.of(new GlazeView(blue, manufacturer, "Blue", "PC-1", null, List.of(), List.of(), List.of(),
                        mine.get(0).createdAt(), adder, null, null),
                new GlazeView(white, manufacturer, "White", "PC-2", null, List.of(), List.of(), List.of(),
                        mine.get(1).createdAt(), adder, null, null)), mine);
    }

    @Test void stockBelongsToItsOwnerOrClubMembers() {
        RestTestClient ada = user("Ada");
        RestTestClient bea = user("Bea");
        String brand = unique("Stock");
        UUID blue = addGlaze(ada, brand, "Blue", "PC-20");
        UUID white = addGlaze(ada, brand, "White", "PC-21");
        UUID club = createClub(ada, unique("Kiln"));

        saveStock(ada, Map.of("glazeId", blue, "quantityG", 450, "ownerType", "personal")).expectStatus().isOk();
        saveStock(ada, Map.of("glazeId", white, "quantityG", 200, "ownerType", "club", "clubId", club)).expectStatus().isOk();
        saveStock(bea, Map.of("glazeId", white, "quantityG", 10, "ownerType", "club", "clubId", club)).expectStatus().isForbidden();
        saveStock(ada, Map.of("glazeId", blue, "quantityG", 1, "ownerType", "club")).expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("Choose personal or club stock");
        saveStock(ada, Map.of("glazeId", blue, "quantityG", 1, "ownerType", "shelf", "clubId", club)).expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("Choose personal or club stock");
        // A club ID sent with personal stock is ignored.
        saveStock(ada, Map.of("glazeId", blue, "quantityG", 450, "ownerType", "personal", "clubId", club)).expectStatus().isOk();

        assertEquals(2, stock(ada).size());
        assertEquals(List.of(), stock(bea));
        joinClub(ada, club, bea);
        List<StockView> shared = stock(bea);
        assertEquals(1, shared.size());
        assertEquals(white, shared.getFirst().glazeId());
        assertEquals(200, shared.getFirst().quantityG());
        assertNull(shared.getFirst().userId());
    }

    @Test void savingStockUpdatesTheExistingRow() {
        RestTestClient ada = user("Ada");
        UUID glaze = addGlaze(ada, unique("Upsert"), "Blue", "PC-20");
        UUID first = created(saveStock(ada, Map.of("glazeId", glaze, "quantityG", 450, "ownerType", "personal")));
        UUID second = created(saveStock(ada, Map.of("glazeId", glaze, "quantityG", 0, "ownerType", "personal")));
        assertEquals(first, second);
        List<StockView> rows = stock(ada);
        assertEquals(1, rows.size());
        assertEquals(0, rows.getFirst().quantityG());
        assertNull(rows.getFirst().clubId());
    }

    @Test void invalidStockRequestsAreRejected() {
        RestTestClient ada = user("Ada");
        RestTestClient bea = user("Bea");
        UUID glaze = addGlaze(ada, unique("Invalid stock"), "Blue", "PC-20");
        UUID beasClub = createClub(bea, unique("Elsewhere"));
        saveStock(ada, Map.of("glazeId", "not-a-uuid", "quantityG", 1, "ownerType", "personal")).expectStatus().isBadRequest();
        saveStock(ada, Map.of("glazeId", glaze, "quantityG", -1, "ownerType", "personal")).expectStatus().isBadRequest();
        saveStock(ada, Map.of("glazeId", glaze, "quantityG", 1, "ownerType", "club")).expectStatus().isBadRequest();
        saveStock(ada, Map.of("glazeId", glaze, "quantityG", 1, "ownerType", "shelf")).expectStatus().isBadRequest();
        saveStock(ada, Map.of("glazeId", UUID.randomUUID(), "quantityG", 1, "ownerType", "personal")).expectStatus().isBadRequest();
        saveStock(ada, Map.of("glazeId", glaze, "quantityG", 1, "ownerType", "club", "clubId", beasClub)).expectStatus().isForbidden();
        saveStock(ada, Map.of("glazeId", glaze, "quantityG", 1, "ownerType", "club", "clubId", UUID.randomUUID()))
                .expectStatus().isForbidden();
        saveStock(guest(), Map.of("glazeId", glaze, "quantityG", 1, "ownerType", "personal")).expectStatus().isUnauthorized();
        guest().get().uri("/api/stock").exchange().expectStatus().isUnauthorized();
        assertEquals(List.of(), stock(ada));
    }

    @Test void jarSizeIsStoredKeptAndValidated() {
        RestTestClient ada = user("Ada");
        UUID glaze = addGlaze(ada, unique("Jar"), "Blue", "PC-20");
        assertNull(stockOf(ada, saveStock(ada, Map.of("glazeId", glaze, "quantityG", 100, "ownerType", "personal"))).jarSizeG());
        saveStock(ada, Map.of("glazeId", glaze, "quantityG", 100, "jarSizeG", 500, "ownerType", "personal")).expectStatus().isOk();
        assertEquals(500, stock(ada).getFirst().jarSizeG());
        // "Used up" and later amounts without a jar size keep the one on record.
        saveStock(ada, Map.of("glazeId", glaze, "quantityG", 0, "ownerType", "personal")).expectStatus().isOk();
        assertEquals(0, stock(ada).getFirst().quantityG());
        assertEquals(500, stock(ada).getFirst().jarSizeG());
        // "+1 jar": the client adds the jar size to the current amount.
        saveStock(ada, Map.of("glazeId", glaze, "quantityG", 500, "jarSizeG", 250, "ownerType", "personal")).expectStatus().isOk();
        assertEquals(250, stock(ada).getFirst().jarSizeG());
        for (int invalid : new int[] {0, -1, 100_001})
            saveStock(ada, Map.of("glazeId", glaze, "quantityG", 1, "jarSizeG", invalid, "ownerType", "personal"))
                    .expectStatus().isBadRequest();
        assertEquals(250, stock(ada).getFirst().jarSizeG());
    }

    private static StockView stockOf(RestTestClient client, RestTestClient.ResponseSpec saved) {
        UUID id = created(saved);
        return stock(client).stream().filter(row -> row.id().equals(id)).findFirst().orElseThrow();
    }

    @Test void ownersRemoveTheirPersonalStock() {
        RestTestClient ada = user("Ada");
        RestTestClient bea = user("Bea");
        UUID glaze = addGlaze(ada, unique("Remove"), "Blue", "PC-20");
        UUID mine = created(saveStock(ada, Map.of("glazeId", glaze, "quantityG", 100, "jarSizeG", 500, "ownerType", "personal")));
        UUID beas = created(saveStock(bea, Map.of("glazeId", glaze, "quantityG", 50, "ownerType", "personal")));
        guest().delete().uri("/api/stock/{id}", mine).exchange().expectStatus().isUnauthorized();
        // Someone else's entry looks exactly like one that does not exist.
        bea.delete().uri("/api/stock/{id}", mine).exchange().expectStatus().isNotFound();
        ada.delete().uri("/api/stock/{id}", UUID.randomUUID()).exchange().expectStatus().isNotFound();
        ada.delete().uri("/api/stock/{id}", "not-a-uuid").exchange().expectStatus().isBadRequest();
        assertEquals(1, stock(ada).size());
        ada.delete().uri("/api/stock/{id}", mine).exchange().expectStatus().isNoContent();
        assertEquals(List.of(), stock(ada));
        ada.delete().uri("/api/stock/{id}", mine).exchange().expectStatus().isNotFound();
        assertEquals(beas, stock(bea).getFirst().id());
        // A fresh save after removal starts without the old jar size.
        saveStock(ada, Map.of("glazeId", glaze, "quantityG", 10, "ownerType", "personal")).expectStatus().isOk();
        assertNull(stock(ada).getFirst().jarSizeG());
    }

    @Test void clubStockIsRemovedByItsAdderOrAnAdminAndOtherMembersOnlyChangeIt() {
        RestTestClient ada = user("Ada");
        RestTestClient bea = user("Bea");
        RestTestClient cy = user("Cy");
        UUID glaze = addGlaze(ada, unique("Club remove"), "Blue", "PC-20");
        UUID other = addGlaze(ada, unique("Club remove"), "White", "PC-21");
        UUID club = createClub(ada, unique("Kiln"));
        joinClub(ada, club, bea);
        UUID adaId = me(ada).id(), beaId = me(bea).id();
        UUID shelf = created(saveStock(ada, Map.of("glazeId", glaze, "quantityG", 900, "jarSizeG", 1000,
                "ownerType", "club", "clubId", club)));
        cy.delete().uri("/api/stock/{id}", shelf).exchange().expectStatus().isNotFound();
        guest().delete().uri("/api/stock/{id}", shelf).exchange().expectStatus().isUnauthorized();
        StockView seen = stock(bea).getFirst();
        assertEquals(1000, seen.jarSizeG());
        assertEquals(adaId, seen.addedById());
        assertNull(seen.userId());
        assertFalse(seen.canRemove());
        assertTrue(stock(ada).getFirst().canRemove());
        // Any member changes the club's amount (and the adder stays); removing it is the adder's or an admin's.
        saveStock(bea, Map.of("glazeId", glaze, "quantityG", 0, "ownerType", "club", "clubId", club)).expectStatus().isOk();
        assertEquals(adaId, stock(ada).getFirst().addedById());
        assertError(bea.delete().uri("/api/stock/{id}", shelf).exchange(), 403, StockService.REMOVE_RIGHTS_REQUIRED);
        UUID beas = created(saveStock(bea, Map.of("glazeId", other, "quantityG", 5, "ownerType", "club", "clubId", club)));
        assertEquals(beaId, stock(ada).stream().filter(row -> row.id().equals(beas)).findFirst().orElseThrow().addedById());
        assertTrue(stock(ada).stream().allMatch(StockView::canRemove));
        bea.delete().uri("/api/stock/{id}", beas).exchange().expectStatus().isNoContent();
        ada.delete().uri("/api/stock/{id}", shelf).exchange().expectStatus().isNoContent();
        assertEquals(List.of(), stock(ada));
        ada.delete().uri("/api/stock/{id}", shelf).exchange().expectStatus().isNotFound();
        bea.delete().uri("/api/stock/{id}", shelf).exchange().expectStatus().isNotFound();
    }

    private static GlazeCreatedView createdGlaze(RestTestClient.ResponseSpec response) {
        return body(response.expectStatus().isOk(), GlazeCreatedView.class);
    }

    @Test void aNewGlazeAnswersWithItselfAndOptionallyGoesOnTheShelfAtOnce() {
        RestTestClient ada = user("Ada");
        String brand = unique("Shelved");
        GlazeCreatedView plain = createdGlaze(postGlaze(ada, Map.of("newManufacturer",
                Map.of("name", brand, "shortId", shortId()), "name", "Plain", "code", "P1")));
        assertEquals(List.of(), plain.stock());
        assertEquals(plain.id(), plain.glaze().id());
        assertEquals(glaze(ada, plain.id()), plain.glaze());
        assertEquals(List.of(), stock(ada));

        UUID maker = plain.glaze().manufacturer().id();
        GlazeCreatedView shelved = createdGlaze(postGlaze(ada, Map.of("manufacturerId", maker, "name", "Shelved",
                "code", "S1", "stock", Map.of("quantityG", 450, "jarSizeG", 500))));
        StockView entry = shelved.stock().getFirst();
        assertEquals(List.of(entry), stock(ada));
        assertEquals(new StockView(entry.id(), shelved.id(), me(ada).id(), null, 450, 500, brand, "Shelved", null, null, true, false), entry);
        assertEquals(glaze(ada, shelved.id()), shelved.glaze());

        // The jar size is optional, 0 g is a valid amount (as for POST /api/stock).
        StockView bare = createdGlaze(postGlaze(ada, Map.of("manufacturerId", maker, "name", "Bare", "code", "B1",
                "stock", Map.of("quantityG", 0)))).stock().getFirst();
        assertNull(bare.jarSizeG());
        assertEquals(0, bare.quantityG());
        assertEquals(2, stock(ada).size());
    }

    @Test void aNewGlazeCanGoOnAClubShelfOnlyForMembers() {
        RestTestClient ada = user("Ada");
        RestTestClient bea = user("Bea");
        UUID club = createClub(ada, unique("Kiln"));
        joinClub(ada, club, bea);
        String brand = unique("Club shelf");
        StockView shared = createdGlaze(postGlaze(ada, Map.of("newManufacturer", Map.of("name", brand, "shortId", shortId()),
                "name", "Shared", "code", "C1", "stock", Map.of("quantityG", 900, "clubId", club)))).stock().getFirst();
        assertEquals(club, shared.clubId());
        assertNull(shared.userId());
        assertEquals(me(ada).id(), shared.addedById());
        assertEquals(List.of(shared), stock(ada));
        // Every member sees it; only its adder (and admins) may remove it.
        assertEquals(List.of(shared.id()), stock(bea).stream().map(StockView::id).toList());
        assertFalse(stock(bea).getFirst().canRemove());

        // Not a member: refused before anything is created, including a new manufacturer.
        RestTestClient cy = user("Cy");
        String other = unique("Refused");
        assertError(postGlaze(cy, Map.of("newManufacturer", Map.of("name", other, "shortId", shortId()), "name", "No",
                "code", "N1", "stock", Map.of("quantityG", 5, "clubId", club))), 403, "Club membership required");
        assertError(postGlaze(cy, Map.of("newManufacturer", Map.of("name", other, "shortId", shortId()), "name", "No",
                "code", "N1", "stock", Map.of("quantityG", 5, "clubId", UUID.randomUUID()))), 403, "Club membership required");
        assertTrue(glazes(guest()).stream().noneMatch(g -> g.manufacturer().name().equals(other)));
        assertEquals(List.of(), stock(cy));
        // The same rules as POST /api/stock for the amounts, and guests cannot add anything.
        for (Map<String, Object> bad : List.of(Map.<String, Object>of("quantityG", -1), Map.<String, Object>of("jarSizeG", 5),
                Map.<String, Object>of("quantityG", 1, "jarSizeG", 0), Map.<String, Object>of("quantityG", 1, "jarSizeG", 100_001)))
            postGlaze(cy, Map.of("newManufacturer", Map.of("name", other, "shortId", shortId()), "name", "No", "code", "N1",
                    "stock", bad)).expectStatus().isBadRequest();
        postGlaze(guest(), Map.of("newManufacturer", Map.of("name", other, "shortId", shortId()), "name", "No", "code", "N1",
                "stock", Map.of("quantityG", 5))).expectStatus().isUnauthorized();
        assertTrue(glazes(guest()).stream().noneMatch(g -> g.manufacturer().name().equals(other)));
    }

    @Test void aConflictingNewGlazeLeavesNoStockBehind() {
        RestTestClient ada = user("Ada");
        UUID existing = addGlaze(ada, unique("Twice"), "Blue", "PC-20");
        UUID maker = glaze(ada, existing).manufacturer().id();
        assertError(postGlaze(ada, Map.of("manufacturerId", maker, "name", "Blue again", "code", "pc 20",
                "stock", Map.of("quantityG", 100))), 409, GlazeService.GLAZE_EXISTS);
        assertEquals(List.of(), stock(ada));
    }

    @Test void concurrentFirstSavesCreateOneStockRowPerOwner() throws Exception {
        RestTestClient ada = user("Ada");
        UUID glaze = addGlaze(ada, unique("Race"), "Blue", "PC-20");
        UUID club = createClub(ada, unique("Race club"));
        csrf(ada);
        List<HttpStatusCode> statuses = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Future<HttpStatusCode>> results = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                Map<String, Object> body = i % 2 == 0
                        ? Map.of("glazeId", glaze, "quantityG", i, "ownerType", "personal")
                        : Map.of("glazeId", glaze, "quantityG", i, "ownerType", "club", "clubId", club);
                results.add(executor.submit(() -> saveStock(ada, body).returnResult().getStatus()));
            }
            for (var result : results) statuses.add(result.get());
        }
        assertEquals(List.of(HttpStatusCode.valueOf(200)), statuses.stream().distinct().toList());
        assertEquals(2, stock(ada).size());
    }
}
