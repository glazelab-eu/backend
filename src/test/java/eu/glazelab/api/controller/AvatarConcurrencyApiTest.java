package eu.glazelab.api.controller;

import eu.glazelab.api.storage.ImageStorage;
import eu.glazelab.api.support.ApiTestBase;
import eu.glazelab.api.support.TestImages;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.test.web.servlet.client.RestTestClient.ResponseSpec;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Profile picture writes on real PostgreSQL lock queues (backend guide "Profile pictures": the acting account first,
 * then the picture row or its evidence row): replacements, removals, reports, site admins' decisions, the evidence
 * purge and account deletion in each order, with no orphan objects (evidence included). Opt in
 * against a disposable database only, like {@code CombinationConcurrencyApiTest}. The objects go to a directory of this
 * class's own under {@code target/}, so the photo tests' object check of the shared image directory stays exact.
 */
@EnabledIfEnvironmentVariable(named = "GLAZELAB_TEST_POSTGRES_URL", matches = ".+")
class AvatarConcurrencyApiTest extends ApiTestBase {
    private static final Path OBJECTS = Path.of("target", "avatar-concurrency-" + UUID.randomUUID());

    @DynamicPropertySource static void postgres(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("GLAZELAB_TEST_POSTGRES_URL"));
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.datasource.username", () -> System.getenv().getOrDefault("GLAZELAB_TEST_POSTGRES_USER", "postgres"));
        properties.add("spring.datasource.password", () -> System.getenv().getOrDefault("GLAZELAB_TEST_POSTGRES_PASSWORD", ""));
        properties.add("glazelab.images.path", OBJECTS::toString);
    }
    @Autowired TransactionTemplate transactions;
    @Autowired eu.glazelab.api.service.AvatarService avatars;

    private final ExecutorService threads = Executors.newCachedThreadPool();
    private final Set<UUID> accounts = new HashSet<>();
    private Set<String> before;

    @BeforeEach void remember() throws Exception { before = files(); }
    @AfterEach void stopThreads() { threads.shutdownNow(); }

    private RestTestClient account(String name) {
        RestTestClient client = user(name);
        accounts.add(me(client).id());
        return client;
    }

    private static int status(ResponseSpec response) {
        return response.expectBody(String.class).returnResult().getStatus().value();
    }

    private static UUID id(ResponseSpec response) {
        return created(response);
    }

    private ResponseSpec replace(RestTestClient client) {
        return uploadAvatar(client, TestImages.unique());
    }

    private static ResponseSpec deleteAccount(RestTestClient client) {
        return client.post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange();
    }

    private Set<String> files() throws Exception {
        if (!Files.exists(OBJECTS)) return Set.of();
        try (var listed = Files.list(OBJECTS)) { return listed.map(path -> path.getFileName().toString()).collect(Collectors.toSet()); }
    }

    /**
     * The objects this test added are exactly both objects of its accounts' current pictures and of the evidence kept
     * of pictures its accounts reported (evidence names no account; its reports name the reporter).
     */
    private void noOrphanObjects() throws Exception {
        Set<String> keys = new HashSet<>();
        for (UUID account : accounts) {
            jdbc.queryForList("select storage_key from user_avatar where user_id = ?", String.class, account).stream()
                    .flatMap(key -> Stream.of(key, ImageStorage.cardKey(key))).forEach(keys::add);
            jdbc.queryForList("select e.storage_key from avatar_evidence e where e.storage_key is not null and exists (select 1"
                    + " from avatar_report r where r.avatar_id = e.avatar_id and r.reporter_id = ?)", String.class, account).stream()
                    .flatMap(key -> Stream.of(key, ImageStorage.cardKey(key))).forEach(keys::add);
        }
        Set<String> added = new HashSet<>(files());
        added.removeAll(before);
        assertEquals(keys, added);
    }

    private int pictures(UUID account) {
        return jdbc.queryForObject("select count(*) from user_avatar where user_id = ?", Integer.class, account);
    }

    private CompletableFuture<Integer> start(Supplier<ResponseSpec> call) {
        return CompletableFuture.supplyAsync(() -> status(call.get()), threads);
    }

    private CompletableFuture<Integer> start(CyclicBarrier barrier, Supplier<ResponseSpec> call) {
        return CompletableFuture.supplyAsync(() -> {
            try { barrier.await(5, TimeUnit.SECONDS); return status(call.get()); }
            catch (Exception error) { throw new RuntimeException(error); }
        }, threads);
    }

    private static int result(CompletableFuture<Integer> future) throws Exception { return future.get(15, TimeUnit.SECONDS); }

    /** Holds the account row while {@code first}, then {@code second}, queue for it, then lets them run in that order. */
    private int[] queued(UUID account, Supplier<ResponseSpec> first, Supplier<ResponseSpec> second) throws Exception {
        @SuppressWarnings("unchecked") CompletableFuture<Integer>[] pending = new CompletableFuture[2];
        transactions.executeWithoutResult(tx -> {
            jdbc.queryForList("select id from app_user where id = ? for no key update", UUID.class, account);
            pending[0] = start(first);
            awaitAccountWaiters(1);
            pending[1] = start(second);
            awaitAccountWaiters(2);
        });
        return new int[] {result(pending[0]), result(pending[1])};
    }

    private void awaitAccountWaiters(int expected) {
        awaitWaiters("app_user", expected);
    }

    private void awaitWaiters(String table, int expected) {
        awaitWaitersBehindMe("from " + table + " ", expected);
    }

    /** Holds the picture row while {@code first}, then {@code second}, queue for it, then lets them run in that order. */
    private int[] queuedOnPicture(UUID picture, Supplier<ResponseSpec> first, Supplier<ResponseSpec> second) throws Exception {
        @SuppressWarnings("unchecked") CompletableFuture<Integer>[] pending = new CompletableFuture[2];
        transactions.executeWithoutResult(tx -> {
            jdbc.queryForList("select id from user_avatar where id = ? for no key update", UUID.class, picture);
            pending[0] = start(first);
            awaitWaiters("user_avatar", 1);
            pending[1] = start(second);
            awaitWaiters("user_avatar", 2);
        });
        return new int[] {result(pending[0]), result(pending[1])};
    }

    private UUID reportOf(UUID picture) {
        return jdbc.queryForObject("select id from avatar_report where avatar_id = ? order by created_at limit 1", UUID.class, picture);
    }

    private String status(UUID picture) {
        return jdbc.queryForObject("select string_agg(distinct status, ',') from avatar_report where avatar_id = ?", String.class, picture);
    }

    private int blocks(UUID picture) {
        return jdbc.queryForObject("select count(*) from avatar_block b join avatar_evidence e on e.stored_sha256 = b.stored_sha256"
                + " where e.avatar_id = ?", Integer.class, picture);
    }

    @Test void twoReplacementsOfOneAccountQueueAndTheLaterWins() throws Exception {
        RestTestClient ada = account("Ada");
        UUID adaId = me(ada).id();
        var answers = new CompletableFuture[2];
        transactions.executeWithoutResult(tx -> {
            jdbc.queryForList("select id from app_user where id = ? for no key update", UUID.class, adaId);
            answers[0] = CompletableFuture.supplyAsync(() -> id(replace(ada)), threads);
            awaitAccountWaiters(1);
            answers[1] = CompletableFuture.supplyAsync(() -> id(replace(ada)), threads);
            awaitAccountWaiters(2);
        });
        UUID first = (UUID) answers[0].get(15, TimeUnit.SECONDS), second = (UUID) answers[1].get(15, TimeUnit.SECONDS);
        assertNotEquals(first, second);
        assertEquals(second, me(ada).avatarId());
        assertEquals(1, pictures(adaId));
        noOrphanObjects();
    }

    @Test void aRemovalAndAReplacementQueueInEitherOrder() throws Exception {
        RestTestClient ada = account("Ada");
        UUID adaId = me(ada).id();
        UUID old = avatar(ada);
        int[] removeFirst = queued(adaId, () -> ada.delete().uri("/api/avatars/{id}", old).exchange(), () -> replace(ada));
        assertArrayEquals(new int[] {204, 200}, removeFirst);
        UUID current = me(ada).avatarId();
        assertNotNull(current);
        int[] replaceFirst = queued(adaId, () -> replace(ada), () -> ada.delete().uri("/api/avatars/{id}", current).exchange());
        // The removal named the picture the replacement has just removed: 404, and the new picture stays.
        assertArrayEquals(new int[] {200, 404}, replaceFirst);
        assertNotEquals(current, me(ada).avatarId());
        assertEquals(1, pictures(adaId));
        noOrphanObjects();
    }

    @Test void aReplacementAndTheAccountDeletionQueueInEitherOrder() throws Exception {
        RestTestClient ada = account("Ada");
        UUID adaId = me(ada).id();
        avatar(ada);
        // The replacement first: the deletion then removes the new picture with the account.
        assertArrayEquals(new int[] {200, 204}, queued(adaId, () -> replace(ada), () -> deleteAccount(ada)));
        assertEquals(0, pictures(adaId));
        noOrphanObjects();

        RestTestClient bea = account("Bea");
        UUID beaId = me(bea).id();
        avatar(bea);
        // The deletion first: the replacement finds no account (401) and removes the objects it wrote.
        assertArrayEquals(new int[] {204, 401}, queued(beaId, () -> deleteAccount(bea), () -> replace(bea)));
        assertEquals(0, pictures(beaId));
        noOrphanObjects();
    }

    @Test void moderationAndReportsRaceReplacementsAndDeletionsWithoutDeadlocks() throws Exception {
        RestTestClient ada = account("Ada");
        RestTestClient bea = account("Bea");
        RestTestClient admin = siteAdmin();
        UUID adaId = me(ada).id();
        for (int round = 0; round < 5; round++) {
            UUID picture = avatar(ada);
            CyclicBarrier barrier = new CyclicBarrier(3);
            var removal = start(barrier, () -> admin.delete().uri("/api/avatars/{id}", picture).exchange());
            var report = start(barrier, () -> bea.post().uri("/api/avatars/{id}/reports", picture).body(Map.of("reason", "Race")).exchange());
            var replacement = start(barrier, () -> replace(ada));
            int removed = result(removal), reported = result(report);
            assertTrue(List.of(204, 404).contains(removed));
            assertTrue(List.of(204, 404).contains(reported));
            assertEquals(200, result(replacement));
            // The raced picture is gone whichever came first; a report that got in stays, with the picture as evidence.
            assertEquals(0, jdbc.queryForObject("select count(*) from user_avatar where id = ?", Integer.class, picture));
            int reports = jdbc.queryForObject("select count(*) from avatar_report where avatar_id = ?", Integer.class, picture);
            assertEquals(reported == 204 ? 1 : 0, reports);
            if (reports == 1) {
                assertNotNull(jdbc.queryForObject("select storage_key from avatar_evidence where avatar_id = ?", String.class, picture));
                // Removed by the admin after the report: ACTIONED; replaced first: still OPEN.
                String outcome = jdbc.queryForObject("select outcome from avatar_evidence where avatar_id = ?", String.class, picture);
                assertEquals(outcome.equals("ADMIN_REMOVED") ? "ACTIONED" : "OPEN", status(picture));
            }
            assertTrue(pictures(adaId) <= 1);
            noOrphanObjects();
        }
        UUID last = me(ada).avatarId();
        CyclicBarrier barrier = new CyclicBarrier(2);
        var report = start(barrier, () -> bea.post().uri("/api/avatars/{id}/reports", last).body(Map.of()).exchange());
        var deletion = start(barrier, () -> deleteAccount(ada));
        int reported = result(report);
        assertTrue(List.of(204, 404).contains(reported));
        assertEquals(204, result(deletion));
        assertEquals(0, pictures(adaId));
        // A report ahead of the deletion is CLOSED with it (no account, no copy of the picture, the fingerprints in its
        // evidence row); one behind it is 404.
        assertEquals(reported == 204 ? 1 : 0, jdbc.queryForObject("select count(*) from avatar_report where avatar_id = ?"
                + " and owner_id is null and status = 'CLOSED'", Integer.class, last));
        assertEquals(reported == 204 ? 1 : 0, jdbc.queryForObject("select count(*) from avatar_evidence where avatar_id = ?"
                + " and storage_key is null", Integer.class, last));
        noOrphanObjects();
    }

    /** A site admin's decision on a report and the owner's replacement queue on the picture row in either order. */
    @Test void aDecisionAndAReplacementQueueInEitherOrderAndTheDecisionAlwaysBlocks() throws Exception {
        RestTestClient ada = account("Ada");
        RestTestClient bea = account("Bea");
        RestTestClient admin = siteAdmin();
        for (boolean decisionFirst : List.of(true, false)) {
            UUID picture = avatar(ada);
            bea.post().uri("/api/avatars/{id}/reports", picture).body(Map.of()).exchange().expectStatus().isNoContent();
            UUID report = reportOf(picture);
            Supplier<ResponseSpec> decide = () -> admin.post().uri("/api/admin/avatar-reports/{id}/remove", report).exchange();
            Supplier<ResponseSpec> replacement = () -> replace(ada);
            int[] answers = decisionFirst ? queuedOnPicture(picture, decide, replacement) : queuedOnPicture(picture, replacement, decide);
            assertArrayEquals(new int[] {decisionFirst ? 204 : 200, decisionFirst ? 200 : 204}, answers);
            assertEquals("ACTIONED", status(picture));
            assertEquals(decisionFirst ? "ADMIN_REMOVED" : "REPLACED",
                    jdbc.queryForObject("select outcome from avatar_evidence where avatar_id = ?", String.class, picture));
            assertEquals(1, blocks(picture));
            assertNotNull(me(ada).avatarId());
            noOrphanObjects();
        }
    }

    /**
     * The owner's account deletion and a site admin's decision queue on the picture row in either order: the report
     * stays, the picture and its evidence copy go with the account (34-7), the fingerprint stays; a decision ahead of
     * the deletion blocks the picture, one behind it finds the report CLOSED (409) and blocks nothing.
     */
    @Test void aDecisionAndTheOwnersAccountDeletionQueueInEitherOrder() throws Exception {
        RestTestClient bea = account("Bea");
        RestTestClient admin = siteAdmin();
        for (boolean decisionFirst : List.of(true, false)) {
            RestTestClient ada = account("Ada");
            UUID picture = avatar(ada);
            bea.post().uri("/api/avatars/{id}/reports", picture).body(Map.of()).exchange().expectStatus().isNoContent();
            UUID report = reportOf(picture);
            Supplier<ResponseSpec> decide = () -> admin.post().uri("/api/admin/avatar-reports/{id}/remove", report).exchange();
            Supplier<ResponseSpec> deletion = () -> deleteAccount(ada);
            int[] answers = decisionFirst ? queuedOnPicture(picture, decide, deletion) : queuedOnPicture(picture, deletion, decide);
            assertArrayEquals(new int[] {204, decisionFirst ? 204 : 409}, answers);
            assertEquals(decisionFirst ? "ACTIONED" : "CLOSED", status(picture));
            assertEquals(decisionFirst ? "ADMIN_REMOVED" : "ACCOUNT_DELETED",
                    jdbc.queryForObject("select outcome from avatar_evidence where avatar_id = ?", String.class, picture));
            assertNull(jdbc.queryForObject("select storage_key from avatar_evidence where avatar_id = ?", String.class, picture));
            assertEquals(0, jdbc.queryForObject("select count(*) from avatar_report where avatar_id = ? and owner_id is not null",
                    Integer.class, picture));
            assertEquals(decisionFirst ? 1 : 0, blocks(picture));
            noOrphanObjects();
        }
    }

    /** The purge, an account deletion and decisions on the same pictures run at once without deadlocks or orphans. */
    @Test void thePurgeRacesAccountDeletionAndDecisions() throws Exception {
        RestTestClient bea = account("Bea");
        RestTestClient admin = siteAdmin();
        for (int round = 0; round < 3; round++) {
            RestTestClient ada = account("Ada");
            UUID expired = avatar(ada);
            bea.post().uri("/api/avatars/{id}/reports", expired).body(Map.of()).exchange().expectStatus().isNoContent();
            UUID open = avatar(ada);
            bea.post().uri("/api/avatars/{id}/reports", open).body(Map.of()).exchange().expectStatus().isNoContent();
            admin.post().uri("/api/admin/avatar-reports/{id}/dismiss", reportOf(expired)).exchange().expectStatus().isNoContent();
            jdbc.update("update avatar_report set closed_at = closed_at - interval '91 days' where avatar_id = ?", expired);
            String expiredKey = jdbc.queryForObject("select storage_key from avatar_evidence where avatar_id = ?", String.class, expired);
            CyclicBarrier barrier = new CyclicBarrier(4);
            var purge = CompletableFuture.supplyAsync(() -> {
                try { barrier.await(5, TimeUnit.SECONDS); return avatars.purgeEvidence(); }
                catch (Exception error) { throw new RuntimeException(error); }
            }, threads);
            var deletion = start(barrier, () -> deleteAccount(ada));
            var decision = start(barrier, () -> admin.post().uri("/api/admin/avatar-reports/{id}/remove", reportOf(open)).exchange());
            var late = start(barrier, () -> admin.post().uri("/api/admin/avatar-reports/{id}/dismiss", reportOf(expired)).exchange());
            purge.get(15, TimeUnit.SECONDS);
            assertEquals(204, result(deletion));
            int decided = result(decision);
            assertTrue(List.of(204, 409).contains(decided), "a decision behind the deletion finds the report CLOSED");
            assertEquals(204, result(late));
            assertNull(jdbc.queryForObject("select storage_key from avatar_evidence where avatar_id = ?", String.class, expired));
            assertFalse(Files.exists(OBJECTS.resolve(expiredKey)));
            // The account's deletion takes every copy along (34-7), whatever came first; the fingerprint stays.
            assertNull(jdbc.queryForObject("select storage_key from avatar_evidence where avatar_id = ?", String.class, open));
            assertEquals(decided == 204 ? "ACTIONED" : "CLOSED", status(open));
            assertEquals(decided == 204 ? 1 : 0, blocks(open));
            noOrphanObjects();
        }
    }
}
