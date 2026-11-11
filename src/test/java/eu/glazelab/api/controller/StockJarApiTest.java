package eu.glazelab.api.controller;

import eu.glazelab.api.dto.FeedDtos.FeedType;
import eu.glazelab.api.dto.StockDtos.StockView;
import eu.glazelab.api.model.AppUser;
import eu.glazelab.api.repository.AppUserRepository;
import eu.glazelab.api.security.CurrentUser;
import eu.glazelab.api.service.StockService;
import eu.glazelab.api.support.ApiTestBase;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.test.web.servlet.client.RestTestClient.ResponseSpec;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code POST /api/stock/add} (STOCK-1): "+1 jar" adds to what the server holds, so devices whose shelf is stale never
 * lose another device's or member's jar; the explicit "set amount" ({@code POST /api/stock}) stays last-write-wins.
 * {@code StockConcurrencyApiTest} runs this class on PostgreSQL.
 */
class StockJarApiTest extends ApiTestBase {
    private final ExecutorService threads = Executors.newCachedThreadPool();
    @AfterEach void stopThreads() { threads.shutdownNow(); }

    private static List<StockView> stock(RestTestClient client) {
        return body(client.get().uri("/api/stock").exchange().expectStatus().isOk(),
                new ParameterizedTypeReference<List<StockView>>() {});
    }

    private static Map<String, Object> owner(UUID club) {
        return club == null ? Map.of("ownerType", "personal") : Map.of("ownerType", "club", "clubId", club);
    }

    private static StockView entry(List<StockView> listing, UUID glaze, UUID club) {
        return listing.stream().filter(row -> row.glazeId().equals(glaze) && Objects.equals(row.clubId(), club))
                .findFirst().orElseThrow();
    }

    private static void set(RestTestClient client, UUID glaze, UUID club, int grams, Integer jar) {
        var body = new HashMap<String, Object>(owner(club));
        body.put("glazeId", glaze);
        body.put("quantityG", grams);
        if (jar != null) body.put("jarSizeG", jar);
        client.post().uri("/api/stock").body(body).exchange().expectStatus().isOk();
    }

    static ResponseSpec add(RestTestClient client, UUID glaze, UUID club, int grams, Integer jar) {
        return add(client, glaze, club, grams, jar, null);
    }

    /** An addition; {@code replace} sends {@code replaceJarSize} (null leaves it out, as older clients do). */
    static ResponseSpec add(RestTestClient client, UUID glaze, UUID club, int grams, Integer jar, Boolean replace) {
        var body = new HashMap<String, Object>(owner(club));
        body.put("glazeId", glaze);
        body.put("addG", grams);
        if (jar != null) body.put("jarSizeG", jar);
        if (replace != null) body.put("replaceJarSize", replace);
        return client.post().uri("/api/stock/add").body(body).exchange();
    }

    private Integer jarOf(UUID glaze, UUID club) {
        return club == null
                ? jdbc.queryForObject("SELECT jar_size_g FROM stock WHERE glaze_id = ? AND club_id IS NULL", Integer.class, glaze)
                : jdbc.queryForObject("SELECT jar_size_g FROM stock WHERE glaze_id = ? AND club_id = ?", Integer.class, glaze, club);
    }

    /** What a device sends for "+1 jar" after it read {@code seen}: one jar of the size it shows. */
    private static StockView addJar(RestTestClient client, StockView seen) {
        return body(add(client, seen.glazeId(), seen.clubId(), seen.jarSizeG(), null).expectStatus().isOk(), StockView.class);
    }

    private int amount(UUID glaze, UUID club) {
        return club == null
                ? jdbc.queryForObject("SELECT quantity_g FROM stock WHERE glaze_id = ? AND club_id IS NULL", Integer.class, glaze)
                : jdbc.queryForObject("SELECT quantity_g FROM stock WHERE glaze_id = ? AND club_id = ?", Integer.class, glaze, club);
    }

