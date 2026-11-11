package eu.glazelab.api.controller;

import eu.glazelab.api.service.GlazeService;
import eu.glazelab.api.dto.GlazeDtos.GlazeInUseView;
import eu.glazelab.api.dto.GlazeDtos.GlazeView;
import eu.glazelab.api.dto.GlazeDtos.ManufacturerView;
import eu.glazelab.api.dto.GlazeDtos.UsingCombinationView;
import eu.glazelab.api.storage.ImageStorage;
import eu.glazelab.api.support.ApiTestBase;
import eu.glazelab.api.support.TestImages;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.util.LinkedMultiValueMap;

import static org.junit.jupiter.api.Assertions.*;

class GlazeRemovalApiTest extends ApiTestBase {
    @Autowired ImageStorage storage;

    private static RestTestClient.ResponseSpec remove(RestTestClient client, UUID glaze) {
        return client.delete().uri("/api/glazes/{id}", glaze).exchange();
    }

    private static boolean listed(RestTestClient client, UUID glaze) {
        return glazes(client).stream().anyMatch(g -> g.id().equals(glaze));
    }

    private static ManufacturerView manufacturer(RestTestClient client, UUID glaze) {
        return glazes(client).stream().filter(g -> g.id().equals(glaze)).findFirst().map(GlazeView::manufacturer).orElseThrow();
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    @Test void onlySiteAdminsRemoveGlazesAndTheFlagIsInMe() {
        RestTestClient ada = user("Ada");
        RestTestClient admin = siteAdmin();
        UUID glaze = addGlaze(ada, unique("Guarded"), "Blue", "PC-20");
        assertFalse(me(ada).siteAdmin());
        assertTrue(me(admin).siteAdmin());

        remove(guest(), glaze).expectStatus().isUnauthorized();
        remove(ada, glaze).expectStatus().isForbidden().expectBody().jsonPath("$.error").isEqualTo("Site admin role required");
        // Even the glaze's author is no admin, and unknown IDs answer 403 too, revealing nothing.
        remove(ada, UUID.randomUUID()).expectStatus().isForbidden();
        assertTrue(listed(guest(), glaze));
        remove(admin, UUID.randomUUID()).expectStatus().isNotFound().expectBody().jsonPath("$.error").isEqualTo("Unknown glaze");
    }

    @Test void removalNeedsACsrfToken() {
        String email = email("Admin");
        signUp(email);
        jdbc.update("UPDATE app_user SET site_admin = TRUE WHERE email = ?", email);
        RestTestClient noCsrf = guestWithoutCsrf();
        var form = new LinkedMultiValueMap<String, String>();
        form.add("email", email);
        form.add("password", PASSWORD);
        noCsrf.post().uri("/api/auth/login").header(CSRF_HEADER, csrf(noCsrf))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).exchange().expectStatus().isOk();
        assertTrue(me(noCsrf).siteAdmin());
        UUID glaze = addGlaze(user("Ada"), unique("Forged"), "Blue", "PC-20");

        remove(noCsrf, glaze).expectStatus().isForbidden();
        noCsrf.delete().uri("/api/glazes/{id}", glaze).header(CSRF_HEADER, "wrong").exchange().expectStatus().isForbidden();
        assertTrue(listed(guest(), glaze));
    }

    @Test void aGlazeUsedByCombinationStepsIsKeptWith409NamingThem() {
        RestTestClient ada = user("Ada");
        RestTestClient admin = siteAdmin();
        UUID glaze = addGlaze(ada, unique("Used"), "Blue", "PC-20");
        UUID other = addGlaze(ada, unique("Other"), "Red", "1");
        String first = unique("A layered");
        String second = unique("B layered");
        UUID b = addCombination(ada, second, other, glaze);
        UUID a = addCombination(ada, first, glaze, glaze);
        addCombination(ada, unique("Unrelated"), other);

        GlazeInUseView conflict = body(remove(admin, glaze).expectStatus().isEqualTo(409), GlazeInUseView.class);
        assertEquals(GlazeService.GLAZE_IN_USE, conflict.error());
        // Every combination is visible to everyone today; each one is named once, by title.
        assertEquals(List.of(new UsingCombinationView(a, first), new UsingCombinationView(b, second)), conflict.combinations());
        assertTrue(listed(guest(), glaze));
        assertEquals(2, combination(guest(), a).orElseThrow().steps().size());
    }

    @Test void customStepsDoNotCountAsGlazeUsage() {
        RestTestClient ada = user("Ada");
        UUID glaze = addGlaze(ada, unique("Unused"), "Blue", "PC-20");
        UUID firingOnly = created(ada.post().uri("/api/combinations").body(Map.of("title", unique("Firing only"),
                "steps", List.of(Map.of("type", "CUSTOM", "label", "Bisque firing")))).exchange());
        remove(siteAdmin(), glaze).expectStatus().isNoContent();
        assertFalse(listed(guest(), glaze));
        assertEquals(1, combination(guest(), firingOnly).orElseThrow().steps().size());
    }

