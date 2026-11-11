package eu.glazelab.api.controller;

import eu.glazelab.api.storage.ImageStorage;
import eu.glazelab.api.support.ApiTestBase;
import eu.glazelab.api.support.TestImages;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL lock/FK behavior; opt in against a disposable database only. */
@EnabledIfEnvironmentVariable(named = "GLAZELAB_TEST_POSTGRES_URL", matches = ".+")
class CombinationConcurrencyApiTest extends ApiTestBase {
    @DynamicPropertySource static void postgres(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("GLAZELAB_TEST_POSTGRES_URL"));
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.datasource.username", () -> System.getenv().getOrDefault("GLAZELAB_TEST_POSTGRES_USER", "postgres"));
        properties.add("spring.datasource.password", () -> System.getenv().getOrDefault("GLAZELAB_TEST_POSTGRES_PASSWORD", ""));
        properties.add("glazelab.images.path", () -> System.getenv("GLAZELAB_TEST_POSTGRES_IMAGES"));
    }
    @Autowired TransactionTemplate transactions;

    private static int status(org.springframework.test.web.servlet.client.RestTestClient.ResponseSpec response) {
        return response.returnResult().getStatus().value();
    }
    private static CompletableFuture<Integer> start(CyclicBarrier barrier, IntSupplier action) {
        return CompletableFuture.supplyAsync(() -> {
            try { barrier.await(5, TimeUnit.SECONDS); return action.getAsInt(); }
            catch (Exception error) { throw new RuntimeException(error); }
        });
    }
    private static int result(CompletableFuture<Integer> future) throws Exception { return future.get(15, TimeUnit.SECONDS); }
    private static Map<String, Object> body(String label) {
        return Map.of("title", "Concurrent", "steps", List.of(Map.of("type", "CUSTOM", "label", label)));
    }
    private void noOrphanObjects() throws Exception {
        // Every picture has two objects: its full size and its card copy.
        var keys = jdbc.queryForList("select storage_key from image", String.class).stream()
                .flatMap(key -> Stream.of(key, ImageStorage.cardKey(key)))
                .collect(Collectors.toSet());
        Path directory = Path.of(System.getenv("GLAZELAB_TEST_POSTGRES_IMAGES"));
        Set<String> files;
        if (!Files.exists(directory)) files = Set.of();
        else try (var listed = Files.list(directory)) { files = listed.map(path -> path.getFileName().toString()).collect(Collectors.toSet()); }
        assertEquals(keys, files);
    }

    @Test void foreignUploadAndStructuralEditSerializeOnTheParent() throws Exception {
        var author = user("Author"); var uploader = user("Uploader");
        UUID id = created(author.post().uri("/api/combinations").body(body("Original")).exchange());
        CyclicBarrier barrier = new CyclicBarrier(2);
        var edit = start(barrier, () -> status(author.put().uri("/api/combinations/{id}", id).body(body("Changed")).exchange()));
        var upload = start(barrier, () -> status(upload(uploader, id, Map.of("visibility", "PUBLIC", "kind", "TEST_TILE"), TestImages.png(20, 20))));
        int edited = result(edit);
        assertEquals(200, result(upload)); assertTrue(edited == 204 || edited == 409);
        var view = combination(author, id).orElseThrow();
        assertTrue(view.stepsLocked()); assertEquals(1, view.images().size());
        assertEquals(edited == 204 ? "Changed" : "Original", view.steps().getFirst().label());
        noOrphanObjects();
    }

    @Test void foreignUploadAndDeletionEitherKeepBothRecordsOrRejectTheUploadWithoutAnObject() throws Exception {
        var author = user("Author"); var uploader = user("Uploader");
        UUID id = created(author.post().uri("/api/combinations").body(body("Original")).exchange());
        CyclicBarrier barrier = new CyclicBarrier(2);
        var deletion = start(barrier, () -> status(author.delete().uri("/api/combinations/{id}", id).exchange()));
        var upload = start(barrier, () -> status(upload(uploader, id, Map.of("visibility", "PUBLIC", "kind", "TEST_TILE"), TestImages.png(20, 20))));
        int removed = result(deletion), uploaded = result(upload);
        assertTrue((removed == 204 && uploaded == 404) || (removed == 409 && uploaded == 200), removed + "/" + uploaded);
        assertEquals(removed == 409, combination(author, id).isPresent());
        noOrphanObjects();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void publicIdRotationNeverOpensAnEditOrDeleteWindow(boolean deleting) throws Exception {
        var author = user("Author"); var uploader = user("Uploader");
        // Leaving PUBLIC on someone else's combination goes to a club its author shares.
        UUID club = createClub(author, unique("Shared"));
        joinClub(author, club, uploader);
        UUID id = created(author.post().uri("/api/combinations").body(body("Original")).exchange());
        UUID photo = created(upload(uploader, id, Map.of("visibility", "PUBLIC", "kind", "TEST_TILE"), TestImages.png(20, 20)));
        CyclicBarrier barrier = new CyclicBarrier(2);
        var rotation = start(barrier, () -> status(uploader.put().uri("/api/images/{id}/visibility", photo)
                .body(Map.of("visibility", "CLUB", "clubId", club)).exchange()));
        var change = start(barrier, () -> status(deleting ? author.delete().uri("/api/combinations/{id}", id).exchange()
                : author.put().uri("/api/combinations/{id}", id).body(body("Changed")).exchange()));
        assertEquals(200, result(rotation)); assertEquals(409, result(change));
        assertTrue(combination(author, id).orElseThrow().stepsLocked());
        assertEquals(0, jdbc.queryForObject("select count(*) from image where id = ?", Integer.class, photo));
        noOrphanObjects();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void destinationClubLocksPrecedeOwnedParentsDuringSoleMemberAccountDeletion(boolean rotation) throws Exception {
        var owner = user("ClubOwner"); var member = user("FormerMember");
        UUID ownerId = me(owner).id(), memberId = me(member).id();
        UUID club = createClub(owner, unique("Sole"));
        joinClub(owner, club, member);
        UUID combination = created(owner.post().uri("/api/combinations").body(body("Shared")).exchange());
        UUID photo = rotation ? created(upload(member, combination, Map.of("visibility", "PUBLIC", "kind", "TEST_TILE"), TestImages.png(20, 20))) : null;
        @SuppressWarnings("unchecked") CompletableFuture<Integer>[] pending = new CompletableFuture[2];
        transactions.executeWithoutResult(tx -> {
            jdbc.queryForObject("select id from club where id = ? for update", UUID.class, club);
            jdbc.update("delete from membership where club_id = ? and user_id = ?", club, memberId);
            // Deletion queues first on the club. The other transaction must not hold the parent's lock.
            pending[0] = CompletableFuture.supplyAsync(() -> status(owner.post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange()));
            awaitClubWaiters(1);
            pending[1] = CompletableFuture.supplyAsync(() -> rotation
                    ? status(member.put().uri("/api/images/{id}/visibility", photo).body(Map.of("visibility", "CLUB", "clubId", club)).exchange())
                    : status(upload(member, combination, Map.of("visibility", "CLUB", "clubId", club.toString(), "kind", "TEST_TILE"), TestImages.png(20, 20))));
            awaitClubWaiters(2);
        });
        assertEquals(204, result(pending[0])); assertEquals(403, result(pending[1]));
        assertEquals(0, jdbc.queryForObject("select count(*) from app_user where id = ?", Integer.class, ownerId));
        assertEquals(0, jdbc.queryForObject("select count(*) from image_deletion where uploader_id = ? or deleted_by = ?", Integer.class, ownerId, ownerId));
        noOrphanObjects();
    }

    @Test void formerMemberEditLocksNewGlazesBeforeItsParentWhileTheSoleClubDisappears() throws Exception {
        var owner = user("ClubOwner"); var former = user("FormerMember");
        UUID club = createClub(owner, unique("Sole")); UUID formerId = me(former).id();
        joinClub(owner, club, former);
        UUID glaze = addGlaze(owner, unique("Authored"), "Blue", "PC-20");
        UUID id = created(former.post().uri("/api/combinations").body(body("Original")).exchange());
        created(upload(former, id, Map.of("visibility", "CLUB", "clubId", club.toString(), "kind", "TEST_TILE"), TestImages.png(20, 20)));
        jdbc.update("delete from membership where club_id = ? and user_id = ?", club, formerId);
        CyclicBarrier barrier = new CyclicBarrier(2);
        var deletion = start(barrier, () -> status(owner.post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange()));
        var edit = start(barrier, () -> status(former.put().uri("/api/combinations/{id}", id)
                .body(Map.of("title", "Changed", "steps", List.of(Map.of("glazeId", glaze, "coats", 2)))).exchange()));
        assertEquals(204, result(deletion)); assertEquals(204, result(edit));
        assertEquals(glaze, combination(former, id).orElseThrow().steps().getFirst().glazeId());
        noOrphanObjects();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void uploadQueuedFirstClosesTheWindowBeforeAnEditOrDelete(boolean deleting) throws Exception {
        var author = user("Author"); var uploader = user("Uploader");
        UUID id = created(author.post().uri("/api/combinations").body(body("Original")).exchange());
        @SuppressWarnings("unchecked") CompletableFuture<Integer>[] pending = new CompletableFuture[2];
        transactions.executeWithoutResult(tx -> {
            jdbc.queryForObject("select id from combination where id = ? for update", UUID.class, id);
            pending[0] = CompletableFuture.supplyAsync(() -> status(upload(uploader, id,
                    Map.of("visibility", "PUBLIC", "kind", "TEST_TILE"), TestImages.png(20, 20))));
            awaitWaiters("combination", 1);
            pending[1] = CompletableFuture.supplyAsync(() -> status(deleting
                    ? author.delete().uri("/api/combinations/{id}", id).exchange()
                    : author.put().uri("/api/combinations/{id}", id).body(body("Changed")).exchange()));
            awaitWaiters("combination", 2);
        });
        assertEquals(200, result(pending[0])); assertEquals(409, result(pending[1]));
        assertEquals("Original", combination(author, id).orElseThrow().steps().getFirst().label());
        noOrphanObjects();
    }

    @Test void publicRotationAndUploaderAccountDeletionKeepAuditIdsAndObjectsConsistent() throws Exception {
        var uploader = user("Uploader");
        var account = me(uploader); var secondSession = guest();
        login(secondSession, account.email(), PASSWORD).expectStatus().isOk();
        // The uploader's own combination, so the photo may leave PUBLIC for Only me.
        UUID id = created(uploader.post().uri("/api/combinations").body(body("Original")).exchange());
        UUID photo = created(upload(uploader, id, Map.of("visibility", "PUBLIC", "kind", "TEST_TILE"), TestImages.png(20, 20)));
        CyclicBarrier barrier = new CyclicBarrier(2);
        var rotation = start(barrier, () -> status(uploader.put().uri("/api/images/{id}/visibility", photo).body(Map.of("visibility", "PRIVATE")).exchange()));
        var deletion = start(barrier, () -> status(secondSession.post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange()));
        int rotated = result(rotation);
        assertTrue(rotated == 200 || rotated == 401, "rotation status " + rotated);
        assertEquals(204, result(deletion));
        assertEquals(0, jdbc.queryForObject("select count(*) from image_deletion where uploader_id = ? or deleted_by = ?", Integer.class, account.id(), account.id()));
        assertEquals(0, jdbc.queryForObject("select count(*) from image where uploaded_by = ?", Integer.class, account.id()));
        noOrphanObjects();
    }

    @Test void packagingKindEditAndSoleClubDeletionSerializeWithoutOrphans() throws Exception {
        var owner = user("PackagingClubOwner"); var former = user("PackagingFormer");
        UUID club = createClub(owner, unique("Packaging sole")); UUID formerId = me(former).id();
        joinClub(owner, club, former);
        UUID glaze = addGlaze(owner, unique("Packaging parent"), "Blue", "P-1");
        UUID photo = created(uploadGlazeImage(former, glaze, Map.of("visibility", "CLUB", "clubId", club.toString(), "kind", "FINISHED"), TestImages.png(20, 20)));
        jdbc.update("delete from membership where club_id = ? and user_id = ?", club, formerId);
        CyclicBarrier barrier = new CyclicBarrier(2);
        var deletion = start(barrier, () -> status(owner.post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange()));
        var edit = start(barrier, () -> status(former.patch().uri("/api/images/{id}", photo).body(Map.of("kind", "PACKAGING")).exchange()));
        assertEquals(204, result(deletion));
        int edited = result(edit);
        assertTrue(edited == 200 || edited == 404, "kind edit status " + edited);
        assertEquals(0, jdbc.queryForObject("select count(*) from image where id = ?", Integer.class, photo));
        noOrphanObjects();
    }

    /** A code change and a combination that adds a glaze step queue on the glaze row; the first in line decides. */
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void glazeCodeChangeAndANewGlazeStepSerializeOnTheGlaze(boolean stepFirst) throws Exception {
        var author = user("Author"); var editor = user("Editor");
        UUID glaze = addGlaze(author, unique("Contended"), "Blue", "PC-20");
        Map<String, Object> combination = Map.of("title", "Uses it", "steps", List.of(Map.of("glazeId", glaze, "coats", 1)));
        IntSupplier create = () -> status(author.post().uri("/api/combinations").body(combination).exchange());
        IntSupplier recode = () -> status(editor.patch().uri("/api/glazes/{id}", glaze).body(Map.of("code", "PC-21")).exchange());
        @SuppressWarnings("unchecked") CompletableFuture<Integer>[] pending = new CompletableFuture[2];
        transactions.executeWithoutResult(tx -> {
            jdbc.queryForObject("select id from glaze where id = ? for update", UUID.class, glaze);
            pending[0] = CompletableFuture.supplyAsync(() -> (stepFirst ? create : recode).getAsInt());
            awaitWaiters("glaze", 1);
            pending[1] = CompletableFuture.supplyAsync(() -> (stepFirst ? recode : create).getAsInt());
            awaitWaiters("glaze", 2);
        });
        int created = result(pending[stepFirst ? 0 : 1]), recoded = result(pending[stepFirst ? 1 : 0]);
        assertEquals(200, created);
        assertEquals(stepFirst ? 403 : 200, recoded);
        assertEquals(stepFirst ? "PC-20" : "PC-21", jdbc.queryForObject("select code from glaze where id = ?", String.class, glaze));
    }

    /** A code taken by another glaze after the check (the unique index decides) is the same 409 as a code seen taken. */
    @Test void aCodeTakenAfterTheCheckIsTheGlazeExistsConflict() throws Exception {
        var editor = user("Editor");
        String maker = unique("Contested");
        UUID first = addGlaze(editor, maker, "Blue", "PC-1");
        UUID second = addGlaze(editor, maker, "Red", "PC-2");
        @SuppressWarnings("unchecked") CompletableFuture<org.springframework.test.web.servlet.client.RestTestClient.ResponseSpec>[] pending = new CompletableFuture[1];
        transactions.executeWithoutResult(tx -> {
            jdbc.update("update glaze set code = 'PC-9' where id = ?", second);
            pending[0] = CompletableFuture.supplyAsync(() -> editor.patch().uri("/api/glazes/{id}", first).body(Map.of("code", "PC-9")).exchange());
            awaitWaitersBehindMe("update glaze ", 1);
        });
        assertError(pending[0].get(15, TimeUnit.SECONDS), 409, eu.glazelab.api.service.GlazeService.GLAZE_EXISTS);
        assertEquals("PC-1", jdbc.queryForObject("select code from glaze where id = ?", String.class, first));
        assertEquals(0, jdbc.queryForObject("select count(*) from glaze_edit where glaze_id = ?", Integer.class, first));
    }

    /**
     * 23-61: a member's account deletion (account, then club), an admin removing that member (admin's account, then
     * club; never the member's account) and the member's club stock save (club) queue on the club lock in each order
     * without a deadlock, and the club shelf keeps whatever the member added, without their name.
     */
    @ParameterizedTest @ValueSource(ints = {0, 1, 2})
    void leaveRemovalStockSaveAndAccountDeletionQueueOnTheClub(int order) throws Exception {
        var admin = user("Admin"); var member = user("Member");
        UUID adminId = me(admin).id(), memberId = me(member).id();
        UUID club = createClub(admin, unique("Queue"));
        joinClub(admin, club, member);
        UUID glaze = addGlaze(admin, unique("Queued"), "Blue", "PC-20");
        Map<String, IntSupplier> actions = Map.of(
                "delete", () -> status(member.post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange()),
                "remove", () -> status(admin.delete().uri("/api/clubs/{id}/members/{userId}", club, memberId).exchange()),
                "stock", () -> status(member.post().uri("/api/stock").body(Map.of("glazeId", glaze, "quantityG", 100,
                        "ownerType", "club", "clubId", club)).exchange()));
        List<String> queue = switch (order) {
            case 0 -> List.of("delete", "remove", "stock");
            case 1 -> List.of("remove", "stock", "delete");
            default -> List.of("stock", "delete", "remove");
        };
        Map<String, CompletableFuture<Integer>> pending = new java.util.HashMap<>();
        transactions.executeWithoutResult(tx -> {
            jdbc.queryForObject("select id from club where id = ? for update", UUID.class, club);
            for (String action : queue) {
                pending.put(action, CompletableFuture.supplyAsync(actions.get(action)::getAsInt));
                awaitClubWaiters(pending.size());
            }
        });
        Map<String, Integer> expected = switch (order) {
            case 0 -> Map.of("delete", 204, "remove", 404, "stock", 403);
            case 1 -> Map.of("remove", 204, "stock", 403, "delete", 204);
            default -> Map.of("stock", 200, "delete", 204, "remove", 404);
        };
        for (String action : queue) assertEquals(expected.get(action), result(pending.get(action)), action);
        assertEquals(0, jdbc.queryForObject("select count(*) from app_user where id = ?", Integer.class, memberId));
        assertEquals(List.of(adminId), jdbc.queryForList("select user_id from membership where club_id = ?", UUID.class, club));
        assertEquals(order == 2 ? 1 : 0, jdbc.queryForObject("select count(*) from stock where club_id = ?", Integer.class, club));
        assertEquals(0, jdbc.queryForObject("select count(*) from stock where added_by is not null and club_id = ?", Integer.class, club));
    }

    /**
     * 23-61: the author's leave and a foreign CLUB upload to their combination serialize on the club lock: a leave
     * first refuses the photo (the author's membership is read under that lock), an upload first stays with the club
     * and stops locking the author's steps once they left.
     */
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void theAuthorsLeaveAndAForeignClubUploadSerializeOnTheClub(boolean leaveFirst) throws Exception {
        var admin = user("Admin"); var author = user("Author");
        UUID authorId = me(author).id();
        UUID club = createClub(admin, unique("Leaving"));
        joinClub(admin, club, author);
        UUID id = created(author.post().uri("/api/combinations").body(body("Original")).exchange());
        IntSupplier leave = () -> status(author.delete().uri("/api/clubs/{id}/members/{userId}", club, authorId).exchange());
        IntSupplier upload = () -> status(upload(admin, id, Map.of("visibility", "CLUB", "clubId", club.toString(), "kind", "TEST_TILE"),
                TestImages.png(20, 20)));
        @SuppressWarnings("unchecked") CompletableFuture<Integer>[] pending = new CompletableFuture[2];
        transactions.executeWithoutResult(tx -> {
            jdbc.queryForObject("select id from club where id = ? for update", UUID.class, club);
            pending[0] = CompletableFuture.supplyAsync((leaveFirst ? leave : upload)::getAsInt);
            awaitClubWaiters(1);
            pending[1] = CompletableFuture.supplyAsync((leaveFirst ? upload : leave)::getAsInt);
            awaitClubWaiters(2);
        });
        int left = result(pending[leaveFirst ? 0 : 1]), uploaded = result(pending[leaveFirst ? 1 : 0]);
        assertEquals(204, left);
        assertEquals(leaveFirst ? 400 : 200, uploaded);
        assertEquals(leaveFirst ? 0 : 1, jdbc.queryForObject("select count(*) from image where combination_id = ?", Integer.class, id));
        var view = combination(author, id).orElseThrow();
        assertFalse(view.stepsLocked());
        assertTrue(view.images().isEmpty());
        noOrphanObjects();
    }

    /**
     * 23-61: the last-admin guard holds under real lock queues. Two admins leaving at once leave one admin behind,
     * and an admin's leave queued with their demotion of the other admin (either order) never empties the admins.
     */
    @ParameterizedTest @ValueSource(ints = {0, 1, 2})
    void concurrentLeavesAndDemotionsAlwaysKeepAnAdmin(int order) throws Exception {
        var ada = user("Ada"); var bea = user("Bea");
        UUID adaId = me(ada).id(), beaId = me(bea).id();
        UUID club = createClub(ada, unique("Admins"));
        joinClub(ada, club, bea);
        ada.put().uri("/api/clubs/{id}/members/{userId}", club, beaId).body(Map.of("role", "ADMIN")).exchange().expectStatus().isOk();
        IntSupplier adaLeaves = () -> status(ada.delete().uri("/api/clubs/{id}/members/{userId}", club, adaId).exchange());
        IntSupplier beaLeaves = () -> status(bea.delete().uri("/api/clubs/{id}/members/{userId}", club, beaId).exchange());
        IntSupplier adaDemotesBea = () -> status(ada.put().uri("/api/clubs/{id}/members/{userId}", club, beaId)
                .body(Map.of("role", "MEMBER")).exchange());
        List<IntSupplier> queue = switch (order) {
            case 0 -> List.of(adaLeaves, beaLeaves);
            case 1 -> List.of(adaLeaves, adaDemotesBea);
            default -> List.of(adaDemotesBea, adaLeaves);
        };
        List<CompletableFuture<Integer>> pending = new java.util.ArrayList<>();
        transactions.executeWithoutResult(tx -> {
            jdbc.queryForObject("select id from club where id = ? for update", UUID.class, club);
            for (IntSupplier action : queue) {
                pending.add(CompletableFuture.supplyAsync(action::getAsInt));
                awaitClubWaiters(pending.size());
            }
        });
        List<Integer> statuses = new java.util.ArrayList<>();
        for (var future : pending) statuses.add(result(future));
        assertEquals(switch (order) {
            case 0 -> List.of(204, 409);
            // Ada left before her demotion of Bea got the lock: the club she no longer sees answers as an unknown one (25-5).
            case 1 -> List.of(204, 404);
            default -> List.of(200, 409);
        }, statuses);
        assertEquals(1, jdbc.queryForObject("select count(*) from membership where club_id = ? and role = 'ADMIN'", Integer.class, club));
    }

    private void awaitClubWaiters(int expected) { awaitWaiters("club", expected); }
    private void awaitWaiters(String table, int expected) {
        awaitWaitersBehindMe("from " + table + " ", expected);
    }
}
