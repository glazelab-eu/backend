package eu.glazelab.api.controller;

import eu.glazelab.api.dto.GlazeDtos.GlazeCorrection;
import eu.glazelab.api.dto.GlazeDtos.GlazeEditView;
import eu.glazelab.api.dto.GlazeDtos.GlazeHistoryView;
import eu.glazelab.api.dto.GlazeDtos.GlazeView;
import eu.glazelab.api.model.GlazeEdit.Field;
import eu.glazelab.api.service.GlazeService;
import eu.glazelab.api.support.ApiTestBase;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.test.web.servlet.client.RestTestClient.ResponseSpec;

import static org.junit.jupiter.api.Assertions.*;

/** Its own database with statement statistics, so the listing's statement count can be measured. */
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:glazeedits;DB_CLOSE_DELAY=-1",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.jpa.properties.hibernate.session.events.log=false" })
class GlazeEditApiTest extends ApiTestBase {
    private static ResponseSpec correct(RestTestClient client, UUID glaze, Map<String, ?> body) {
        return client.patch().uri("/api/glazes/{id}", glaze).body(body).exchange();
    }

    private static GlazeView corrected(RestTestClient client, UUID glaze, Map<String, ?> body) {
        return body(correct(client, glaze, body).expectStatus().isOk(), GlazeView.class);
    }

    private static GlazeHistoryView history(RestTestClient client, UUID glaze) {
        return body(client.get().uri("/api/glazes/{id}/history", glaze).exchange().expectStatus().isOk(), GlazeHistoryView.class);
    }

    private static GlazeView listed(RestTestClient client, UUID glaze) {
        return glazes(client).stream().filter(g -> g.id().equals(glaze)).findFirst().orElseThrow();
    }

