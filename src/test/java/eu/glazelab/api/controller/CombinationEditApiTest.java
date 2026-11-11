package eu.glazelab.api.controller;

import eu.glazelab.api.dto.CombinationDtos.CombinationView;
import eu.glazelab.api.dto.CombinationDtos.StepView;
import eu.glazelab.api.model.StepType;
import eu.glazelab.api.service.CombinationService;
import eu.glazelab.api.storage.ImageStorage;
import eu.glazelab.api.support.ApiTestBase;
import eu.glazelab.api.support.TestImages;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

class CombinationEditApiTest extends ApiTestBase {
    @Autowired ImageStorage storage;
    @Autowired CombinationService service;
    @Autowired TransactionTemplate transactions;

    private static Map<String, Object> glaze(UUID id) { return Map.of("glazeId", id, "coats", 2); }
    private static Map<String, Object> custom(String label) { return Map.of("type", "CUSTOM", "label", label); }
    private static Map<String, Object> input(String title, List<?> steps) {
        return Map.of("title", title, "description", "Updated notes", "tags", List.of("edited"), "steps", steps);
    }
    private static RestTestClient.ResponseSpec edit(RestTestClient user, UUID id, List<?> steps) {
        return user.put().uri("/api/combinations/{id}", id).body(input("Updated", steps)).exchange();
    }
    private static RestTestClient.ResponseSpec remove(RestTestClient user, UUID id) {
        return user.delete().uri("/api/combinations/{id}", id).exchange();
    }
    private static List<Map<String, Object>> inputs(CombinationView view) {
        return view.steps().stream().map(step -> {
            var result = new HashMap<String, Object>();
            result.put("type", step.type().name());
            if (step.type() == StepType.GLAZE) { result.put("glazeId", step.glazeId()); result.put("coats", step.coats()); }
            else result.put("label", step.label());
            if (step.method() != null) result.put("method", step.method());
            if (step.notes() != null) result.put("notes", step.notes());
            return (Map<String, Object>) result;
        }).toList();
    }
    private int count(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }

    @Test void onlyTheAuthorMayEditOrDeleteEvenForSiteAdmins() {
        var author = user("Author"); var other = user("Other"); var admin = user("Admin");
        jdbc.update("update app_user set site_admin = true where id = ?", me(admin).id());
        UUID id = created(author.post().uri("/api/combinations").body(input("Original", List.of(custom("Firing")))).exchange());
        for (var client : List.of(other, admin)) {
            edit(client, id, List.of(custom("Changed"))).expectStatus().isForbidden();
            remove(client, id).expectStatus().isForbidden();
        }
        edit(guest(), id, List.of(custom("Changed"))).expectStatus().isUnauthorized();
        remove(guest(), id).expectStatus().isUnauthorized();
        edit(author, UUID.randomUUID(), List.of(custom("Changed"))).expectStatus().isNotFound();
        remove(author, UUID.randomUUID()).expectStatus().isNotFound();
        edit(author, id, List.of(custom("Changed"))).expectStatus().isNoContent();
        remove(author, id).expectStatus().isNoContent();
    }

    @Test void editsReplaceOrderedGlazeAndCustomStepsAndKeepIdentityAndCreatedAt() {
        var author = user("Author"); UUID g = addGlaze(author, unique("Edit"), "Blue", "PC-20");
        UUID id = addCombination(author, "Original", g);
        var before = combination(author, id).orElseThrow();
        var next = List.of(custom("Bisque"), Map.of("glazeId", g, "coats", 4, "method", "Dip", "notes", "Outside"), custom("Final firing"));
        edit(author, id, next).expectStatus().isNoContent();
        var after = combination(author, id).orElseThrow();
        assertEquals(before.id(), after.id()); assertEquals(before.createdAt(), after.createdAt());
        assertEquals("Updated", after.title()); assertEquals("Updated notes", after.description()); assertEquals(List.of("edited"), after.tags());
        assertEquals(List.of(StepType.CUSTOM, StepType.GLAZE, StepType.CUSTOM), after.steps().stream().map(StepView::type).toList());
        assertEquals(List.of(1, 2, 3), after.steps().stream().map(StepView::position).toList());
        assertEquals(4, after.steps().get(1).coats()); assertEquals("Outside", after.steps().get(1).notes());
        edit(author, id, List.of(custom("Custom only"))).expectStatus().isNoContent();
        assertEquals(1, combination(guest(), id).orElseThrow().steps().size());
    }

