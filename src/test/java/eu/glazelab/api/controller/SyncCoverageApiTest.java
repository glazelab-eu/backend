package eu.glazelab.api.controller;

import eu.glazelab.api.dto.ImageDtos.ImageView;
import eu.glazelab.api.model.Manufacturer;
import eu.glazelab.api.service.ChangeRecorder;
import eu.glazelab.api.service.SyncService;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import eu.glazelab.api.support.ApiTestBase;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every write operation of the OpenAPI contract either writes the sync change log (shown by running it) or is
 * exempt with a reason. A new write endpoint fails here until it calls {@code ChangeRecorder} (with a scenario
 * below) or is added to {@link #EXEMPT}.
 */
class SyncCoverageApiTest extends ApiTestBase {
    @Autowired TransactionTemplate transactions;
    @Autowired EntityManager entities;
    @Autowired ChangeRecorder changes;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlers;

    /** Writes that change no glaze or combination view, with the reason. */
    static final Map<String, String> EXEMPT = Map.ofEntries(
            Map.entry("POST /api/admin/image-reports/{id}/dismiss", "reports appear in no synced view"),
            Map.entry("DELETE /api/admin/bans/{id}", "the blocklist appears in no synced view"),
            Map.entry("POST /api/admin/avatar-reports/{id}/dismiss", "reports appear in no synced view"),
            Map.entry("POST /api/admin/avatar-reports/{id}/remove", "profile pictures and reports appear in no synced view"),
            Map.entry("POST /api/avatars", "profile pictures appear in no synced view (authors: GET /api/combinations/author-avatars)"),
            Map.entry("DELETE /api/avatars/{id}", "profile pictures appear in no synced view (authors: GET /api/combinations/author-avatars)"),
            Map.entry("POST /api/avatars/{id}/reports", "reports appear in no synced view"),
            Map.entry("POST /api/images/{id}/reports", "reports appear in no synced view"),
            Map.entry("POST /api/reports", "error reports appear in no synced view"),
            Map.entry("POST /api/feedback", "feedback appears in no synced view"),
            Map.entry("PUT /api/admin/feedback/{id}/status", "feedback appears in no synced view"),
            Map.entry("DELETE /api/admin/feedback/{id}", "feedback appears in no synced view"),
            Map.entry("DELETE /api/admin/error-reports/{fingerprint}", "error reports appear in no synced view"),
            Map.entry("DELETE /api/admin/error-reports/occurrences/{id}", "error reports appear in no synced view"),
            Map.entry("PUT /api/account/diagnostics", "the account's own choice (UserView), in no synced view"),
            Map.entry("PUT /api/account/feed", "the account's activity-feed sources (UserView), in no synced view"),
            Map.entry("POST /api/feed/seen", "the account's activity-feed seen position (UserView.feedUnread), in no synced view"),
            Map.entry("POST /api/auth/login", "session only; the client handles an account change"),
            Map.entry("POST /api/auth/logout", "session only; the client handles an account change"),
            Map.entry("POST /api/auth/magic/request", "mail only"),
            Map.entry("POST /api/auth/magic/confirm", "activates or signs in; a pending account has no content to show"),
            Map.entry("POST /api/auth/password/change", "account credentials only"),
            Map.entry("POST /api/auth/password/request", "mail only"),
            Map.entry("POST /api/auth/password/reset", "credentials; a pending account it activates has no content"),
            Map.entry("POST /api/auth/register", "a pending account has no content"),
            Map.entry("POST /api/auth/terms", "terms acceptance appears in no synced view"),
            Map.entry("POST /api/auth/verification/confirm", "activation names a pending account that has no content yet"),
            Map.entry("POST /api/auth/verification/request", "mail only"),
            Map.entry("POST /api/clubs", "membership: the creator's derived epoch changes; a new club has no photos"),
            Map.entry("POST /api/clubs/join", "membership: the joiner's derived epoch changes"),
            Map.entry("DELETE /api/clubs/{id}/members/{userId}", "membership: the leaver's derived epoch changes"),
            Map.entry("PUT /api/clubs/{id}/members/{userId}", "role: the member's derived epoch changes"),
            Map.entry("PUT /api/clubs/{id}", "club settings appear in no synced view (clubs are a full fetch)"),
            Map.entry("POST /api/clubs/{id}/invite", "invite links appear in no synced view"),
            Map.entry("POST /api/clubs/invitations/preview", "read-only"),
            Map.entry("POST /api/clubs/{id}/wishlist", "the club wishlist is a page fetch, in no synced view"),
            Map.entry("DELETE /api/clubs/{id}/wishlist/{entryId}", "the club wishlist is a page fetch, in no synced view"),
            Map.entry("PUT /api/clubs/{id}/wishlist/{entryId}/acquired", "the club wishlist is a page fetch, in no synced view"),
            Map.entry("PUT /api/favorites/combinations/{id}", "favourites are a full fetch"),
            Map.entry("DELETE /api/favorites/combinations/{id}", "favourites are a full fetch"),
            Map.entry("PUT /api/favorites/glazes/{id}", "favourites are a full fetch"),
            Map.entry("DELETE /api/favorites/glazes/{id}", "favourites are a full fetch"),
            Map.entry("POST /api/stock", "stock is a full fetch"),
            Map.entry("POST /api/stock/add", "stock is a full fetch"),
            Map.entry("DELETE /api/stock/{id}", "stock is a full fetch"),
            Map.entry("PATCH /api/stock/{id}", "stock is a full fetch"),
            Map.entry("POST /api/buddies/link", "buddies are a full fetch"),
            Map.entry("POST /api/buddies/link/preview", "read-only"),
            Map.entry("POST /api/buddies/link/request", "buddies are a full fetch"),
            Map.entry("POST /api/buddies/requests", "buddies are a full fetch"),
            Map.entry("POST /api/buddies/{userId}/accept", "buddies and their availability are a full fetch"),
            Map.entry("POST /api/buddies/{userId}/decline", "buddies are a full fetch"),
            Map.entry("POST /api/buddies/{userId}/cancel", "buddies are a full fetch"),
            Map.entry("DELETE /api/buddies/{userId}", "buddies and their availability are a full fetch"));

    /** Setup, then the operation itself, answering the item whose log row it must write. */
    private Map<String, Supplier<Supplier<UUID>>> scenarios() {
        return Map.ofEntries(
                Map.entry("POST /api/glazes", () -> {
                    var client = user("Adder");
                    return () -> addGlaze(client, "Coverage", unique("New"), code());
                }),
                Map.entry("PATCH /api/glazes/{id}", () -> {
                    var client = user("Corrector"); UUID glaze = glaze(client);
                    return () -> { client.patch().uri("/api/glazes/{id}", glaze).body(Map.of("name", "Corrected")).exchange().expectStatus().isOk(); return glaze; };
                }),
                Map.entry("PATCH /api/glazes/{id}/tags", () -> {
                    var client = user("Tagger"); UUID glaze = glaze(client);
                    return () -> { client.patch().uri("/api/glazes/{id}/tags", glaze).body(Map.of("add", List.of("gloss"))).exchange().expectStatus().isOk(); return glaze; };
                }),
                Map.entry("PATCH /api/glazes/{id}/barcodes", () -> {
                    var client = user("Scanner"); UUID glaze = glaze(client);
                    return () -> { client.patch().uri("/api/glazes/{id}/barcodes", glaze).body(Map.of("add", List.of(ean13()))).exchange().expectStatus().isOk(); return glaze; };
                }),
                Map.entry("POST /api/glazes/{id}/images", () -> {
                    var client = user("Photographer"); UUID glaze = glaze(client);
                    return () -> { created(uploadGlazeImage(client, glaze, Map.of("visibility", "PUBLIC", "kind", "PACKAGING"), PNG)); return glaze; };
                }),
                Map.entry("DELETE /api/glazes/{id}", () -> {
                    var admin = siteAdmin(); UUID glaze = glaze(admin);
                    return () -> { admin.delete().uri("/api/glazes/{id}", glaze).exchange().expectStatus().isNoContent(); return glaze; };
                }),
                Map.entry("POST /api/combinations", () -> {
                    var client = user("Author");
                    return () -> combination(client, false);
                }),
                Map.entry("PUT /api/combinations/{id}", () -> {
                    var client = user("Editor"); UUID id = combination(client, false);
                    return () -> { client.put().uri("/api/combinations/{id}", id).body(steps("Edited")).exchange().expectStatus().isNoContent(); return id; };
                }),
                Map.entry("POST /api/combinations/{id}/publish", () -> {
                    var client = user("Publisher"); UUID id = combination(client, true);
                    return () -> { client.post().uri("/api/combinations/{id}/publish", id).exchange().expectStatus().isNoContent(); return id; };
                }),
                Map.entry("DELETE /api/combinations/{id}", () -> {
                    var client = user("Remover"); UUID id = combination(client, false);
                    return () -> { client.delete().uri("/api/combinations/{id}", id).exchange().expectStatus().isNoContent(); return id; };
                }),
                Map.entry("PATCH /api/combinations/{id}/tags", () -> {
                    var client = user("Tagger"); UUID id = combination(client, false);
                    return () -> { client.patch().uri("/api/combinations/{id}/tags", id).body(Map.of("add", List.of("blue"))).exchange().expectStatus().isOk(); return id; };
                }),
                Map.entry("PATCH /api/combinations/{id}/title", () -> {
                    var client = user("Renamer"); UUID id = combination(client, false);
                    return () -> { client.patch().uri("/api/combinations/{id}/title", id).body(Map.of("title", "Renamed")).exchange().expectStatus().isOk(); return id; };
                }),
                Map.entry("POST /api/combinations/{id}/images", () -> {
                    var client = user("Uploader"); UUID id = combination(client, false);
                    return () -> { uploadTile(client, id, "PRIVATE", null); return id; };
                }),
                Map.entry("PUT /api/images/{id}/visibility", () -> {
                    var client = user("Mover"); UUID id = combination(client, false); UUID photo = uploadTile(client, id, "PUBLIC", null);
                    return () -> { client.put().uri("/api/images/{id}/visibility", photo).body(Map.of("visibility", "PRIVATE")).exchange().expectStatus().isOk(); return id; };
                }),
                Map.entry("PATCH /api/images/{id}", () -> {
                    var client = user("Captioner"); UUID id = combination(client, false); UUID photo = uploadTile(client, id, "PRIVATE", null);
                    return () -> { body(client.patch().uri("/api/images/{id}", photo).body(Map.of("caption", "Satin")).exchange().expectStatus().isOk(), ImageView.class); return id; };
                }),
                Map.entry("PUT /api/images/{id}/kind", () -> {
                    var admin = siteAdmin(); var client = user("Retyped"); UUID id = combination(client, false); UUID photo = uploadTile(client, id, "PRIVATE", null);
                    return () -> { admin.put().uri("/api/images/{id}/kind", photo).body(Map.of("kind", "FINISHED")).exchange().expectStatus().isOk(); return id; };
                }),
                Map.entry("PUT /api/images/{id}/focus", () -> {
                    var client = user("Refocused"); UUID id = combination(client, false); UUID photo = uploadTile(client, id, "PUBLIC", null);
                    return () -> { client.put().uri("/api/images/{id}/focus", photo).body(Map.of("focusX", 20, "focusY", 70)).exchange().expectStatus().isOk(); return id; };
                }),
                Map.entry("DELETE /api/images/{id}", () -> {
                    var client = user("Deleter"); UUID id = combination(client, false); UUID photo = uploadTile(client, id, "CLUB", createClub(client, unique("Club")));
                    return () -> { client.delete().uri("/api/images/{id}", photo).exchange().expectStatus().isNoContent(); return id; };
                }),
                Map.entry("POST /api/admin/users/{id}/ban", () -> {
                    var admin = siteAdmin(); var client = user("Banned"); UUID id = combination(client, false); UUID target = me(client).id();
                    return () -> { admin.post().uri("/api/admin/users/{id}/ban", target).body(Map.of("reason", "Spam")).exchange().expectStatus().isNoContent(); return id; };
                }),
                Map.entry("POST /api/auth/delete", () -> {
                    var client = user("Leaving"); UUID id = combination(client, false);
                    return () -> { client.post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange().expectStatus().isNoContent(); return id; };
                }));
    }

    @Test void everyWriteOperationRecordsItsChangeOrIsExempt() {
        var spec = JsonMapper.builder().build().readTree(guest().get().uri("/api/openapi").exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody());
        Set<String> writes = new TreeSet<>();
        spec.get("paths").properties().forEach(path -> path.getValue().propertyNames().forEach(method -> {
            if (Set.of("post", "put", "patch", "delete").contains(method)) writes.add(method.toUpperCase() + " " + path.getKey());
        }));
        // The contract lists what the server maps: an endpoint hidden from OpenAPI would otherwise escape this test.
        Set<String> mapped = new TreeSet<>();
        handlers.getHandlerMethods().keySet().forEach(info -> info.getPatternValues().stream().filter(path -> path.startsWith("/api/"))
                .forEach(path -> {
                    var methods = info.getMethodsCondition().getMethods();
                    assertFalse(methods.isEmpty(), path + " answers every method: restrict it");
                    methods.stream().map(Enum::name).filter(Set.of("POST", "PUT", "PATCH", "DELETE")::contains)
                            .forEach(method -> mapped.add(method + " " + path));
                }));
        // (Sign-in and sign-out are security filters the document adds; nothing maps them.)
        mapped.removeAll(writes);
        assertEquals(Set.of(), mapped, "write operations missing from the OpenAPI document");
        var scenarios = new TreeMap<>(scenarios());
        Set<String> covered = new TreeSet<>(EXEMPT.keySet());
        scenarios.keySet().forEach(operation -> assertTrue(covered.add(operation), operation + " is both recorded and exempt"));
        assertEquals(writes, covered, "every write operation records through ChangeRecorder (add a scenario) or is exempt (with a reason)");
        scenarios.forEach((operation, scenario) -> {
            Supplier<UUID> run = scenario.get();
            long before = top();
            UUID item = run.get();
            assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM change_log WHERE item_id = ? AND revision > ?", Integer.class, item, before) > 0,
                    operation + " wrote no change log row for " + item);
        });
    }

    /**
     * The shape of the views a sync client stores, as of {@code SyncService.FORMAT}. Adding, removing, renaming or
     * retyping a field of these views changes it: bump {@code SyncService.FORMAT} (clients then load in full) and
     * put the new value here.
     */
    // Format 2 also took the image kind IN_PROGRESS without a bump: a new enum value adds no field, and clients store
    // and skip a kind they do not know, so no cached view needs refetching. It also took ImageView.focusX/focusY: a stored
    // view without them has only photos stored before the focus existed, whose focus is null (the centre) in the database too.
    static final Map<Integer, String> VIEW_SHAPES = Map.of(1, "9a370066b102fb711aa387d20e2298f893790d126f5532caaaf53519bad76bce",
            2, "60b4c5b38451d779ea03fb7c69b82a954699600f5b0f98cbca603ac322c6a70d");

    @Test void theStoredViewShapesBelongToTheSyncFormat() throws Exception {
        var schemas = JsonMapper.builder().build().readTree(guest().get().uri("/api/openapi").exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody()).get("components").get("schemas");
        StringBuilder shapes = new StringBuilder();
        for (String view : List.of("GlazeView", "ManufacturerView", "ImageView", "CombinationView", "StepView"))
            shapes.append(view).append('=').append(canonical(schemas.get(view))).append('\n');
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(shapes.toString().getBytes(StandardCharsets.UTF_8));
        assertEquals(VIEW_SHAPES.get(SyncService.FORMAT), HexFormat.of().formatHex(hash),
                "the stored views changed: bump SyncService.FORMAT and record the new shape");
    }

    /** Sorted keys without descriptions, so only the shape counts. */
    private static String canonical(JsonNode node) {
        if (node.isObject()) {
            var keys = new TreeSet<>(node.propertyNames());
            keys.remove("description");
            StringBuilder text = new StringBuilder("{");
            keys.forEach(key -> text.append(key).append(':').append(canonical(node.get(key))).append(','));
            return text.append('}').toString();
        }
        if (node.isArray()) {
            StringBuilder text = new StringBuilder("[");
            node.forEach(element -> text.append(canonical(element)).append(','));
            return text.append(']').toString();
        }
        return node.toString();
    }

    /**
     * The recorder flushes pending entity changes before its insert; a constraint violation found there must reach
     * callers as the DataIntegrityViolationException the commit's own flush raised before (409 handling, retries).
     */
    @Test void aViolationFlushedBeforeTheLogInsertStaysAnIntegrityFailureAndWritesNoRow() {
        String shortId = shortId();
        transactions.executeWithoutResult(status -> entities.persist(new Manufacturer(unique("First"), shortId)));
        long before = top();
        UUID item = UUID.randomUUID();
        assertThrows(DataIntegrityViolationException.class, () -> transactions.executeWithoutResult(status -> {
            changes.glaze(item);
            entities.persist(new Manufacturer(unique("Second"), shortId));
        }));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM change_log WHERE item_id = ? AND revision > ?", Integer.class, item, before));
        assertThrows(IllegalStateException.class, () -> changes.glaze(item), "outside a transaction");
    }

    /**
     * The commit-order bound of the overlap window needs the log insert to be the transaction's last statement: the
     * recorder flushes pending entity changes first (a synchronization registered later runs after its beforeCommit).
     */
    @Test void theLogInsertFollowsTheLastEntityFlush() {
        UUID item = UUID.randomUUID();
        transactions.executeWithoutResult(status -> {
            entities.persist(new Manufacturer(unique("Pending"), shortId()));
            changes.glaze(item);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) {
                    assertFalse(entities.unwrap(Session.class).isDirty(), "entity changes are flushed before the log insert");
                    assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM change_log WHERE item_id = ?", Integer.class, item));
                }
            });
        });
    }

    @Test void aRefusedWriteRecordsNothing() {
        RestTestClient client = user("Refused"), other = user("Other");
        UUID id = combination(client, false);
        long before = top();
        other.put().uri("/api/combinations/{id}", id).body(steps("Not mine")).exchange().expectStatus().isForbidden();
        // A tag edit that changes nothing writes no row either.
        client.patch().uri("/api/combinations/{id}/tags", id).body(Map.of("remove", List.of("absent"))).exchange().expectStatus().isOk();
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM change_log WHERE item_id = ? AND revision > ?", Integer.class, id, before));
    }

    private long top() {
        Long top = jdbc.queryForObject("SELECT MAX(revision) FROM change_log", Long.class);
        return top == null ? 0 : top;
    }

    private UUID glaze(RestTestClient client) {
        return addGlaze(client, "Coverage", unique("Glaze"), code());
    }

    private static String code() {
        return "C" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private static Map<String, Object> steps(String title) {
        return Map.of("title", title, "steps", List.of(Map.of("type", "CUSTOM", "label", "Firing")));
    }

    private static UUID combination(RestTestClient client, boolean draft) {
        return created(client.post().uri("/api/combinations").body(Map.of("title", "Coverage", "draft", draft,
                "steps", List.of(Map.of("type", "CUSTOM", "label", "Firing")))).exchange());
    }

    /** A random EAN-13 in the in-store range with its GS1 check digit. */
    private static String ean13() {
        StringBuilder digits = new StringBuilder("20");
        for (int i = 0; i < 10; i++) digits.append(ThreadLocalRandom.current().nextInt(10));
        int sum = 0;
        for (int i = 0; i < 12; i++) sum += (digits.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
        return digits.append((10 - sum % 10) % 10).toString();
    }
}