    @Test void twoMembersAddingAJarFromTheSameReadBothCount() {
        RestTestClient ada = user("jar-ada");
        RestTestClient bea = user("jar-bea");
        UUID club = createClub(ada, unique("Jar club"));
        joinClub(ada, club, bea);
        UUID glaze = addGlaze(ada, unique("Jar"), "Blue", "1");
        set(ada, glaze, club, 250, 250);

        StockView adaSees = entry(stock(ada), glaze, club);
        StockView beaSees = entry(stock(bea), glaze, club);
        assertEquals(500, addJar(ada, adaSees).quantityG());
        StockView answered = addJar(bea, beaSees);
        assertEquals(750, answered.quantityG());
        assertEquals(750, entry(stock(ada), glaze, club).quantityG());
        // The answer is the entry as the caller sees it: Bea neither administers the club nor added the entry.
        assertEquals(new StockView(beaSees.id(), glaze, null, club, 750, 250, beaSees.manufacturer(), "Blue",
                beaSees.clubName(), me(ada).id(), false, false), answered);
        assertTrue(addJar(ada, adaSees).canRemove());
    }

    /** The answer's removal right is the viewer's own: a club admin who did not add the entry may remove it, a plain member not. */
    @Test void theAnswerCarriesTheCallersRemovalRight() {
        RestTestClient ada = user("jar-admin");
        RestTestClient bea = user("jar-adder");
        RestTestClient cleo = user("jar-member");
        UUID club = createClub(ada, unique("Jar rights club"));
        joinClub(ada, club, bea);
        joinClub(ada, club, cleo);
        UUID glaze = addGlaze(bea, unique("Jar"), "Rights", "10");
        assertTrue(body(add(bea, glaze, club, 250, 250).expectStatus().isOk(), StockView.class).canRemove());
        StockView admin = body(add(ada, glaze, club, 250, null).expectStatus().isOk(), StockView.class);
        assertTrue(admin.canRemove());
        assertEquals(me(bea).id(), admin.addedById());
        assertFalse(body(add(cleo, glaze, club, 250, null).expectStatus().isOk(), StockView.class).canRemove());
    }

    @Test void twoSessionsOfOneAccountAddingAJarFromTheSameReadBothCount() {
        String email = email("jar-cleo");
        RestTestClient laptop = signUp(email);
        RestTestClient phone = guest();
        login(phone, email, PASSWORD).expectStatus().isOk();
        UUID glaze = addGlaze(laptop, unique("Jar"), "Green", "2");
        set(laptop, glaze, null, 250, 250);

        StockView laptopSees = entry(stock(laptop), glaze, null);
        StockView phoneSees = entry(stock(phone), glaze, null);
        addJar(laptop, laptopSees);
        addJar(phone, phoneSees);
        assertEquals(750, entry(stock(laptop), glaze, null).quantityG());
    }

    @Test void anAdditionCreatesAMissingEntryAndSetsTheJarSizeOnlyWhenSent() {
        RestTestClient ada = user("jar-new");
        UUID glaze = addGlaze(ada, unique("Jar"), "Ash", "3");
        StockView created = body(add(ada, glaze, null, 250, 250).expectStatus().isOk(), StockView.class);
        assertEquals(250, created.quantityG());
        assertEquals(250, created.jarSizeG());
        assertNull(created.clubId());
        assertTrue(created.canRemove());
        assertEquals(created, entry(stock(ada), glaze, null));
        assertNotNull(jdbc.queryForObject("SELECT created_at FROM stock WHERE id = ?", java.sql.Timestamp.class, created.id()));
        // An absent jar size keeps the stored one; a sent one never replaces it unasked (STOCK-2), only with replaceJarSize.
        assertEquals(500, body(add(ada, glaze, null, 250, null).expectStatus().isOk(), StockView.class).quantityG());
        StockView kept = body(add(ada, glaze, null, 1000, 1000).expectStatus().isOk(), StockView.class);
        assertEquals(1500, kept.quantityG());
        assertEquals(250, kept.jarSizeG());
        assertEquals(250, body(add(ada, glaze, null, 1000, 1000, false).expectStatus().isOk(), StockView.class).jarSizeG());
        StockView bigger = body(add(ada, glaze, null, 1000, 1000, true).expectStatus().isOk(), StockView.class);
        assertEquals(3500, bigger.quantityG());
        assertEquals(1000, bigger.jarSizeG());
        // replaceJarSize without a size changes nothing about the size.
        assertEquals(1000, body(add(ada, glaze, null, 10, null, true).expectStatus().isOk(), StockView.class).jarSizeG());
        // A used up entry gets its jar back.
        set(ada, glaze, null, 0, null);
        assertEquals(1000, body(add(ada, glaze, null, 1000, null).expectStatus().isOk(), StockView.class).quantityG());
        assertEquals(1, stock(ada).size());

        UUID bare = addGlaze(ada, unique("Jar"), "Bare", "4");
        assertNull(body(add(ada, bare, null, 100, null).expectStatus().isOk(), StockView.class).jarSizeG());
        // An entry without a jar size takes the one an addition sends.
        StockView sized = body(add(ada, bare, null, 500, 500).expectStatus().isOk(), StockView.class);
        assertEquals(600, sized.quantityG());
        assertEquals(500, sized.jarSizeG());
    }