    @Test void ownPhotosDoNotLockStructuralEdits() {
        var author = user("Author"); UUID g = addGlaze(author, unique("Own"), "Blue", "PC-20");
        UUID id = addCombination(author, "Original", g);
        created(upload(author, id, Map.of("visibility", "PRIVATE", "kind", "TEST_TILE"), TestImages.png(20, 20)));
        assertFalse(combination(author, id).orElseThrow().stepsLocked());
        edit(author, id, List.of(custom("Changed"))).expectStatus().isNoContent();
    }

    @Test void otherPhotosLockEveryStructuralChangeButNotMetadata() {
        var author = user("Author"); var other = user("Other"); UUID g = addGlaze(author, unique("Lock"), "Blue", "PC-20");
        UUID id = created(author.post().uri("/api/combinations").body(input("Original", List.of(glaze(g), custom("Bisque"), custom("Final")))).exchange());
        var original = combination(author, id).orElseThrow();
        created(upload(other, id, Map.of("visibility", "PUBLIC", "kind", "FINISHED"), TestImages.png(20, 20)));
        var seen = combination(author, id).orElseThrow();
        assertTrue(seen.stepsLocked()); assertEquals(1, seen.images().size());
        assertFalse(combination(other, id).orElseThrow().stepsLocked()); assertFalse(combination(guest(), id).orElseThrow().stepsLocked());
        var unchanged = inputs(original);
        // UI drafts round-trip nullable notes/method as empty strings. Metadata changes must still work.
        unchanged.get(0).put("method", ""); unchanged.forEach(step -> step.put("notes", ""));
        edit(author, id, unchanged).expectStatus().isNoContent();
        var metadata = combination(author, id).orElseThrow();
        assertEquals("Updated notes", metadata.description()); assertEquals(List.of("edited"), metadata.tags());
        assertEquals(original.steps(), metadata.steps());
        var changedGlaze = new HashMap<>(glaze(g)); changedGlaze.put("coats", 3);
        var method = new HashMap<>(glaze(g)); method.put("method", "Dip");
        var notes = new HashMap<>(glaze(g)); notes.put("notes", "New details");
        var customNotes = new HashMap<>(custom("Bisque")); customNotes.put("notes", "New firing");
        List<List<?>> changes = List.of(
                List.of(changedGlaze, custom("Bisque"), custom("Final")),
                List.of(method, custom("Bisque"), custom("Final")),
                List.of(notes, custom("Bisque"), custom("Final")),
                List.of(glaze(g), custom("Renamed"), custom("Final")),
                List.of(glaze(g), customNotes, custom("Final")),
                List.of(glaze(g), custom("Final"), custom("Bisque")),
                List.of(custom("Bisque"), glaze(g), custom("Final")),
                List.of(glaze(g), custom("Bisque")),
                List.of(glaze(g), custom("Bisque"), custom("Final"), custom("Added")),
                List.of(custom("Type changed"), custom("Bisque"), custom("Final")));
        for (var changed : changes) edit(author, id, changed).expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.error").isEqualTo(CombinationService.STEPS_LOCKED);
        remove(author, id).expectStatus().isEqualTo(409);
        assertEquals(original.steps(), combination(author, id).orElseThrow().steps());
        assertEquals(0, count("select count(*) from image_deletion where combination_id = ?", id));
    }

