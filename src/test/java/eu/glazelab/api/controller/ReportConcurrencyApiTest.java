package eu.glazelab.api.controller;

import eu.glazelab.api.support.ApiTestBase;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient.ResponseSpec;
import org.springframework.transaction.support.TransactionTemplate;

import static eu.glazelab.api.controller.ReportApiTest.batch;
import static eu.glazelab.api.controller.ReportApiTest.choose;
import static eu.glazelab.api.controller.ReportApiTest.event;
import static eu.glazelab.api.controller.ReportApiTest.optIn;
import static eu.glazelab.api.controller.ReportApiTest.send;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Error reports on real PostgreSQL lock queues (backend guide "Error reports"): a send decides the opt-in under the
 * account row lock, so an opt-out or an account deletion queued before or after it leaves no report. Opt in against a
 * disposable database only, like {@code BanConcurrencyApiTest}.
 */
@EnabledIfEnvironmentVariable(named = "GLAZELAB_TEST_POSTGRES_URL", matches = ".+")
class ReportConcurrencyApiTest extends ApiTestBase {
    @DynamicPropertySource static void postgres(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("GLAZELAB_TEST_POSTGRES_URL"));
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.datasource.username", () -> System.getenv().getOrDefault("GLAZELAB_TEST_POSTGRES_USER", "postgres"));
        properties.add("spring.datasource.password", () -> System.getenv().getOrDefault("GLAZELAB_TEST_POSTGRES_PASSWORD", ""));
    }
    @Autowired TransactionTemplate transactions;

    private final ExecutorService threads = Executors.newCachedThreadPool();
    @AfterEach void stopThreads() { threads.shutdownNow(); }

    private static int status(ResponseSpec response) {
        return response.expectBody(String.class).returnResult().getStatus().value();
    }

    /** Holds the account row while {@code first}, then {@code second}, queue for it, then lets them run in that order. */
    private int[] queued(UUID account, Supplier<ResponseSpec> first, Supplier<ResponseSpec> second) throws Exception {
        @SuppressWarnings("unchecked") CompletableFuture<Integer>[] pending = new CompletableFuture[2];
        transactions.executeWithoutResult(tx -> {
            jdbc.queryForList("select id from app_user where id = ? for no key update", UUID.class, account);
            pending[0] = CompletableFuture.supplyAsync(() -> status(first.get()), threads);
            awaitWaiters(1);
            pending[1] = CompletableFuture.supplyAsync(() -> status(second.get()), threads);
            awaitWaiters(2);
        });
        return new int[] {pending[0].get(20, TimeUnit.SECONDS), pending[1].get(20, TimeUnit.SECONDS)};
    }

    private void awaitWaiters(int expected) {
        awaitWaitersBehindMe("from app_user ", expected);
    }

    private int reportsOf(UUID account) {
        return jdbc.queryForObject("select count(*) from client_report where user_id = ?", Integer.class, account);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void anOptOutRacingASendLeavesNoReport(boolean optOutFirst) throws Exception {
        var user = user("Racing");
        UUID id = me(user).id();
        optIn(user);
        Supplier<ResponseSpec> sending = () -> send(user, batch(List.of(event("Racing"))));
        Supplier<ResponseSpec> optingOut = () -> choose(user, false);
        int[] statuses = queued(id, optOutFirst ? optingOut : sending, optOutFirst ? sending : optingOut);
        assertArrayEquals(optOutFirst ? new int[] {204, 409} : new int[] {204, 204}, statuses);
        assertEquals(0, reportsOf(id));
        assertFalse(me(user).diagnosticsOptIn());
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void anAccountDeletionRacingASendLeavesNoReport(boolean deletionFirst) throws Exception {
        var user = user("Deleting");
        UUID id = me(user).id();
        optIn(user);
        Supplier<ResponseSpec> sending = () -> send(user, batch(List.of(event("Deleting"))));
        Supplier<ResponseSpec> deleting = () -> user.post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange();
        int[] statuses = queued(id, deletionFirst ? deleting : sending, deletionFirst ? sending : deleting);
        assertArrayEquals(deletionFirst ? new int[] {204, 401} : new int[] {204, 204}, statuses);
        assertEquals(0, reportsOf(id));
        assertEquals(0, jdbc.queryForObject("select count(*) from app_user where id = ?", Integer.class, id));
    }
}