    private int logRows(UUID glaze) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM glaze_edit WHERE glaze_id = ?", Integer.class, glaze);
    }

    private static Map<String, Object> notes(Object value) {
        var body = new HashMap<String, Object>();
        body.put("notes", value);
        return body;
    }

    @Test void guestsCannotCorrectAndWritesNeedACsrfToken() {
        var ada = user("Ada");
        UUID glaze = addGlaze(ada, unique("Guarded"), "Blue", "PC-20");
        assertError(correct(guest(), glaze, Map.of("name", "Red")), 401, "Please sign in.");
        correct(guestWithoutCsrf(), glaze, Map.of("name", "Red")).expectStatus().isForbidden();
        assertError(correct(ada, UUID.randomUUID(), Map.of("name", "Red")), 404, GlazeService.UNKNOWN_GLAZE);
        assertEquals("Blue", listed(guest(), glaze).name());
        assertEquals(0, logRows(glaze));
    }

    @Test void anySignedInUserCorrectsNameAndNotesTrimmedAndEveryChangeIsLogged() {
        var ada = user("Ada");
        var bob = user("Bob");
        UUID glaze = addGlaze(ada, unique("Wiki"), "Bleu", "PC-20");
        GlazeView view = corrected(bob, glaze, Map.of("name", "  Blue  ", "notes", "  Glossy.  "));
        assertEquals("Blue", view.name());
        assertEquals("Glossy.", view.notes());
        assertEquals("PC-20", view.code());
        assertEquals(me(bob).displayName(), view.lastEditedBy());
        assertNotNull(view.lastEditedAt());
        assertEquals(me(ada).displayName(), view.addedBy());

        GlazeView seen = listed(guest(), glaze);
        assertEquals("Blue", seen.name());
        assertEquals(view.lastEditedAt(), seen.lastEditedAt());
        assertEquals(me(bob).displayName(), seen.lastEditedBy());

        List<GlazeEditView> edits = history(guest(), glaze).edits();
        assertEquals(2, edits.size());
        var byField = edits.stream().collect(java.util.stream.Collectors.toMap(GlazeEditView::field, e -> e));
        assertEquals("Bleu", byField.get(Field.NAME).oldValue());
        assertEquals("Blue", byField.get(Field.NAME).newValue());
        assertNull(byField.get(Field.NOTES).oldValue());
        assertEquals("Glossy.", byField.get(Field.NOTES).newValue());
        assertTrue(edits.stream().allMatch(e -> me(bob).displayName().equals(e.editor())));
    }

    @Test void historyIsPublicNewestFirstAndCapped() {
        var ada = user("Ada");
        UUID glaze = addGlaze(ada, unique("Long"), "Blue", "PC-20");
        assertEquals(new GlazeHistoryView(List.of(), false), history(guest(), glaze));
        corrected(ada, glaze, Map.of("name", "Blue 1"));
        corrected(ada, glaze, Map.of("name", "Blue 2"));
        var edits = history(guest(), glaze).edits();
        assertEquals(List.of("Blue 2", "Blue 1"), edits.stream().map(GlazeEditView::newValue).toList());
        assertEquals(history(guest(), glaze), history(user("Reader"), glaze));

        for (int i = 0; i < CatalogController.HISTORY_LIMIT; i++) {
            if (i == CatalogController.HISTORY_LIMIT - 2) {
                // Exactly the limit: everything is shown, nothing more.
                GlazeHistoryView full = history(guest(), glaze);
                assertEquals(CatalogController.HISTORY_LIMIT, full.edits().size());
                assertFalse(full.more());
            }
            jdbc.update("INSERT INTO glaze_edit (id, glaze_id, editor_id, edited_at, field, old_value, new_value)"
                    + " VALUES (?, ?, NULL, ?, 'NAME', 'a', 'b')",
                    UUID.randomUUID(), glaze, java.time.OffsetDateTime.parse("2020-01-01T00:00:00Z").plusMinutes(i));
        }
        GlazeHistoryView capped = history(guest(), glaze);
        assertEquals(CatalogController.HISTORY_LIMIT, capped.edits().size());
        assertTrue(capped.more());
        assertEquals("Blue 2", capped.edits().getFirst().newValue());
        guest().get().uri("/api/glazes/{id}/history", UUID.randomUUID()).exchange().expectStatus().isNotFound();
    }

    @Test void codeChangesWhileUnusedAreNormalizedAndKeepTheOldCodeInTheLog() {
        var ada = user("Ada");
        UUID glaze = addGlaze(ada, unique("Codes"), "Blue", "PC-20");
        // A combination of custom steps only does not use the glaze.
        created(ada.post().uri("/api/combinations").body(Map.of("title", "Firing",
                "steps", List.of(Map.of("type", "CUSTOM", "label", "Bisque")))).exchange());
        assertEquals("PC-21", corrected(user("Bob"), glaze, Map.of("code", " pc 21 ")).code());
        var edit = history(guest(), glaze).edits().getFirst();
        assertEquals(Field.CODE, edit.field());
        assertEquals("PC-20", edit.oldValue());
        assertEquals("PC-21", edit.newValue());
        assertError(correct(ada, glaze, Map.of("code", "  ")), 400, "code: Use letters and digits for the glaze code, such as PC-20");
        assertError(correct(ada, glaze, Map.of("code", "PC_1")), 400, "code: Use letters and digits for the glaze code, such as PC-20");
    }

    @Test void usedCodesChangeOnlyForSiteAdminsWhileNameAndNotesStayOpen() {
        var ada = user("Ada");
        UUID published = addGlaze(ada, unique("Used"), "Blue", "PC-20");
        addCombination(ada, "Uses it", published);
        assertError(correct(ada, published, Map.of("code", "PC-30")), 403, GlazeService.CODE_LOCKED);
        // A refused code change changes nothing else of the request either.
        assertError(correct(ada, published, Map.of("code", "PC-30", "name", "Changed")), 403, GlazeService.CODE_LOCKED);
        assertEquals("Blue", listed(ada, published).name());
        assertEquals(0, logRows(published));
        // The same code (after normalization) is no change, so it passes with the name.
        assertEquals("Navy", corrected(ada, published, Map.of("code", "pc 20", "name", "Navy")).name());

        // Drafts count too: they may be published later and are invisible to others.
        UUID drafted = addGlaze(ada, unique("Drafted"), "Green", "G-1");
        created(ada.post().uri("/api/combinations").body(Map.of("title", "", "draft", true,
                "steps", List.of(Map.of("glazeId", drafted, "coats", 1)))).exchange());
        assertError(correct(user("Bob"), drafted, Map.of("code", "G-2")), 403, GlazeService.CODE_LOCKED);

        var admin = siteAdmin();
        GlazeView changed = corrected(admin, published, Map.of("code", "PC-30"));
        assertEquals("PC-30", changed.code());
        assertEquals(me(admin).displayName(), changed.lastEditedBy());
        // Steps reference the glaze by ID, so the combination keeps it.
        assertTrue(combinations(ada).stream().anyMatch(c -> c.steps().stream().anyMatch(s -> published.equals(s.glazeId()))));
    }

    @Test void aLegacyCodeStaysAsStoredUntilTheCodeItselfIsCorrected() {
        var ada = user("Ada");
        UUID glaze = addGlaze(ada, unique("Legacy"), "Blue", "PC-20");
        addCombination(ada, "Uses it", glaze);
        // V10 only trimmed and upper-cased codes, so older ones may keep spaces or slashes.
        jdbc.update("UPDATE glaze SET code = 'PC 20' WHERE id = ?", glaze);
        GlazeView view = corrected(user("Bob"), glaze, Map.of("name", "Navy"));
        assertEquals("PC 20", view.code());
        assertEquals("PC 20", jdbc.queryForObject("SELECT code FROM glaze WHERE id = ?", String.class, glaze));
        assertEquals(List.of(Field.NAME), history(guest(), glaze).edits().stream().map(GlazeEditView::field).toList());
        // Sending the normalized form is a real change, so the lock applies and an admin's change is logged.
        assertError(correct(user("Bob"), glaze, Map.of("code", "pc 20")), 403, GlazeService.CODE_LOCKED);
        assertEquals("PC-20", corrected(siteAdmin(), glaze, Map.of("code", "pc 20")).code());
        var edit = history(guest(), glaze).edits().getFirst();
        assertEquals(List.of(Field.CODE, "PC 20", "PC-20"), List.of(edit.field(), edit.oldValue(), edit.newValue()));
    }

    @Test void aCodeTheManufacturerHasIsAConflictAndTheManufacturerNeverChanges() {
        var ada = user("Ada");
        String maker = unique("Maker");
        UUID first = addGlaze(ada, maker, "Blue", "PC-20");
        UUID second = addGlaze(ada, maker, "Red", "PC-21");
        UUID otherMaker = addGlaze(ada, unique("Other"), "Green", "PC-20");
        assertError(correct(ada, second, Map.of("code", "pc-20")), 409, GlazeService.GLAZE_EXISTS);
        assertEquals("PC-21", listed(ada, second).code());
        // The same code at another manufacturer is fine; a manufacturer in the request is ignored.
        var other = listed(ada, otherMaker).manufacturer();
        GlazeView view = corrected(ada, first, Map.of("code", "PC-22", "manufacturerId", other.id(),
                "manufacturer", Map.of("id", other.id(), "name", "X", "shortId", "XX")));
        assertEquals(maker, view.manufacturer().name());
        assertEquals("PC-22", view.code());
    }

    @Test void unchangedValuesWriteNoLogRowAndAbsentNotesStayWhileNullOrBlankClear() {
        var ada = user("Ada");
        UUID glaze = addGlaze(ada, unique("Same"), "Blue", "PC-20");
        corrected(ada, glaze, Map.of("notes", "Satin"));
        assertEquals(1, logRows(glaze));
        GlazeView same = corrected(ada, glaze, Map.of("name", " Blue ", "code", "pc 20", "notes", "Satin "));
        assertEquals("Satin", same.notes());
        corrected(ada, glaze, Map.of());
        assertEquals(1, logRows(glaze));
        assertEquals("Satin", corrected(ada, glaze, Map.of("name", "Navy")).notes());
        assertEquals(2, logRows(glaze));
        assertNull(corrected(ada, glaze, notes(null)).notes());
        assertEquals(3, logRows(glaze));
        assertNull(corrected(ada, glaze, notes("   ")).notes());
        assertEquals(3, logRows(glaze));
        var cleared = history(guest(), glaze).edits().stream().filter(e -> e.field() == Field.NOTES).findFirst().orElseThrow();
        assertEquals("Satin", cleared.oldValue());
        assertNull(cleared.newValue());

        UUID untouched = addGlaze(ada, unique("Untouched"), "Blue", "PC-20");
        GlazeView view = corrected(ada, untouched, Map.of("name", "Blue"));
        assertNull(view.lastEditedAt());
        assertNull(view.lastEditedBy());
    }

    @Test void namesAndNotesAreLimitedLikeOnCreation() {
        var ada = user("Ada");
        UUID glaze = addGlaze(ada, unique("Limits"), "Blue", "PC-20");
        assertError(correct(ada, glaze, Map.of("name", "   ")), 400, "name: " + GlazeCorrection.NAME_LENGTH);
        assertError(correct(ada, glaze, Map.of("name", "n".repeat(121))), 400, "name: " + GlazeCorrection.NAME_LENGTH);
        assertEquals("n".repeat(120), corrected(ada, glaze, Map.of("name", " " + "n".repeat(120) + " ")).name());
        assertError(correct(ada, glaze, Map.of("notes", "n".repeat(1001))), 400, GlazeService.NOTES_TOO_LONG);
        assertEquals(1000, corrected(ada, glaze, Map.of("notes", "n".repeat(1000) + "  ")).notes().length());
    }

    @Test void deletedEditorsLeaveTheirCorrectionsWithoutANameAndRemovalTakesTheLog() {
        var ada = user("Ada");
        var bob = user("Bob");
        UUID glaze = addGlaze(ada, unique("Kept"), "Blue", "PC-20");
        GlazeView view = corrected(bob, glaze, Map.of("name", "Navy"));
        bob.post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange().expectStatus().isNoContent();
        var edit = history(guest(), glaze).edits().getFirst();
        assertNull(edit.editor());
        assertEquals("Navy", edit.newValue());
        GlazeView seen = listed(guest(), glaze);
        assertEquals(view.lastEditedAt(), seen.lastEditedAt());
        assertNull(seen.lastEditedBy());
        assertEquals(1, logRows(glaze));

        siteAdmin().delete().uri("/api/glazes/{id}", glaze).exchange().expectStatus().isNoContent();
        assertEquals(0, logRows(glaze));
    }

    @Test void editorsAppearByDisplayNameNeverByEmailOrId() {
        String email = email("Private");
        var editor = signUp(email);
        UUID editorId = me(editor).id();
        UUID glaze = addGlaze(user("Ada"), unique("Leak"), "Blue", "PC-20");
        corrected(editor, glaze, Map.of("name", "Navy", "notes", "Matte"));
        for (String json : List.of(
                guest().get().uri("/api/glazes/{id}/history", glaze).exchange().expectBody(String.class).returnResult().getResponseBody(),
                guest().get().uri("/api/glazes").exchange().expectBody(String.class).returnResult().getResponseBody(),
                correct(editor, glaze, Map.of("name", "Navy")).expectBody(String.class).returnResult().getResponseBody())) {
            assertFalse(json.contains(email), json);
            assertFalse(json.contains(editorId.toString()), json);
        }
    }

    @Test void theListingNeedsNoQueryPerGlazeOrCorrection() {
        var ada = user("Ada");
        UUID first = addGlaze(ada, unique("Counted"), "Blue", "C-1");
        corrected(ada, first, Map.of("name", "Navy"));
        long before = measured(() -> glazes(guest())).statements();
        for (int i = 0; i < 3; i++) {
            UUID glaze = addGlaze(user("Editor"), unique("Counted"), "Blue", "C-" + (i + 2));
            corrected(user("Editor"), glaze, Map.of("name", "Navy " + i, "notes", "Notes " + i));
        }
        assertEquals(before, measured(() -> glazes(guest())).statements());
    }
}