    /**
     * STOCK-2: the laptop stored a 1000 g jar; the phone, whose shelf did not hold the glaze yet, sends the one-click add
     * (250 g, jar 250 g). The amount is added and the 1000 g jar stays, on the personal and on a club shelf.
     */
    @Test void aStaleOneClickAddKeepsAnotherDevicesJarSize() {
        String email = email("jar-stale");
        RestTestClient laptop = signUp(email);
        RestTestClient phone = guest();
        login(phone, email, PASSWORD).expectStatus().isOk();
        RestTestClient bea = user("jar-stale-bea");
        UUID club = createClub(laptop, unique("Jar stale club"));
        joinClub(laptop, club, bea);
        UUID glaze = addGlaze(laptop, unique("Jar"), "Stale", "11");
        assertTrue(stock(phone).isEmpty());

        set(laptop, glaze, null, 1000, 1000);
        StockView answered = body(add(phone, glaze, null, 250, 250).expectStatus().isOk(), StockView.class);
        assertEquals(1250, answered.quantityG());
        assertEquals(1000, answered.jarSizeG());
        assertEquals(1000, jarOf(glaze, null));
        assertEquals(1250, amount(glaze, null));

        // A club member's stale one-click add on the club shelf: the same rule.
        add(laptop, glaze, club, 1000, 1000).expectStatus().isOk();
        StockView club250 = body(add(bea, glaze, club, 250, 250).expectStatus().isOk(), StockView.class);
        assertEquals(1250, club250.quantityG());
        assertEquals(1000, club250.jarSizeG());

        // A new entry takes the sent size.
        UUID fresh = addGlaze(laptop, unique("Jar"), "Fresh", "12");
        assertEquals(250, body(add(phone, fresh, null, 250, 250).expectStatus().isOk(), StockView.class).jarSizeG());
        // "+1 jar" on a row another device removed recreates the entry with the size the row showed.
        String removed = entry(stock(laptop), fresh, null).id().toString();
        laptop.delete().uri("/api/stock/" + removed).exchange().expectStatus().isNoContent();
        StockView recreated = body(add(phone, fresh, null, 250, 250).expectStatus().isOk(), StockView.class);
        assertEquals(250, recreated.quantityG());
        assertEquals(250, recreated.jarSizeG());
    }

