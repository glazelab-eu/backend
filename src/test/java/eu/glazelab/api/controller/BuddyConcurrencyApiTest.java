package eu.glazelab.api.controller;

import eu.glazelab.api.dto.BuddyDtos.BuddyLinkPreviewView;
import eu.glazelab.api.dto.BuddyDtos.Relation;
import eu.glazelab.api.model.BuddyId;
import eu.glazelab.api.support.ApiTestBase;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.test.web.servlet.client.RestTestClient.ResponseSpec;
import org.springframework.transaction.support.TransactionTemplate;

import static eu.glazelab.api.controller.BuddyApiTest.act;
import static eu.glazelab.api.controller.BuddyApiTest.link;
import static eu.glazelab.api.controller.BuddyApiTest.preview;
import static eu.glazelab.api.controller.BuddyApiTest.requestByLink;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Buddy writes on real PostgreSQL lock queues (the two account rows in {@code BuddyId.ORDER}); opt in against a
 * disposable database only, like {@code CombinationConcurrencyApiTest}.
 */
@EnabledIfEnvironmentVariable(named = "GLAZELAB_TEST_POSTGRES_URL", matches = ".+")
class BuddyConcurrencyApiTest extends ApiTestBase {
    @DynamicPropertySource static void postgres(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("GLAZELAB_TEST_POSTGRES_URL"));
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.datasource.username", () -> System.getenv().getOrDefault("GLAZELAB_TEST_POSTGRES_USER", "postgres"));
        properties.add("spring.datasource.password", () -> System.getenv().getOrDefault("GLAZELAB_TEST_POSTGRES_PASSWORD", ""));
        properties.add("glazelab.images.path", () -> System.getenv("GLAZELAB_TEST_POSTGRES_IMAGES"));
    }
    @Autowired TransactionTemplate transactions;

    /** A finished call: its status and, for a request, the relation it answered. */
    private record Answer(int status, Relation relation) {}

    private static Answer answer(ResponseSpec response) {
        var result = response.expectBody(String.class).returnResult();
        int status = result.getStatus().value();
        String body = result.getResponseBody();
        Relation relation = status == 200 && body != null && body.contains("relation")
                ? Relation.valueOf(body.replaceAll(".*\"relation\":\"([A-Z]+)\".*", "$1")) : null;
        return new Answer(status, relation);
    }

    /** One thread per caller: a barrier of more callers than the common pool's threads would never open. */
    private final java.util.concurrent.ExecutorService threads = java.util.concurrent.Executors.newCachedThreadPool();
    @org.junit.jupiter.api.AfterEach void stopThreads() { threads.shutdownNow(); }

    private CompletableFuture<Answer> start(CyclicBarrier barrier, Supplier<ResponseSpec> call) {
        return CompletableFuture.supplyAsync(() -> {
            try { barrier.await(5, TimeUnit.SECONDS); return answer(call.get()); }
            catch (Exception error) { throw new RuntimeException(error); }
        }, threads);
    }

    private static Answer result(CompletableFuture<Answer> future) throws Exception { return future.get(15, TimeUnit.SECONDS); }

    /**
     * Holds both account rows of a pair while {@code first}, then {@code second}, queue for them, then lets them run in
     * that order (PostgreSQL grants a row lock to its waiters in order).
     */
    private Answer[] queued(UUID one, UUID other, int waitersFirst, Supplier<ResponseSpec> first, int waitersSecond, Supplier<ResponseSpec> second) throws Exception {
        @SuppressWarnings("unchecked") CompletableFuture<Answer>[] pending = new CompletableFuture[2];
        transactions.executeWithoutResult(tx -> {
            jdbc.queryForList("select id from app_user where id in (?, ?) for no key update", UUID.class, one, other);
            pending[0] = CompletableFuture.supplyAsync(() -> answer(first.get()), threads);
            awaitAccountWaiters(waitersFirst);
            pending[1] = CompletableFuture.supplyAsync(() -> answer(second.get()), threads);
            awaitAccountWaiters(waitersFirst + waitersSecond);
        });
        return new Answer[] {result(pending[0]), result(pending[1])};
    }

    private void awaitAccountWaiters(int expected) {
        awaitWaitersBehindMe("from app_user ", expected);
    }

    private String status(UUID one, UUID other) {
        BuddyId id = BuddyId.of(one, other);
        var rows = jdbc.queryForList("select status from buddy where user_a = ? and user_b = ?", String.class, id.getUserA(), id.getUserB());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Test void postgresqlOrdersAPairLikeBuddyIdOrderAndNotLikeUuidCompareTo() {
        // UUID.compareTo puts 80000000-... before 7fffffff-... (signed first half); PostgreSQL compares bytes unsigned.
        UUID low = UUID.fromString("7fffffff-ffff-4fff-bfff-ffffffffffff"), high = UUID.fromString("80000000-0000-4000-8000-000000000000");
        assertTrue(high.compareTo(low) < 0);
        BuddyId id = BuddyId.of(high, low);
        assertEquals(low, id.getUserA());
        String removeUsers = "delete from app_user where id in (?, ?)";
        jdbc.update(removeUsers, low, high);
        try {
            for (UUID user : List.of(low, high))
                jdbc.update("insert into app_user (id, email, email_key, password_hash, display_name) values (?, ?, ?, 'hash', 'Order')", user, user + "@example.test", user + "@example.test");
            String insert = "insert into buddy (user_a, user_b, status, requested_by, created_at) values (?, ?, 'PENDING', ?, now())";
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> jdbc.update(insert, id.getUserB(), id.getUserA(), high));
            assertEquals(1, jdbc.update(insert, id.getUserA(), id.getUserB(), high));
        } finally { jdbc.update(removeUsers, low, high); }
    }

    @Test void crossedRequestsAtOnceBecomeOneAcceptedPair() throws Exception {
        for (int round = 0; round < 5; round++) {
            var ada = user("Ada"); var ben = user("Ben");
            UUID adaId = me(ada).id(), benId = me(ben).id();
            String adas = link(ada), bens = link(ben);
            CyclicBarrier barrier = new CyclicBarrier(2);
            var asking = start(barrier, () -> requestByLink(ada, bens));
            var answering = start(barrier, () -> requestByLink(ben, adas));
            var answers = Set.of(result(asking), result(answering));
            assertEquals(Set.of(new Answer(200, Relation.OUTGOING), new Answer(200, Relation.BUDDIES)), answers);
            assertEquals("ACCEPTED", status(adaId, benId));
        }
    }

    @Test void crossedRequestsQueuedOnTheAccountsAcceptInOrder() throws Exception {
        var ada = user("Ada"); var ben = user("Ben");
        UUID adaId = me(ada).id(), benId = me(ben).id();
        String adas = link(ada), bens = link(ben);
        Answer[] answers = queued(adaId, benId, 1, () -> requestByLink(ada, bens), 1, () -> requestByLink(ben, adas));
        assertEquals(new Answer(200, Relation.OUTGOING), answers[0]);
        assertEquals(new Answer(200, Relation.BUDDIES), answers[1]);
        assertEquals("ACCEPTED", status(adaId, benId));
    }

    @Test void twoAcceptsOfOneRequestBothSucceed() throws Exception {
        var ada = user("Ada"); var ben = user("Ben");
        var benElsewhere = guest();
        login(benElsewhere, me(ben).email(), PASSWORD).expectStatus().isOk();
        UUID adaId = me(ada).id(), benId = me(ben).id();
        requestByLink(ada, link(ben)).expectStatus().isOk();
        Answer[] answers = queued(adaId, benId, 1, () -> act(ben, "accept", adaId), 1, () -> act(benElsewhere, "accept", adaId));
        assertEquals(List.of(204, 204), List.of(answers[0].status(), answers[1].status()));
        assertEquals("ACCEPTED", status(adaId, benId));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void acceptAgainstCancelKeepsWhatCameFirst(boolean acceptFirst) throws Exception {
        var ada = user("Ada"); var ben = user("Ben");
        UUID adaId = me(ada).id(), benId = me(ben).id();
        requestByLink(ada, link(ben)).expectStatus().isOk();
        Supplier<ResponseSpec> accept = () -> act(ben, "accept", adaId), cancel = () -> act(ada, "cancel", benId);
        Answer[] answers = acceptFirst ? queued(adaId, benId, 1, accept, 1, cancel) : queued(adaId, benId, 1, cancel, 1, accept);
        if (acceptFirst) {
            assertEquals(List.of(204, 409), List.of(answers[0].status(), answers[1].status()));
            assertEquals("ACCEPTED", status(adaId, benId));
        } else {
            assertEquals(List.of(204, 404), List.of(answers[0].status(), answers[1].status()));
            assertNull(status(adaId, benId));
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void acceptAgainstRemoveNeverLeavesAPairThatWasRemovedAfterItWasAccepted(boolean acceptFirst) throws Exception {
        var ada = user("Ada"); var ben = user("Ben");
        UUID adaId = me(ada).id(), benId = me(ben).id();
        requestByLink(ada, link(ben)).expectStatus().isOk();
        // Ada's stale page offers Remove (she believes they are buddies) while Ben accepts.
        Supplier<ResponseSpec> accept = () -> act(ben, "accept", adaId), remove = () -> act(ada, "remove", benId);
        Answer[] answers = acceptFirst ? queued(adaId, benId, 1, accept, 1, remove) : queued(adaId, benId, 1, remove, 1, accept);
        if (acceptFirst) {
            assertEquals(List.of(204, 204), List.of(answers[0].status(), answers[1].status()));
            assertNull(status(adaId, benId));
        } else {
            assertEquals(List.of(409, 204), List.of(answers[0].status(), answers[1].status()));
            assertEquals("ACCEPTED", status(adaId, benId));
        }
    }

    @Test void aRequestQueuedBehindALinkReplacementIsRefused() throws Exception {
        var ada = user("Ada"); var ben = user("Ben");
        UUID adaId = me(ada).id(), benId = me(ben).id();
        String old = link(ben);
        // Both wait for Ben's row: the replacement first, then the request (whose first lock may be Ada's).
        boolean adaFirst = BuddyId.ORDER.compare(adaId, benId) < 0;
        @SuppressWarnings("unchecked") CompletableFuture<Answer>[] pending = new CompletableFuture[2];
        transactions.executeWithoutResult(tx -> {
            jdbc.queryForObject("select id from app_user where id = ? for no key update", UUID.class, benId);
            pending[0] = CompletableFuture.supplyAsync(() -> answer(ben.post().uri("/api/buddies/link").exchange()), threads);
            awaitAccountWaiters(1);
            pending[1] = CompletableFuture.supplyAsync(() -> answer(requestByLink(ada, old)), threads);
            awaitAccountWaiters(2);
        });
        assertEquals(200, result(pending[0]).status());
        assertEquals(404, result(pending[1]).status(), "Ada first: " + adaFirst);
        assertNull(status(adaId, benId));
        assertEquals(404, answer(preview(ada, old)).status());
    }

    @Test void aPreviewDuringALinkReplacementSeesTheOldOrNoLinkAndThenNone() throws Exception {
        for (int round = 0; round < 5; round++) {
            var ada = user("Ada"); var ben = user("Ben");
            String old = link(ben);
            CyclicBarrier barrier = new CyclicBarrier(2);
            var replacing = CompletableFuture.supplyAsync(() -> {
                try { barrier.await(5, TimeUnit.SECONDS); }
                catch (Exception error) { throw new RuntimeException(error); }
                return ben.post().uri("/api/buddies/link").exchange().expectStatus().isOk()
                        .expectBody(eu.glazelab.api.dto.BuddyDtos.BuddyLinkView.class).returnResult().getResponseBody().token();
            }, threads);
            var previewing = start(barrier, () -> preview(ada, old));
            String replacement = replacing.get(15, TimeUnit.SECONDS);
            int previewed = result(previewing).status();
            assertTrue(previewed == 200 || previewed == 404, "preview " + previewed);
            assertEquals(404, answer(preview(ada, old)).status());
            assertEquals(Relation.NONE, body(preview(ada, replacement).expectStatus().isOk(), BuddyLinkPreviewView.class).relation());
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void accountDeletionAgainstAcceptLeavesNoPair(boolean deletionFirst) throws Exception {
        var ada = user("Ada"); var ben = user("Ben");
        UUID adaId = me(ada).id(), benId = me(ben).id();
        requestByLink(ada, link(ben)).expectStatus().isOk();
        Supplier<ResponseSpec> deletion = () -> ada.post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange();
        Supplier<ResponseSpec> accept = () -> act(ben, "accept", adaId);
        // The deletion waits for Ada's row only, an accept for the smaller ID's row first. Queued after an accept that
        // waits for Ben's row, the deletion may also take Ada's row first; either way no pair remains.
        boolean benFirst = BuddyId.ORDER.compare(benId, adaId) < 0;
        Answer[] answers = deletionFirst ? queued(adaId, benId, 1, deletion, 1, accept) : queued(adaId, benId, 1, accept, 1, deletion);
        Answer deleted = deletionFirst ? answers[0] : answers[1], accepted = deletionFirst ? answers[1] : answers[0];
        assertEquals(204, deleted.status());
        if (deletionFirst || !benFirst) assertEquals(deletionFirst ? 404 : 204, accepted.status(), "Ben first: " + benFirst);
        else assertTrue(accepted.status() == 204 || accepted.status() == 404, "accept " + accepted.status());
        assertNull(status(adaId, benId));
        assertEquals(0, jdbc.queryForObject("select count(*) from buddy where requested_by = ?", Integer.class, adaId));
        assertTrue(BuddyApiTest.buddies(ben).buddies().isEmpty());
        assertTrue(BuddyApiTest.availability(ben).isEmpty());
    }

    @Test void manyBuddyRequestsAtOnceNeitherDeadlockNorDuplicate() throws Exception {
        List<RestTestClient> people = List.of(user("P1"), user("P2"), user("P3"), user("P4"));
        List<String> links = people.stream().map(BuddyApiTest::link).toList();
        CyclicBarrier barrier = new CyclicBarrier(12);
        var calls = new java.util.ArrayList<CompletableFuture<Answer>>();
        for (int asker = 0; asker < 4; asker++)
            for (int owner = 0; owner < 4; owner++)
                if (asker != owner) {
                    var client = people.get(asker); String token = links.get(owner);
                    calls.add(start(barrier, () -> requestByLink(client, token)));
                }
        for (var call : calls) assertEquals(200, result(call).status());
        var ids = people.stream().map(p -> me(p).id()).toList();
        for (int i = 0; i < 4; i++)
            for (int j = i + 1; j < 4; j++) assertEquals("ACCEPTED", status(ids.get(i), ids.get(j)));
    }
}
