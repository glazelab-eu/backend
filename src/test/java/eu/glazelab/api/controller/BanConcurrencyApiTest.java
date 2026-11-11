package eu.glazelab.api.controller;

import eu.glazelab.api.service.ClubService;
import eu.glazelab.api.service.EmailBlocklist;
import eu.glazelab.api.support.ApiTestBase;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.test.web.servlet.client.RestTestClient.ResponseSpec;
import org.springframework.transaction.support.TransactionTemplate;

import static eu.glazelab.api.controller.BanApiTest.ban;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Bans on real PostgreSQL lock queues (backend guide "Bans": both account rows in {@code BuddyId.ORDER}, then the
 * deletion's order): against the account's own deletion and sign-in, a registration of its address, club changes of
 * the member who inherits a club, a second ban and a buddy request between admin and account. Opt in against a
 * disposable database only, like {@code BuddyConcurrencyApiTest}.
 */
@EnabledIfEnvironmentVariable(named = "GLAZELAB_TEST_POSTGRES_URL", matches = ".+")
class BanConcurrencyApiTest extends ApiTestBase {
    private static final Path OBJECTS = Path.of("target", "ban-concurrency-" + UUID.randomUUID());

    @DynamicPropertySource static void postgres(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("GLAZELAB_TEST_POSTGRES_URL"));
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.datasource.username", () -> System.getenv().getOrDefault("GLAZELAB_TEST_POSTGRES_USER", "postgres"));
        properties.add("spring.datasource.password", () -> System.getenv().getOrDefault("GLAZELAB_TEST_POSTGRES_PASSWORD", ""));
        properties.add("glazelab.images.path", OBJECTS::toString);
    }
    @Autowired TransactionTemplate transactions;
    @Autowired EmailBlocklist blocklist;

    private final ExecutorService threads = Executors.newCachedThreadPool();
    @AfterEach void stopThreads() { threads.shutdownNow(); }

    private static int status(ResponseSpec response) {
        return response.expectBody(String.class).returnResult().getStatus().value();
    }

    private static int result(CompletableFuture<Integer> future) throws Exception { return future.get(20, TimeUnit.SECONDS); }

    /**
     * Holds {@code table}'s row {@code id} while {@code first}, then {@code second}, queue for it, then lets them run in
     * that order (PostgreSQL grants a row lock to its waiters in order).
     */
    private int[] queued(String table, UUID id, Supplier<ResponseSpec> first, Supplier<ResponseSpec> second) throws Exception {
        @SuppressWarnings("unchecked") CompletableFuture<Integer>[] pending = new CompletableFuture[2];
        transactions.executeWithoutResult(tx -> {
            jdbc.queryForList("select id from " + table + " where id = ? for no key update", UUID.class, id);
            pending[0] = CompletableFuture.supplyAsync(() -> status(first.get()), threads);
            awaitWaiters(table, 1);
            pending[1] = CompletableFuture.supplyAsync(() -> status(second.get()), threads);
            awaitWaiters(table, 2);
        });
        return new int[] {result(pending[0]), result(pending[1])};
    }

    private void awaitWaiters(String table, int expected) {
        awaitWaitersBehindMe("from " + table + " ", expected);
    }

    /** Runs the calls at once, each on its own thread behind one barrier, and answers their statuses. */
    @SafeVarargs
    private List<Integer> together(Supplier<Integer>... calls) throws Exception {
        var barrier = new CyclicBarrier(calls.length);
        var futures = new ArrayList<CompletableFuture<Integer>>();
        for (var call : calls)
            futures.add(CompletableFuture.supplyAsync(() -> {
                try { barrier.await(5, TimeUnit.SECONDS); return call.get(); }
                catch (Exception error) { throw new RuntimeException(error); }
            }, threads));
        var statuses = new ArrayList<Integer>();
        for (var future : futures) statuses.add(result(future));
        return statuses;
    }

    private int count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Integer.class, arguments);
    }

    /** Entries of the address (at most one: the unique hash; an entry names nobody, 34-7). */
    private int blocksOf(String email) {
        return blocklist.blocked(email) ? 1 : 0;
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void aBanAndTheAccountsOwnDeletionQueueOnTheAccountInEitherOrder(boolean banFirst) throws Exception {
        var admin = siteAdmin();
        String name = unique("Leaving");
        String email = email("Leaving");
        var target = signUp(email);
        jdbc.update("update app_user set display_name = ? where email = ?", name, email);
        UUID id = me(target).id();
        Supplier<ResponseSpec> banning = () -> ban(admin, id, "Spam", null);
        Supplier<ResponseSpec> deleting = () -> target.post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange();
        int[] statuses = queued("app_user", id, banFirst ? banning : deleting, banFirst ? deleting : banning);
        assertEquals(0, count("select count(*) from app_user where id = ?", id));
        if (banFirst) {
            assertArrayEquals(new int[] {204, 401}, statuses);
            assertEquals(1, blocksOf(email));
        } else {
            assertArrayEquals(new int[] {204, 404}, statuses);
            assertEquals(0, blocksOf(email));
        }
    }

    @Test void aSignInRacingTheBanLeavesNoSession() throws Exception {
        var admin = siteAdmin();
        for (int round = 0; round < 4; round++) {
            String email = email("SigningIn");
            signUp(email);
            UUID id = jdbc.queryForObject("select id from app_user where email = ?", UUID.class, email);
            var browser = guest();
            List<Integer> statuses = together(() -> status(ban(admin, id, "Spam", null)), () -> status(login(browser, email, PASSWORD)));
            assertEquals(204, statuses.get(0));
            // A sign-in whose session insert meets the removal answers like a wrong password (34-3), never a 500, and
            // no session outlives the ban.
            assertTrue(List.of(200, 401).contains(statuses.get(1)), statuses.toString());
            browser.get().uri("/api/auth/me").exchange().expectStatus().isUnauthorized();
            assertEquals(0, count("select count(*) from login_session where user_id = ?", id));
            assertEquals(0, count("select count(*) from app_user where id = ?", id));
        }
    }

    /**
     * 34-3, deterministic: the password check has passed and the session insert waits for the account row, which a ban
     * or an account deletion is removing (here an uncommitted delete holds it); once that commits, the insert fails its
     * foreign key. The answer is the wrong-password answer (status, body, no session cookie), not a 500.
     */
    @Test void aSignInWhoseAccountGoesBeforeItsSessionInsertAnswersLikeAWrongPassword() throws Exception {
        String email = email("Vanishing");
        signUp(email);
        UUID id = jdbc.queryForObject("select id from app_user where email = ?", UUID.class, email);
        var wrong = login(guest(), email, "wrong-password-1").expectBody(String.class).returnResult();
        var browser = guest();
        CompletableFuture<EntityExchangeResult<String>> signIn = new CompletableFuture<>();
        transactions.executeWithoutResult(tx -> {
            jdbc.update("delete from app_user where id = ?", id);
            signIn.completeAsync(() -> login(browser, email, PASSWORD).expectBody(String.class).returnResult(), threads);
            awaitWaitersBehindMe("insert into login_session", 1);
        });
        var raced = signIn.get(20, TimeUnit.SECONDS);
        assertEquals(401, raced.getStatus().value());
        assertEquals(wrong.getStatus(), raced.getStatus());
        assertEquals(wrong.getResponseBody(), raced.getResponseBody());
        assertTrue(raced.getResponseHeaders().getOrEmpty("Set-Cookie").stream().noneMatch(cookie -> cookie.startsWith("GLAZELAB_SESSION")),
                raced.getResponseHeaders().toString());
        browser.get().uri("/api/auth/me").exchange().expectStatus().isUnauthorized();
        assertEquals(0, count("select count(*) from login_session where user_id = ?", id));
    }

    @Test void aRegistrationRacingTheBanNeverYieldsAnAccountForTheAddress() throws Exception {
        var admin = siteAdmin();
        for (int round = 0; round < 4; round++) {
            String email = email("Racing");
            signUp(email);
            UUID id = jdbc.queryForObject("select id from app_user where email = ?", UUID.class, email);
            String alias = email.replace("@", "+again@");
            var registrations = new ArrayList<RestTestClient>(List.of(guest(), guest()));
            var answers = new ArrayList<String>();
            List<Integer> statuses = together(() -> status(ban(admin, id, "Spam", null)),
                    () -> record(answers, register(registrations.get(0), email, "Racer")),
                    () -> record(answers, register(registrations.get(1), alias, "Racer")));
            assertEquals(List.of(204, 200, 200), statuses);
            // A registration that looked before the ban committed may have created a pending account: it is dead.
            for (String answer : answers) {
                String token = answer.replaceAll(".*\"setupToken\":\"?([^\",}]*)\"?.*", "$1");
                if (!token.equals("null")) activate(guest(), token, PASSWORD, "Racer").expectStatus().isBadRequest();
            }
            assertEquals(0, count("select count(*) from app_user where id = ?", id));
            assertEquals(0, count("select count(*) from app_user where email in (?, ?) and email_verified", email, alias));
        }
    }

    /** Keeps the answer's body and answers its status. */
    private static int record(List<String> answers, ResponseSpec response) {
        var result = response.expectBody(String.class).returnResult();
        synchronized (answers) {
            answers.add(result.getResponseBody());
        }
        return result.getStatus().value();
    }

    /** The banned account administers a club whose one other member leaves at the same time. */
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void aBanAndTheHeirsLeaveQueueOnTheClubInEitherOrder(boolean banFirst) throws Exception {
        var admin = siteAdmin();
        var target = user("ClubBanned");
        var heir = user("Heir");
        UUID heirId = me(heir).id();
        UUID club = createClub(target, unique("Contested"));
        joinClub(target, club, heir);
        UUID targetId = me(target).id();
        Supplier<ResponseSpec> banning = () -> ban(admin, targetId, "Spam", null);
        Supplier<ResponseSpec> leaving = () -> heir.delete().uri("/api/clubs/{id}/members/{user}", club, heirId).exchange();
        int[] statuses = queued("club", club, banFirst ? banning : leaving, banFirst ? leaving : banning);
        if (banFirst) {
            // The heir became the club's admin and, its only member now, cannot leave it.
            assertArrayEquals(new int[] {204, 409}, statuses);
            assertEquals("ADMIN", jdbc.queryForObject("select role from membership where club_id = ? and user_id = ?", String.class, club, heirId));
            assertError(heir.delete().uri("/api/clubs/{id}/members/{user}", club, heirId).exchange(), 409, ClubService.LAST_ADMIN);
        } else {
            // The heir left first: the club had only the banned account and went with it.
            assertArrayEquals(new int[] {204, 204}, statuses);
            assertEquals(0, count("select count(*) from club where id = ?", club));
        }
        assertEquals(0, count("select count(*) from app_user where id = ?", targetId));
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void aBanAndTheHeirsAccountDeletionQueueOnTheClubInEitherOrder(boolean banFirst) throws Exception {
        var admin = siteAdmin();
        var target = user("ClubBanned");
        var heir = user("Heir");
        UUID heirId = me(heir).id();
        UUID club = createClub(target, unique("Contested"));
        joinClub(target, club, heir);
        UUID targetId = me(target).id();
        Supplier<ResponseSpec> banning = () -> ban(admin, targetId, "Spam", null);
        Supplier<ResponseSpec> deleting = () -> heir.post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange();
        int[] statuses = queued("club", club, banFirst ? banning : deleting, banFirst ? deleting : banning);
        // Either way both accounts are gone and the club, which the last of them held alone, too.
        assertArrayEquals(new int[] {204, 204}, statuses);
        assertEquals(0, count("select count(*) from app_user where id in (?, ?)", targetId, heirId));
        assertEquals(0, count("select count(*) from club where id = ?", club));
    }

    @Test void twoAdminsBanningTheSameAccountBanItOnce() throws Exception {
        var first = siteAdmin();
        var second = siteAdmin();
        for (int round = 0; round < 3; round++) {
            String name = unique("Twice");
            String email = email("Twice");
            signUp(email);
            jdbc.update("update app_user set display_name = ? where email = ?", name, email);
            UUID id = jdbc.queryForObject("select id from app_user where email = ?", UUID.class, email);
            List<Integer> statuses = together(() -> status(ban(first, id, "Spam", null)), () -> status(ban(second, id, "Spam", null)));
            statuses.sort(Integer::compareTo);
            assertEquals(List.of(204, 404), statuses);
            assertEquals(1, blocksOf(email));
            assertEquals(1, count("select count(*) from admin_action where action = 'BAN' and target_user_id = ?", id));
        }
    }

    /** A buddy request between the banning admin and the account locks the same two rows in the same order. */
    @Test void aBuddyRequestBetweenAdminAndAccountNeverDeadlocksWithTheBan() throws Exception {
        var admin = siteAdmin();
        UUID adminId = me(admin).id();
        for (int round = 0; round < 4; round++) {
            var target = user("Asking");
            UUID id = me(target).id();
            String link = BuddyApiTest.link(admin);
            List<Integer> statuses = together(() -> status(ban(admin, id, "Spam", null)), () -> status(BuddyApiTest.requestByLink(target, link)));
            assertEquals(204, statuses.get(0));
            assertTrue(List.of(200, 401, 404).contains(statuses.get(1)), statuses.toString());
            assertEquals(0, count("select count(*) from buddy where user_a = ? or user_b = ?", id, id));
            assertEquals(0, count("select count(*) from app_user where id = ?", id));
            assertTrue(count("select count(*) from app_user where id = ?", adminId) == 1);
        }
    }
}