    /**
     * STOCK-2 review: the jar size rule on a used-up entry, with a size equal to the stored one, on a club shelf both
     * ways, and with the size bounds, which hold for a replacement too.
     */
    @Test void theJarSizeRuleHoldsOnUsedUpAndClubEntries() {
        RestTestClient ada = user("jar-rule-ada");
        RestTestClient bea = user("jar-rule-bea");
        UUID club = createClub(ada, unique("Jar rule club"));
        joinClub(ada, club, bea);
        UUID glaze = addGlaze(ada, unique("Jar"), "Rule", "13");

        // A used-up entry (0 g) still has its jar size: an addition with another size keeps it.
        set(ada, glaze, null, 0, 250);
        StockView restocked = body(add(ada, glaze, null, 1000, 1000).expectStatus().isOk(), StockView.class);
        assertEquals(1000, restocked.quantityG());
        assertEquals(250, restocked.jarSizeG());
        // Asking to replace with the size stored already changes nothing but the amount.
        assertEquals(250, body(add(ada, glaze, null, 250, 250, true).expectStatus().isOk(), StockView.class).jarSizeG());
        // The bounds hold for a replacement: refused, nothing changed.
        add(ada, glaze, null, 250, 0, true).expectStatus().isBadRequest();
        add(ada, glaze, null, 250, 100_001, true).expectStatus().isBadRequest();
        assertEquals(250, jarOf(glaze, null));
        assertEquals(1250, amount(glaze, null));
        assertEquals(100_000, body(add(ada, glaze, null, 1, 100_000, true).expectStatus().isOk(), StockView.class).jarSizeG());
        assertEquals(1, body(add(ada, glaze, null, 1, 1, true).expectStatus().isOk(), StockView.class).jarSizeG());

        // A club entry without a size takes the sent one, also with replaceJarSize false; another member replaces it on purpose.
        add(ada, glaze, club, 100, null).expectStatus().isOk();
        assertNull(jarOf(glaze, club));
        assertEquals(500, body(add(bea, glaze, club, 500, 500, false).expectStatus().isOk(), StockView.class).jarSizeG());
        assertEquals(500, body(add(ada, glaze, club, 1000, 1000, false).expectStatus().isOk(), StockView.class).jarSizeG());
        StockView replaced = body(add(bea, glaze, club, 1000, 1000, true).expectStatus().isOk(), StockView.class);
        assertEquals(2600, replaced.quantityG());
        assertEquals(1000, replaced.jarSizeG());
        // The personal entry of the same glaze is a different entry: untouched by the club's replacement.
        assertEquals(1, jarOf(glaze, null));
    }

    /**
     * A field the API does not know is ignored, not refused: a newer client's {@code replaceJarSize} against an API from
     * before STOCK-2 (during a deploy) is an addition that keeps the stored size, never a 400 that breaks "+1 jar".
     */
    @Test void anAdditionWithAnUnknownFieldIsAccepted() {
        RestTestClient ada = user("jar-unknown");
        UUID glaze = addGlaze(ada, unique("Jar"), "Unknown", "14");
        var body = Map.of("glazeId", glaze, "addG", 250, "jarSizeG", 250, "ownerType", "personal", "aFieldFromTheFuture", true);
        assertEquals(250, body(ada.post().uri("/api/stock/add").body(body).exchange().expectStatus().isOk(), StockView.class).quantityG());
    }

