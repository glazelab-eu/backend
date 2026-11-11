package eu.glazelab.api.controller;

import eu.glazelab.api.controller.SyncApiTest.From;
import eu.glazelab.api.dto.CombinationDtos.CombinationView;
import eu.glazelab.api.dto.CombinationDtos.NewCombination;
import eu.glazelab.api.dto.CombinationDtos.StepInput;
import eu.glazelab.api.dto.SyncDtos.SyncView;
import eu.glazelab.api.model.StepType;
import eu.glazelab.api.service.CombinationService;
import eu.glazelab.api.support.ApiTestBase;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Commit order against the cursor: a transaction that writes its log row (a lower revision) before a delta and
 * commits after it is still delivered by the next delta through the overlap window. Runs on H2 here and on
 * PostgreSQL in {@link SyncConcurrencyApiTest}.
 */
class SyncOverlapApiTest extends ApiTestBase {
    @Autowired CombinationService service;
    @Autowired TransactionTemplate transactions;

    private static NewCombination titled(String title) {
        return new NewCombination(title, null, List.of(new StepInput(StepType.CUSTOM, null, null, "Firing", null, null)),
                null, null, null, null, null, null, null, null, null, null);
    }

    /** Edits in this thread's transaction and holds its commit after the log insert until {@code release} opens. */
    private CompletableFuture<Void> heldEdit(UUID id, UUID author, String title, CountDownLatch inserted, CountDownLatch release) {
        return CompletableFuture.runAsync(() -> transactions.executeWithoutResult(status -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                // After every beforeCommit (the change log insert), before the database commit.
                @Override public void beforeCompletion() {
                    inserted.countDown();
                    try { assertTrue(release.await(30, TimeUnit.SECONDS)); }
                    catch (InterruptedException error) { throw new IllegalStateException(error); }
                }
            });
            service.update(id, titled(title), author);
        }));
    }

    private long revisionOf(String cursor) {
        return Long.parseLong(cursor.substring(0, cursor.indexOf('.')));
    }

    @Test void aChangeCommittedAfterALaterDeltaComesWithTheNextOne() throws Exception {
        RestTestClient author = user("Late"), other = user("Prompt"), guest = guest();
        UUID id = SyncApiTest.published(author, unique("Late"));
        SyncApiTest.settle(jdbc);
        From from = SyncApiTest.start(guest);
        CountDownLatch inserted = new CountDownLatch(1), release = new CountDownLatch(1);
        var late = heldEdit(id, me(author).id(), "Late title", inserted, release);
        try {
            assertTrue(inserted.await(30, TimeUnit.SECONDS));
            // A later change commits first; the delta's cursor passes the held row's revision.
            UUID prompt = SyncApiTest.published(other, unique("Prompt"));
            SyncView first = SyncApiTest.delta(guest, from);
            assertTrue(SyncApiTest.upserted(first, prompt).isPresent());
            assertTrue(SyncApiTest.upserted(first, id).isEmpty());
            release.countDown();
            late.get(30, TimeUnit.SECONDS);
            Long lateRevision = jdbc.queryForObject("SELECT MAX(revision) FROM change_log WHERE item_id = ?", Long.class, id);
            assertTrue(lateRevision < revisionOf(first.cursor()), "the held row lies below the cursor: only the overlap finds it");
            SyncView second = SyncApiTest.delta(guest, from.after(first));
            assertEquals("Late title", SyncApiTest.upserted(second, id).orElseThrow().title());
        } finally {
            release.countDown();
        }
    }

    /**
     * Writers over HTTP and held in-process transactions (commits delayed after their log insert) while a client
     * syncs again and again and applies each delta: at the end its copy equals the listing.
     */
    @Test void aClientSyncingDuringConcurrentWritesLosesNothing() throws Exception {
        RestTestClient guest = guest();
        List<RestTestClient> writers = new ArrayList<>();
        List<UUID> authors = new ArrayList<>();
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            RestTestClient writer = user("Writer");
            writers.add(writer);
            authors.add(me(writer).id());
            ids.add(SyncApiTest.published(writer, unique("Start")));
        }
        Map<UUID, String> local = new HashMap<>();
        combinations(guest).forEach(view -> local.put(view.id(), view.title()));
        From from = SyncApiTest.start(guest);
        AtomicBoolean writing = new AtomicBoolean(true);
        List<CompletableFuture<Void>> work = new ArrayList<>();
        for (int w = 0; w < writers.size(); w++) {
            int index = w;
            work.add(CompletableFuture.runAsync(() -> {
                for (int round = 0; round < 12; round++) {
                    String title = "W" + index + " r" + round;
                    if (round % 3 == 2) {
                        // Held (the last round too): the commit waits up to 300 ms after the log insert, so later rows overtake it.
                        CountDownLatch inserted = new CountDownLatch(1), release = new CountDownLatch(1);
                        var held = heldEdit(ids.get(index), authors.get(index), title, inserted, release);
                        try {
                            assertTrue(inserted.await(30, TimeUnit.SECONDS));
                            Thread.sleep(ThreadLocalRandom.current().nextInt(50, 300));
                            release.countDown();
                            held.get(30, TimeUnit.SECONDS);
                        } catch (Exception error) { throw new IllegalStateException(error); }
                    } else {
                        writers.get(index).put().uri("/api/combinations/{id}", ids.get(index))
                                .body(Map.of("title", title, "steps", List.of(Map.of("type", "CUSTOM", "label", "Firing"))))
                                .exchange().expectStatus().isNoContent();
                    }
                }
            }));
        }
        CompletableFuture<Void> all = CompletableFuture.allOf(work.toArray(CompletableFuture[]::new))
                .whenComplete((done, error) -> writing.set(false));
        From cursor = from;
        int deltas = 0;
        while (writing.get()) {
            cursor = apply(local, guest, cursor);
            deltas++;
        }
        all.get(60, TimeUnit.SECONDS);
        apply(local, guest, cursor);
        assertTrue(deltas > 3, "the client synced while writing: " + deltas);
        for (CombinationView view : combinations(guest))
            if (ids.contains(view.id())) assertEquals(view.title(), local.get(view.id()), "client copy of " + view.id());
        for (int w = 0; w < ids.size(); w++) assertEquals("W" + w + " r11", local.get(ids.get(w)));
    }

    private static From apply(Map<UUID, String> local, RestTestClient client, From from) {
        SyncView delta = SyncApiTest.delta(client, from);
        delta.combinations().forEach(view -> local.put(view.id(), view.title()));
        delta.removedCombinationIds().forEach(local::remove);
        assertEquals(delta.visibleCombinations(), local.size(), "integrity count");
        return from.after(delta);
    }
}