    @Test void anonymizedOtherUploadersStillLockTheCombination() {
        var author = user("Author"); var other = user("Other");
        UUID id = created(author.post().uri("/api/combinations").body(input("Original", List.of(custom("Firing")))).exchange());
        UUID photo = created(upload(other, id, Map.of("visibility", "PUBLIC", "kind", "TEST_TILE"), TestImages.png(20, 20)));
        jdbc.update("update image set uploaded_by = null where id = ?", photo);
        assertTrue(combination(author, id).orElseThrow().stepsLocked());
        edit(author, id, List.of(custom("Changed"))).expectStatus().isEqualTo(409);
        remove(author, id).expectStatus().isEqualTo(409);
    }

    @Test void invalidEditsRollBackMetadataAndSteps() {
        var author = user("Author"); UUID g = addGlaze(author, unique("Invalid edit"), "Blue", "PC-20");
        UUID id = addCombination(author, "Original", g);
        var original = combination(author, id).orElseThrow();
        edit(author, id, List.of(custom(""))).expectStatus().isBadRequest();
        edit(author, id, List.of(Map.of("type", "CUSTOM", "label", "Invalid", "glazeId", g))).expectStatus().isBadRequest();
        edit(author, id, List.of(glaze(UUID.randomUUID()))).expectStatus().isBadRequest();
        edit(author, id, List.of()).expectStatus().isBadRequest();
        var tooMany = new ArrayList<Map<String, Object>>(); for (int i = 0; i < 31; i++) tooMany.add(custom("Step"));
        edit(author, id, tooMany).expectStatus().isBadRequest().expectBody().jsonPath("$.error").isEqualTo("steps: Maximum 30 steps");
        assertEquals(original, combination(author, id).orElseThrow());
    }

    @Test void deletionLogsOwnPhotosRemovesFavoritesAndDeletesObjectsAfterCommit() throws Exception {
        var author = user("Author"); var other = user("Other"); UUID actor = me(author).id();
        UUID id = created(author.post().uri("/api/combinations").body(input("Doomed", List.of(custom("Firing")))).exchange());
        for (String visibility : List.of("PUBLIC", "PRIVATE")) created(upload(author, id, Map.of("visibility", visibility, "kind", "TEST_TILE"), TestImages.png(20, 20)));
        author.put().uri("/api/favorites/combinations/{id}", id).exchange().expectStatus().is2xxSuccessful();
        other.put().uri("/api/favorites/combinations/{id}", id).exchange().expectStatus().is2xxSuccessful();
        var keys = jdbc.queryForList("select storage_key from image where combination_id = ?", String.class, id);
        transactions.executeWithoutResult(status -> {
            service.remove(id, actor);
            for (String key : keys) assertStored(storage, key, true);
        });
        assertTrue(combination(guest(), id).isEmpty());
        assertEquals(0, count("select count(*) from favorite where combination_id = ?", id));
        assertEquals(0, count("select count(*) from combination_step where combination_id = ?", id));
        assertEquals(0, count("select count(*) from combination_tag where combination_id = ?", id));
        assertEquals(2, count("select count(*) from image_deletion where combination_id = ? and title = 'Doomed' and uploader_id = ? and deleted_by = ?", id, actor, actor));
        for (String key : keys) assertStored(storage, key, false);
    }

    @Test void enclosingRollbackRetainsPhotosObjectsAndFavoritesWithoutAuditRows() throws Exception {
        var author = user("Author"); UUID actor = me(author).id();
        UUID id = created(author.post().uri("/api/combinations").body(input("Kept", List.of(custom("Firing")))).exchange());
        created(upload(author, id, Map.of("visibility", "PUBLIC", "kind", "TEST_TILE"), TestImages.png(20, 20)));
        author.put().uri("/api/favorites/combinations/{id}", id).exchange().expectStatus().is2xxSuccessful();
        String key = jdbc.queryForObject("select storage_key from image where combination_id = ?", String.class, id);
        transactions.executeWithoutResult(status -> { service.remove(id, actor); status.setRollbackOnly(); });
        assertTrue(combination(author, id).isPresent()); assertTrue(storage.get(key).length > 0);
        assertEquals(1, count("select count(*) from image where combination_id = ?", id));
        assertEquals(1, count("select count(*) from favorite where combination_id = ?", id));
        assertEquals(0, count("select count(*) from image_deletion where combination_id = ?", id));
    }
}