    /**
     * Twelve first additions at once with twelve jar sizes: the first one under the lock creates the entry with its size and
     * every later one keeps it (one of the sent sizes, never a mix); then twelve replacements at once leave one of theirs.
     */
    @Test void concurrentAdditionsWithDifferentSizesKeepOneSize() throws Exception {
        final int devices = 12;
        String email = email("jar-sizes");
        RestTestClient owner = signUp(email);
        List<RestTestClient> sessions = new java.util.ArrayList<>(List.of(owner));
        for (int i = 1; i < devices; i++) {
            RestTestClient session = guest();
            login(session, email, PASSWORD).expectStatus().isOk();
            sessions.add(session);
        }
        UUID glaze = addGlaze(owner, unique("Jar"), "Sizes", "15");
        List<Integer> sizes = IntStream.range(0, devices).mapToObj(i -> 100 * (i + 1)).toList();

        for (boolean replace : new boolean[] {false, true}) {
            CountDownLatch start = new CountDownLatch(1);
            var pending = IntStream.range(0, devices).mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                await(start);
                return body(add(sessions.get(i), glaze, null, 10, sizes.get(i), replace).expectStatus().isOk(), StockView.class);
            }, threads)).toList();
            start.countDown();
            List<StockView> answers = new java.util.ArrayList<>();
            for (var future : pending) answers.add(future.get(60, TimeUnit.SECONDS));
            Integer stored = jarOf(glaze, null);
            assertTrue(sizes.contains(stored), "stored " + stored);
            if (!replace) {
                // Every answer shows the creator's size: nobody after it changed it.
                assertTrue(answers.stream().allMatch(answer -> stored.equals(answer.jarSizeG())), answers.toString());
            }
        }
        assertEquals(2 * devices * 10, amount(glaze, null));
    }

    @Test void settingTheAmountStaysLastWriteWins() {
        RestTestClient ada = user("jar-set");
        UUID glaze = addGlaze(ada, unique("Jar"), "Set", "5");
        add(ada, glaze, null, 250, 250).expectStatus().isOk();
        add(ada, glaze, null, 250, null).expectStatus().isOk();
        set(ada, glaze, null, 100, null);
        assertEquals(100, amount(glaze, null));
    }

    @Test void additionsKeepTheOwnersBoundsAndChecks() {
        RestTestClient ada = user("jar-checks");
        RestTestClient bea = user("jar-outsider");
        UUID club = createClub(ada, unique("Jar club"));
        UUID glaze = addGlaze(ada, unique("Jar"), "Rules", "6");
        for (int bad : new int[] {0, -250, StockService.MAX_QUANTITY_G + 1})
            add(ada, glaze, null, bad, null).expectStatus().isBadRequest();
        add(ada, glaze, null, 250, 0).expectStatus().isBadRequest();
        add(ada, glaze, null, 250, 100_001).expectStatus().isBadRequest();
        add(ada, UUID.randomUUID(), null, 250, null).expectStatus().isBadRequest();
        var noOwner = Map.of("glazeId", glaze, "addG", 250, "ownerType", "club");
        assertError(ada.post().uri("/api/stock/add").body(noOwner).exchange(), 400, "Choose personal or club stock");
        assertError(add(bea, glaze, club, 250, null), 403, "Club membership required");
        assertError(add(bea, glaze, UUID.randomUUID(), 250, null), 403, "Club membership required");
        add(guest(), glaze, null, 250, null).expectStatus().isUnauthorized();
        ada.post().uri("/api/stock/add").header(CSRF_HEADER, "wrong")
                .body(Map.of("glazeId", glaze, "addG", 250, "ownerType", "personal")).exchange().expectStatus().isForbidden();
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM stock WHERE glaze_id = ?", Integer.class, glaze));

        // The total stays within the bound: the addition that would pass it is refused and changes nothing.
        set(ada, glaze, null, StockService.MAX_QUANTITY_G - 100, null);
        assertError(add(ada, glaze, null, 101, null), 400, StockService.AMOUNT_TOO_LARGE);
        assertEquals(StockService.MAX_QUANTITY_G, body(add(ada, glaze, null, 100, null).expectStatus().isOk(), StockView.class).quantityG());
        assertError(add(ada, glaze, null, 1, null), 400, StockService.AMOUNT_TOO_LARGE);
        assertEquals(StockService.MAX_QUANTITY_G, amount(glaze, null));

        // Pending terms: like every other stock write.
        jdbc.update("UPDATE app_user SET accepted_terms_version = NULL, terms_accepted_at = NULL WHERE id = ?", me(ada).id());
        add(ada, glaze, null, 1, null).expectStatus().isEqualTo(428);
    }

    /** BE-11: a set amount has the bound of an addition, so no save stores a total no addition could reach. */
    @Test void aSetAmountHasTheBoundOfAnAddition() {
        RestTestClient ada = user("jar-set-bound");
        UUID glaze = addGlaze(ada, unique("Jar"), "Set bound", "11");
        var tooMuch = Map.of("glazeId", glaze, "quantityG", StockService.MAX_QUANTITY_G + 1, "ownerType", "personal");
        // A field error names its field; the client strips the name to translate the message.
        assertError(ada.post().uri("/api/stock").body(tooMuch).exchange(), 400, "quantityG: " + StockService.AMOUNT_TOO_LARGE);
        assertError(ada.post().uri("/api/stock").body(Map.of("glazeId", glaze, "quantityG", Integer.MAX_VALUE, "ownerType", "personal"))
                .exchange(), 400, "quantityG: " + StockService.AMOUNT_TOO_LARGE);
        var newGlaze = Map.of("newManufacturer", Map.of("name", unique("Jar bound"), "shortId", shortId()), "name", "Bound", "code", "1",
                "stock", Map.of("quantityG", StockService.MAX_QUANTITY_G + 1));
        assertError(ada.post().uri("/api/glazes").body(newGlaze).exchange(), 400, "stock.quantityG: " + StockService.AMOUNT_TOO_LARGE);
        set(ada, glaze, null, StockService.MAX_QUANTITY_G, null);
        assertEquals(StockService.MAX_QUANTITY_G, amount(glaze, null));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM glaze WHERE name = 'Bound' AND code = '1' AND created_by = ?",
                Integer.class, me(ada).id()));
    }

    /**
     * STOCK-3: a save without an amount changes only the jar size, so a device that changed only the size never resets
     * the amount another device saved or added meanwhile; personal and club alike, under the same owner lock.
     */
    @Test void aSizeOnlySaveKeepsTheStoredAmount() {
        String email = email("jar-size-only");
        RestTestClient laptop = signUp(email);
        RestTestClient phone = guest();
        login(phone, email, PASSWORD).expectStatus().isOk();
        RestTestClient bea = user("jar-size-only-bea");
        UUID club = createClub(laptop, unique("Jar size club"));
        joinClub(laptop, club, bea);
        UUID glaze = addGlaze(laptop, unique("Jar"), "Size only", "12");
        for (UUID owner : java.util.Arrays.asList(null, club)) {
            set(laptop, glaze, owner, 250, 250);
            // Another device of the account (personal) or another member (club) adds a jar the laptop has not seen.
            add(owner == null ? phone : bea, glaze, owner, 250, null).expectStatus().isOk();
            sizeOnly(laptop, glaze, owner, 500).expectStatus().isOk();
            assertEquals(500, amount(glaze, owner));
            assertEquals(500, jarOf(glaze, owner));
        }
        // On a missing entry it creates a used-up one with that size.
        UUID fresh = addGlaze(laptop, unique("Jar"), "Size only new", "16");
        sizeOnly(laptop, fresh, null, 1000).expectStatus().isOk();
        StockView created = entry(stock(laptop), fresh, null);
        assertEquals(0, created.quantityG());
        assertEquals(1000, created.jarSizeG());

        // Neither an amount nor a size is nothing to save; the size keeps its bounds.
        assertError(laptop.post().uri("/api/stock").body(Map.of("glazeId", glaze, "ownerType", "personal")).exchange(), 400,
                StockService.NOTHING_TO_SAVE);
        sizeOnly(laptop, glaze, null, 0).expectStatus().isBadRequest();
        sizeOnly(laptop, glaze, null, 100_001).expectStatus().isBadRequest();
        assertError(sizeOnly(user("jar-size-outsider"), glaze, club, 500), 403, "Club membership required");
        assertEquals(500, jarOf(glaze, club));
    }

    private static ResponseSpec sizeOnly(RestTestClient client, UUID glaze, UUID club, int jar) {
        var body = new HashMap<String, Object>(owner(club));
        body.put("glazeId", glaze);
        body.put("jarSizeG", jar);
        return client.post().uri("/api/stock").body(body).exchange();
    }

    private int amount(UUID glaze, UUID club, UUID user) {
        return club != null ? amount(glaze, club)
                : jdbc.queryForObject("SELECT quantity_g FROM stock WHERE glaze_id = ? AND user_id = ?", Integer.class, glaze, user);
    }

    private static ResponseSpec addOnce(RestTestClient client, UUID glaze, UUID club, int grams, UUID request) {
        var body = new HashMap<String, Object>(owner(club));
        body.put("glazeId", glaze);
        body.put("addG", grams);
        body.put("jarSizeG", grams);
        body.put("requestId", request);
        return client.post().uri("/api/stock/add").body(body).exchange();
    }

    /**
     * BE-13: an addition sent again with its request ID (its answer was lost, the person taps again) answers the entry
     * as it is now and adds nothing; another ID, another account, another glaze or no ID add as usual.
     */
    @Test void aRepeatedAdditionWithItsRequestIdCountsOnce() {
        RestTestClient ada = user("jar-repeat");
        RestTestClient bea = user("jar-repeat-bea");
        UUID club = createClub(ada, unique("Jar repeat club"));
        joinClub(ada, club, bea);
        UUID glaze = addGlaze(ada, unique("Jar"), "Repeat", "13");
        UUID other = addGlaze(ada, unique("Jar"), "Repeat other", "14");
        UUID request = UUID.randomUUID();
        assertEquals(250, body(addOnce(ada, glaze, null, 250, request).expectStatus().isOk(), StockView.class).quantityG());
        StockView repeated = body(addOnce(ada, glaze, null, 250, request).expectStatus().isOk(), StockView.class);
        assertEquals(250, repeated.quantityG());
        assertEquals(250, amount(glaze, null, me(ada).id()));
        // What the repeat answers is the entry now: another device's addition in between shows.
        add(ada, glaze, null, 100, null).expectStatus().isOk();
        assertEquals(350, body(addOnce(ada, glaze, null, 250, request).expectStatus().isOk(), StockView.class).quantityG());

        assertEquals(600, body(addOnce(ada, glaze, null, 250, UUID.randomUUID()).expectStatus().isOk(), StockView.class).quantityG());
        assertEquals(250, body(addOnce(bea, glaze, null, 250, request).expectStatus().isOk(), StockView.class).quantityG());
        assertEquals(250, body(addOnce(ada, other, null, 250, request).expectStatus().isOk(), StockView.class).quantityG());
        assertEquals(250, body(addOnce(ada, glaze, club, 250, request).expectStatus().isOk(), StockView.class).quantityG());
        assertEquals(500, body(add(ada, glaze, club, 250, null).expectStatus().isOk(), StockView.class).quantityG());

        // A refused addition is not remembered: once it fits, the same ID adds.
        UUID refused = UUID.randomUUID();
        set(ada, other, null, StockService.MAX_QUANTITY_G, null);
        assertError(addOnce(ada, other, null, 250, refused), 400, StockService.AMOUNT_TOO_LARGE);
        set(ada, other, null, 0, null);
        assertEquals(250, body(addOnce(ada, other, null, 250, refused).expectStatus().isOk(), StockView.class).quantityG());

        // An entry removed since comes back with the jar, as any "+1 jar" on a removed row.
        UUID removedRequest = UUID.randomUUID();
        UUID id = body(addOnce(bea, other, null, 250, removedRequest).expectStatus().isOk(), StockView.class).id();
        set(bea, other, null, 0, null);
        bea.delete().uri("/api/stock/{id}", id).exchange().expectStatus().isNoContent();
        assertEquals(250, body(addOnce(bea, other, null, 250, removedRequest).expectStatus().isOk(), StockView.class).quantityG());
    }

    /** BE-13 under the owner lock: the same addition sent twice at once counts once, whichever arrives first. */
    @Test void concurrentRepeatsOfOneAdditionCountOnce() throws Exception {
        RestTestClient ada = user("jar-repeat-race");
        UUID glaze = addGlaze(ada, unique("Jar"), "Repeat race", "15");
        for (int round = 0; round < 5; round++) {
            UUID request = UUID.randomUUID();
            CountDownLatch start = new CountDownLatch(1);
            var pending = IntStream.range(0, 4).mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                await(start);
                return status(addOnce(ada, glaze, null, 250, request));
            }, threads)).toList();
            start.countDown();
            for (var future : pending) assertEquals(200, future.get(60, TimeUnit.SECONDS));
            assertEquals(250 * (round + 1), amount(glaze, null, me(ada).id()));
        }
    }

    @Autowired StockService stockService;
    @Autowired AppUserRepository users;
    @Autowired PlatformTransactionManager transactionManager;

    /**
     * BE-12: a personal save whose account was deleted (or banned) after the request loaded it answers 401 "Please sign
     * in.", like every other write, never a 500.
     */
    @Test void aPersonalSaveOfAnAccountDeletedMeanwhileIsSignedOut() {
        RestTestClient ada = user("jar-gone");
        AppUser loaded = users.findById(me(ada).id()).orElseThrow();
        ada.post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange().expectStatus().isNoContent();
        var refused = assertThrows(ResponseStatusException.class,
                () -> new TransactionTemplate(transactionManager).execute(status -> stockService.lockOwner(loaded, null)));
        assertEquals(HttpStatus.UNAUTHORIZED, refused.getStatusCode());
        assertEquals(CurrentUser.SIGN_IN_REQUIRED, refused.getReason());
    }

    @Test void onlyTheAdditionThatCreatesAClubEntryIsNews() {
        RestTestClient ada = user("jar-feed-ada");
        RestTestClient bea = user("jar-feed-bea");
        UUID club = createClub(ada, unique("Jar feed club"));
        joinClub(ada, club, bea);
        UUID glaze = addGlaze(ada, unique("Jar"), "Feed", "7");
        add(ada, glaze, club, 250, 250).expectStatus().isOk();
        add(bea, glaze, club, 250, null).expectStatus().isOk();
        add(ada, glaze, club, 250, null).expectStatus().isOk();
        assertEquals(List.of(new FeedApiTest.Seen(FeedType.CLUB_GLAZE, glaze)), FeedApiTest.seen(bea).stream()
                .filter(seen -> seen.type() == FeedType.CLUB_GLAZE).toList());
        assertTrue(FeedApiTest.seen(ada).stream().noneMatch(seen -> seen.type() == FeedType.CLUB_GLAZE));
        assertEquals(me(ada).id(), entry(stock(bea), glaze, club).addedById());
    }

    /** Twelve sessions add a jar at once (on PostgreSQL: real lock queues), on the personal and on a club shelf. */
    @Test void concurrentAdditionsAllCount() throws Exception {
        final int devices = 12;
        String email = email("jar-many");
        RestTestClient owner = signUp(email);
        UUID club = createClub(owner, unique("Jar crowd"));
        List<RestTestClient> sessions = new java.util.ArrayList<>(List.of(owner));
        List<RestTestClient> members = new java.util.ArrayList<>(List.of(owner));
        for (int i = 1; i < devices; i++) {
            RestTestClient session = guest();
            login(session, email, PASSWORD).expectStatus().isOk();
            sessions.add(session);
            RestTestClient member = user("jar-crowd-" + i);
            joinClub(owner, club, member);
            members.add(member);
        }
        UUID glaze = addGlaze(owner, unique("Jar"), "Crowd", "8");
        set(owner, glaze, null, 250, 250);
        set(owner, glaze, club, 250, 250);

        CountDownLatch start = new CountDownLatch(1);
        var pending = IntStream.range(0, devices).boxed().flatMap(i -> java.util.stream.Stream.of(
                CompletableFuture.supplyAsync(() -> { await(start); return status(add(sessions.get(i), glaze, null, 250, null)); }, threads),
                CompletableFuture.supplyAsync(() -> { await(start); return status(add(members.get(i), glaze, club, 250, null)); }, threads)))
                .toList();
        start.countDown();
        for (var future : pending) assertEquals(200, future.get(60, TimeUnit.SECONDS));
        assertEquals(250 + devices * 250, amount(glaze, null));
        assertEquals(250 + devices * 250, amount(glaze, club));
    }

    /**
     * Twelve first additions at once on shelves without the glaze: the owner lock makes one create the entry and the others
     * add to it, never a second row (409 on the unique key) or a lost jar.
     */
    @Test void concurrentFirstAdditionsCreateOneEntryAndAllCount() throws Exception {
        final int devices = 12;
        String email = email("jar-first");
        RestTestClient owner = signUp(email);
        UUID club = createClub(owner, unique("Jar first"));
        List<RestTestClient> sessions = new java.util.ArrayList<>(List.of(owner));
        List<RestTestClient> members = new java.util.ArrayList<>(List.of(owner));
        for (int i = 1; i < devices; i++) {
            RestTestClient session = guest();
            login(session, email, PASSWORD).expectStatus().isOk();
            sessions.add(session);
            RestTestClient member = user("jar-first-" + i);
            joinClub(owner, club, member);
            members.add(member);
        }
        UUID glaze = addGlaze(owner, unique("Jar"), "First", "9");

        CountDownLatch start = new CountDownLatch(1);
        var pending = IntStream.range(0, devices).boxed().flatMap(i -> java.util.stream.Stream.of(
                CompletableFuture.supplyAsync(() -> { await(start); return status(add(sessions.get(i), glaze, null, 250, 250)); }, threads),
                CompletableFuture.supplyAsync(() -> { await(start); return status(add(members.get(i), glaze, club, 250, 250)); }, threads)))
                .toList();
        start.countDown();
        for (var future : pending) assertEquals(200, future.get(60, TimeUnit.SECONDS));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM stock WHERE glaze_id = ?", Integer.class, glaze));
        assertEquals(devices * 250, amount(glaze, null));
        assertEquals(devices * 250, amount(glaze, club));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static int status(ResponseSpec response) {
        return response.expectBody(String.class).returnResult().getStatus().value();
    }
}