    @Test void removalDeletesStockFavoritesTagsAndPicturesWithTheirStoredObjects() throws Exception {
        RestTestClient ada = user("Ada");
        RestTestClient admin = siteAdmin();
        String manufacturerName = unique("Kept");
        UUID glaze = addGlaze(ada, manufacturerName, "Blue", "PC-20");
        UUID sibling = addGlaze(ada, manufacturerName, "Green", "PC-21");
        UUID club = createClub(ada, unique("Kiln"));
        ada.patch().uri("/api/glazes/{id}/tags", glaze).body(Map.of("add", List.of("doomed"))).exchange().expectStatus().isOk();
        ada.post().uri("/api/stock").body(Map.of("glazeId", glaze, "quantityG", 500, "ownerType", "personal")).exchange().expectStatus().isOk();
        ada.post().uri("/api/stock").body(Map.of("glazeId", glaze, "quantityG", 200, "ownerType", "club", "clubId", club)).exchange().expectStatus().isOk();
        ada.post().uri("/api/stock").body(Map.of("glazeId", sibling, "quantityG", 100, "ownerType", "personal")).exchange().expectStatus().isOk();
        ada.put().uri("/api/favorites/glazes/{id}", glaze).exchange().expectStatus().is2xxSuccessful();
        admin.put().uri("/api/favorites/glazes/{id}", glaze).exchange().expectStatus().is2xxSuccessful();
        byte[] png = TestImages.png(30, 20);
        UUID publicImage = created(uploadGlazeImage(ada, glaze, Map.of("visibility", "PUBLIC", "kind", "TEST_TILE"), png));
        created(uploadGlazeImage(ada, glaze, Map.of("visibility", "PRIVATE", "kind", "FINISHED"), png));
        created(uploadGlazeImage(ada, glaze, Map.of("visibility", "CLUB", "kind", "TEST_TILE", "clubId", club.toString()), png));
        List<String> keys = jdbc.queryForList("SELECT storage_key FROM image WHERE glaze_id = ?", String.class, glaze);
        assertEquals(3, keys.size());
        for (String key : keys) assertStored(storage, key, true);

        remove(admin, glaze).expectStatus().isNoContent();

        assertFalse(listed(guest(), glaze));
        assertEquals(0, count("SELECT COUNT(*) FROM glaze WHERE id = ?", glaze));
        assertEquals(0, count("SELECT COUNT(*) FROM glaze_tag WHERE glaze_id = ?", glaze));
        assertEquals(0, count("SELECT COUNT(*) FROM stock WHERE glaze_id = ?", glaze));
        assertEquals(0, count("SELECT COUNT(*) FROM favorite WHERE glaze_id = ?", glaze));
        assertEquals(0, count("SELECT COUNT(*) FROM image WHERE glaze_id = ?", glaze));
        for (String key : keys) assertStored(storage, key, false);
        guest().get().uri("/api/images/{id}", publicImage).exchange().expectStatus().isNotFound();
        // Other glazes, their stock and the manufacturer that still has a glaze stay.
        assertEquals(manufacturerName, manufacturer(guest(), sibling).name());
        assertEquals(1, count("SELECT COUNT(*) FROM stock WHERE glaze_id = ?", sibling));
        assertEquals(List.of(), body(ada.get().uri("/api/favorites").exchange().expectStatus().isOk(),
                eu.glazelab.api.controller.FavoriteController.FavoritesView.class).glazeIds());
        remove(admin, glaze).expectStatus().isNotFound();
    }

    @Test void removingAManufacturersLastGlazeFreesItsNameAndShortId() {
        RestTestClient ada = user("Ada");
        RestTestClient admin = siteAdmin();
        String name = unique("Lonely");
        String shortId = shortId();
        UUID glaze = created(ada.post().uri("/api/glazes").body(Map.of("newManufacturer",
                Map.of("name", name, "shortId", shortId), "name", "Only", "code", "1")).exchange());
        UUID manufacturer = manufacturer(guest(), glaze).id();

        remove(admin, glaze).expectStatus().isNoContent();

        assertEquals(0, count("SELECT COUNT(*) FROM manufacturer WHERE id = ?", manufacturer));
        // The picker would no longer list it, so its name and short ID are free again, in any letter case.
        UUID again = created(ada.post().uri("/api/glazes").body(Map.of("newManufacturer",
                Map.of("name", name.toUpperCase(), "shortId", shortId.toLowerCase()), "name", "Only", "code", "1")).exchange());
        assertEquals(shortId, manufacturer(guest(), again).shortId());
    }
}
