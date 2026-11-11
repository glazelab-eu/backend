package eu.glazelab.api.controller;

import eu.glazelab.api.dto.AvatarDtos.AvatarReportPageView;
import eu.glazelab.api.dto.AvatarDtos.AvatarReportView;
import eu.glazelab.api.dto.BanDtos.BanPageView;
import eu.glazelab.api.dto.BanDtos.BanView;
import eu.glazelab.api.exception.ApiErrorHandler;
import eu.glazelab.api.model.AccountToken;
import eu.glazelab.api.model.AvatarPictureState;
import eu.glazelab.api.model.AvatarReportStatus;
import eu.glazelab.api.security.CurrentUser;
import eu.glazelab.api.service.AccountDeletionService;
import eu.glazelab.api.service.AccountService;
import eu.glazelab.api.service.AdminLog;
import eu.glazelab.api.service.AvatarService;
import eu.glazelab.api.service.EmailBlocklist;
import eu.glazelab.api.support.ApiTestBase;
import eu.glazelab.api.support.TestImages;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.test.web.servlet.client.RestTestClient.ResponseSpec;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Account bans (backend guide "Bans"): site admins only, removal exactly like an account deletion, the address blocked
 * from registering again in every written form of the same mailbox, the club last-admin rule never blocking a ban, the
 * audit log, and the reported profile pictures blocked on request. Registration with mail is in
 * {@code PendingAccountApiTest}, lock queues on PostgreSQL in {@code BanConcurrencyApiTest}.
 */
class BanApiTest extends ApiTestBase {
    private static final String REASON = "Repeated harassment in club chats";
    @Autowired TransactionTemplate transactions;
    @Autowired AccountDeletionService deletion;
    @Autowired AdminLog adminLog;
    @Autowired EmailBlocklist blocklist;

    static ResponseSpec ban(RestTestClient admin, UUID target, String reason, Boolean blockPictures) {
        var body = new HashMap<String, Object>();
        if (reason != null) body.put("reason", reason);
        if (blockPictures != null) body.put("blockPictures", blockPictures);
        return admin.post().uri("/api/admin/users/{id}/ban", target).body(body).exchange();
    }

    static void banned(RestTestClient admin, UUID target) {
        ban(admin, target, REASON, null).expectStatus().isNoContent();
    }

    static BanPageView bans(RestTestClient admin, int page) {
        return body(admin.get().uri("/api/admin/bans?page=" + page).exchange().expectStatus().isOk(), BanPageView.class);
    }

    /** The list entries of the ban of {@code target} (an entry names nobody: the admin log links it to the account). */
    private List<BanView> entriesOf(RestTestClient admin, UUID target) {
        UUID entry = jdbc.queryForObject("SELECT target_id FROM admin_action WHERE action = 'BAN' AND target_user_id = ?"
                + " ORDER BY created_at LIMIT 1", UUID.class, target);
        return bans(admin, 0).entries().stream().filter(row -> row.id().equals(entry)).toList();
    }

    private List<Map<String, Object>> actions(String action, UUID target) {
        return jdbc.queryForList("SELECT actor_id, target_user_id, target_id, reason FROM admin_action WHERE action = ?"
                + " AND (target_user_id = ? OR target_id = ?) ORDER BY created_at", action, target, target);
    }

    private boolean accountExists(String email) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE email = ?", Integer.class, email) > 0;
    }

    /** A mail-free registration: the setup token for a new account, null for an address in use or banned. */
    private String registers(String email) {
        return setupToken(register(guest(), email, "Prober"));
    }

    @Test void onlySiteAdminsBanListAndUnbanAndNeverThemselvesOrAnotherSiteAdmin() {
        String email = email("Target");
        var target = signUp(email);
        UUID targetId = me(target).id();
        var user = user("Plain");
        var admin = siteAdmin();
        UUID adminId = me(admin).id();
        var otherAdmin = siteAdmin();

        // Guests 401 and other accounts 403 before anything about the target is read (an unknown ID answers alike).
        for (UUID id : List.of(targetId, UUID.randomUUID())) {
            assertError(ban(guest(), id, REASON, null), 401, CurrentUser.SIGN_IN_REQUIRED);
            assertError(ban(user, id, REASON, null), 403, CurrentUser.SITE_ADMIN_REQUIRED);
            assertError(guest().delete().uri("/api/admin/bans/{id}", id).exchange(), 401, CurrentUser.SIGN_IN_REQUIRED);
            assertError(user.delete().uri("/api/admin/bans/{id}", id).exchange(), 403, CurrentUser.SITE_ADMIN_REQUIRED);
        }
        assertError(ban(target, targetId, REASON, null), 403, CurrentUser.SITE_ADMIN_REQUIRED);
        assertError(guest().get().uri("/api/admin/bans").exchange(), 401, CurrentUser.SIGN_IN_REQUIRED);
        assertError(user.get().uri("/api/admin/bans").exchange(), 403, CurrentUser.SITE_ADMIN_REQUIRED);
        // Self and any site admin (so also the last one) are refused; an unknown account is 404.
        assertError(ban(admin, adminId, REASON, null), 409, AccountDeletionService.SELF_BAN);
        assertError(ban(admin, me(otherAdmin).id(), REASON, true), 409, AccountDeletionService.ADMIN_BAN);
        assertError(ban(otherAdmin, adminId, REASON, null), 409, AccountDeletionService.ADMIN_BAN);
        assertError(ban(admin, UUID.randomUUID(), REASON, null), 404, AccountDeletionService.ACCOUNT_NOT_FOUND);
        assertError(admin.delete().uri("/api/admin/bans/{id}", UUID.randomUUID()).exchange(), 404, EmailBlocklist.BAN_NOT_FOUND);
        // The reason is required (stripped, at most 500); IDs and pages are validated.
        for (String reason : new String[] {null, "", "   ", "x".repeat(501)})
            ban(admin, targetId, reason, null).expectStatus().isBadRequest();
        admin.post().uri("/api/admin/users/not-an-id/ban").body(Map.of("reason", REASON)).exchange().expectStatus().isBadRequest();
        admin.get().uri("/api/admin/bans?page=-1").exchange().expectStatus().isBadRequest();
        // CSRF and the current terms, like every admin write.
        admin.post().uri("/api/admin/users/{id}/ban", targetId).header(CSRF_HEADER, "forged").body(Map.of("reason", REASON)).exchange()
                .expectStatus().isForbidden();
        admin.delete().uri("/api/admin/bans/{id}", UUID.randomUUID()).header(CSRF_HEADER, "forged").exchange().expectStatus().isForbidden();
        jdbc.update("UPDATE app_user SET accepted_terms_version = NULL, terms_accepted_at = NULL WHERE id = ?", adminId);
        ban(admin, targetId, REASON, null).expectStatus().isEqualTo(428);
        jdbc.update("UPDATE app_user SET accepted_terms_version = ?, terms_accepted_at = CURRENT_TIMESTAMP WHERE id = ?",
                AccountService.TERMS_VERSION, adminId);
        // A site admin whose flag was taken away (SQL) is a plain account from the next request on.
        jdbc.update("UPDATE app_user SET site_admin = FALSE WHERE id = ?", me(otherAdmin).id());
        assertError(ban(otherAdmin, targetId, REASON, null), 403, CurrentUser.SITE_ADMIN_REQUIRED);
        assertTrue(accountExists(email));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM admin_action WHERE target_user_id = ?", Integer.class, targetId));

        ban(admin, targetId, "  " + REASON + "  ", false).expectStatus().isNoContent();
        assertFalse(accountExists(email));
        assertError(ban(admin, targetId, REASON, null), 404, AccountDeletionService.ACCOUNT_NOT_FOUND);
        target.get().uri("/api/auth/me").exchange().expectStatus().isUnauthorized();
        assertEquals(1, actions("BAN", targetId).size());
        assertEquals(REASON, actions("BAN", targetId).getFirst().get("reason"));
        assertEquals(adminId, actions("BAN", targetId).getFirst().get("actor_id"));
    }

    /**
     * A plain account's ban request is refused before any lock: it never waits for (or holds) the target's account row,
     * so it can neither stall the target's writes nor time an ID. The service re-reads the actor's flag under its lock.
     */
    @Test void aRefusedBanTakesNoLockAndTheServiceChecksTheRoleAgain() throws Exception {
        var target = user("Locked");
        UUID targetId = me(target).id();
        var user = user("Prober");
        UUID userId = me(user).id();
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var holder = CompletableFuture.runAsync(() -> transactions.executeWithoutResult(tx -> {
            jdbc.queryForList("SELECT id FROM app_user WHERE id = ? FOR UPDATE", UUID.class, targetId);
            held.countDown();
            try { assertTrue(release.await(30, TimeUnit.SECONDS)); }
            catch (InterruptedException error) { throw new IllegalStateException(error); }
        }));
        try {
            assertTrue(held.await(10, TimeUnit.SECONDS));
            var answer = CompletableFuture.supplyAsync(() -> statusAndBody(ban(user, targetId, REASON, null)));
            assertTrue(answer.get(5, TimeUnit.SECONDS).startsWith("403 "));
        } finally {
            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
        }
        var refused = assertThrows(ResponseStatusException.class,
                () -> deletion.ban(userId, targetId, REASON, false));
        assertEquals(403, refused.getStatusCode().value());
        assertTrue(accountExists(me(target).email()));
    }

    /** Everything a fixture account has, made the same way for a deleted and a banned account. */
    private record Fixture(RestTestClient client, RestTestClient secondSession, String email, UUID id, UUID glaze,
            UUID combination, UUID draft, UUID publicPhoto, UUID privatePhoto, UUID clubPhoto, UUID soleClub,
            UUID sharedClub, UUID peerGlaze, UUID avatar, String resetToken) {}

    private Fixture fixture(String name, RestTestClient peer) throws Exception {
        String email = email(name);
        var client = signUp(email);
        UUID id = me(client).id();
        var secondSession = guest();
        login(secondSession, email, PASSWORD).expectStatus().isOk();
        BuddyApiTest.befriend(client, peer);
        BuddyApiTest.link(client);
        UUID glaze = addGlaze(client, unique("Fixture"), "Blue", "1");
        UUID combination = addCombination(client, unique("Fixture layers"), glaze);
        UUID draft = created(client.post().uri("/api/combinations").body(Map.of("title", unique("Draft"), "steps", List.of(),
                "draft", true)).exchange());
        client.post().uri("/api/stock").body(Map.of("glazeId", glaze, "quantityG", 500, "ownerType", "personal")).exchange().expectStatus().isOk();
        client.put().uri("/api/favorites/glazes/{id}", glaze).exchange().expectStatus().isOk();
        UUID soleClub = createClub(client, unique("Sole"));
        client.post().uri("/api/stock").body(Map.of("glazeId", glaze, "quantityG", 100, "ownerType", "club", "clubId", soleClub)).exchange().expectStatus().isOk();
        uploadTile(client, combination, "CLUB", soleClub);
        UUID sharedClub = createClub(peer, unique("Shared"));
        joinClub(peer, sharedClub, client);
        client.post().uri("/api/stock").body(Map.of("glazeId", glaze, "quantityG", 300, "ownerType", "club", "clubId", sharedClub)).exchange().expectStatus().isOk();
        UUID publicPhoto = uploadTile(client, combination, "PUBLIC", null);
        UUID privatePhoto = uploadTile(client, combination, "PRIVATE", null);
        UUID clubPhoto = uploadTile(client, combination, "CLUB", sharedClub);
        UUID peerGlaze = addGlaze(peer, unique("Peer"), "Green", "2");
        client.patch().uri("/api/glazes/{id}", peerGlaze).body(Map.of("notes", "Runs at cone 6")).exchange().expectStatus().isOk();
        UUID peerPhoto = uploadTile(peer, combination, "PUBLIC", null);
        client.post().uri("/api/images/{id}/reports", peerPhoto).body(Map.of("reason", "Blurry")).exchange().expectStatus().isNoContent();
        UUID peerAvatar = avatar(peer);
        client.post().uri("/api/avatars/{id}/reports", peerAvatar).body(Map.of("reason", "Not a person")).exchange().expectStatus().isNoContent();
        UUID avatar = avatar(client);
        peer.post().uri("/api/avatars/{id}/reports", avatar).body(Map.of("reason", "Shows " + name + "'s neighbour")).exchange().expectStatus().isNoContent();
        ReportApiTest.optIn(client);
        ReportApiTest.send(client, ReportApiTest.batch(List.of(ReportApiTest.event("Fixture error")))).expectStatus().isNoContent();
        sendFeedback(client, "Fixture feedback", Map.of("route", "stock"), PNG).expectStatus().isOk();
        String resetToken = accountToken(id, AccountToken.Purpose.RESET_PASSWORD);
        return new Fixture(client, secondSession, email, id, glaze, combination, draft, publicPhoto, privatePhoto,
                clubPhoto, soleClub, sharedClub, peerGlaze, avatar, resetToken);
    }

    /** Rows that name the account in any table: all zero after an account deletion and after a ban. */
    private Map<String, Integer> footprint(UUID id) {
        var rows = new LinkedHashMap<String, Integer>();
        for (String where : List.of("app_user WHERE id = ?", "club WHERE owner_id = ?", "membership WHERE user_id = ?",
                "glaze WHERE created_by = ?", "stock WHERE user_id = ?", "stock WHERE added_by = ?", "combination WHERE created_by = ?",
                "image WHERE uploaded_by = ?", "account_token WHERE user_id = ?", "favorite WHERE user_id = ?",
                "glaze_edit WHERE editor_id = ?", "image_report WHERE reporter_id = ?", "login_session WHERE user_id = ?",
                "buddy WHERE user_a = ?", "buddy WHERE user_b = ?", "buddy WHERE requested_by = ?", "buddy_link WHERE user_id = ?",
                "user_avatar WHERE user_id = ?", "avatar_report WHERE owner_id = ?", "avatar_report WHERE reporter_id = ?",
                "avatar_report WHERE closed_by = ?", "image_deletion WHERE uploader_id = ?", "image_deletion WHERE deleted_by = ?",
                "admin_action WHERE actor_id = ?", "client_report WHERE user_id = ?", "feedback WHERE user_id = ?",
                "club_wishlist WHERE added_by = ?"))
            rows.put(where, jdbc.queryForObject("SELECT COUNT(*) FROM " + where, Integer.class, id));
        return rows;
    }

    /** What is left of the account's content, as seen by the peer and a guest. */
    private Map<String, Object> outcome(Fixture account, RestTestClient peer) {
        var left = new LinkedHashMap<String, Object>();
        left.put("glaze kept, anonymous", glazes(peer).stream().anyMatch(g -> g.id().equals(account.glaze()) && g.addedBy() == null));
        var published = combination(peer, account.combination()).orElseThrow();
        left.put("combination kept, anonymous", published.author() == null && published.authorId() == null);
        left.put("draft", jdbc.queryForObject("SELECT COUNT(*) FROM combination WHERE id = ?", Integer.class, account.draft()));
        left.put("public photo", guest().get().uri("/api/images/{id}", account.publicPhoto()).exchange().returnResult().getStatus().value());
        left.put("private photo", jdbc.queryForObject("SELECT COUNT(*) FROM image WHERE id = ?", Integer.class, account.privatePhoto()));
        left.put("club photo for the club", peer.get().uri("/api/images/{id}", account.clubPhoto()).exchange().returnResult().getStatus().value());
        left.put("sole-member club", jdbc.queryForObject("SELECT COUNT(*) FROM club WHERE id = ?", Integer.class, account.soleClub()));
        left.put("sole-member club stock", jdbc.queryForObject("SELECT COUNT(*) FROM stock WHERE club_id = ?", Integer.class, account.soleClub()));
        left.put("shared club stock", jdbc.queryForObject("SELECT quantity_g FROM stock WHERE club_id = ?", Integer.class, account.sharedClub()));
        left.put("peer glaze correction kept", jdbc.queryForObject("SELECT COUNT(*) FROM glaze_edit WHERE glaze_id = ? AND editor_id IS NULL",
                Integer.class, account.peerGlaze()));
        left.put("picture", jdbc.queryForObject("SELECT COUNT(*) FROM user_avatar WHERE id = ?", Integer.class, account.avatar()));
        // The reported picture goes with the account (no evidence copy), its fingerprints stay; the report keeps only
        // its status and dates.
        left.put("picture report", jdbc.queryForList("SELECT status, reason, closed_by FROM avatar_report WHERE avatar_id = ?",
                account.avatar()).toString());
        left.put("picture evidence", jdbc.queryForList("SELECT storage_key, outcome, stored_sha256 IS NOT NULL AS hashed"
                + " FROM avatar_evidence WHERE avatar_id = ?", account.avatar()).toString());
        left.put("peer buddies", BuddyApiTest.buddies(peer).buddies().stream().filter(b -> b.id().equals(account.id())).count());
        left.put("session", account.client().get().uri("/api/auth/me").exchange().returnResult().getStatus().value());
        left.put("second session", account.secondSession().get().uri("/api/auth/me").exchange().returnResult().getStatus().value());
        // Signing in is refused exactly like a wrong password; an old reset link is dead.
        left.put("sign-in", statusAndBody(login(guest(), account.email(), PASSWORD)));
        left.put("reset link", guest().post().uri("/api/auth/password/reset").body(Map.of("token", account.resetToken(), "password", "fresh-secret-1"))
                .exchange().returnResult().getStatus().value());
        return left;
    }

    @Test void aBanRemovesTheAccountExactlyLikeAnAccountDeletion() throws Exception {
        var peer = user("Peer");
        var admin = siteAdmin();
        UUID adminId = me(admin).id();
        var deleted = fixture("Deleted", peer);
        var banned = fixture("Banned", peer);

        deleted.client().post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange().expectStatus().isNoContent();
        ban(admin, banned.id(), REASON, false).expectStatus().isNoContent();

        var nothing = footprint(deleted.id());
        nothing.replaceAll((table, count) -> 0);
        assertEquals(nothing, footprint(deleted.id()));
        assertEquals(nothing, footprint(banned.id()));
        var outcome = outcome(deleted, peer);
        assertEquals(outcome, outcome(banned, peer));
        assertEquals(true, outcome.get("glaze kept, anonymous"));
        assertEquals(true, outcome.get("combination kept, anonymous"));
        assertEquals(0, outcome.get("draft"));
        assertEquals(200, outcome.get("public photo"));
        assertEquals(0, outcome.get("private photo"));
        assertEquals(200, outcome.get("club photo for the club"));
        assertEquals(0, outcome.get("sole-member club"));
        assertEquals(0, outcome.get("sole-member club stock"));
        assertEquals(300, outcome.get("shared club stock"));
        assertEquals(1, outcome.get("peer glaze correction kept"));
        assertEquals(0, outcome.get("picture"));
        assertEquals("[{STATUS=CLOSED, REASON=null, CLOSED_BY=null}]", outcome.get("picture report"));
        assertEquals("[{STORAGE_KEY=null, OUTCOME=ACCOUNT_DELETED, HASHED=true}]", outcome.get("picture evidence"));
        assertEquals(0L, outcome.get("peer buddies"));
        assertEquals(401, outcome.get("session"));
        assertEquals(401, outcome.get("second session"));
        assertEquals(statusAndBody(login(guest(), me(peer).email(), "wrong-password")), outcome.get("sign-in"));
        assertEquals(400, outcome.get("reset link"));
        // The one difference in the logs: the photos a ban removed name the banning admin, never the banned account.
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM image_deletion WHERE image_id = ? AND deleted_by IS NOT NULL",
                Integer.class, deleted.privatePhoto()));
        assertEquals(adminId, jdbc.queryForObject("SELECT deleted_by FROM image_deletion WHERE image_id = ?", UUID.class, banned.privatePhoto()));
        // Only the ban blocks the address.
        assertNotNull(registers(deleted.email()));
        assertNull(registers(banned.email()));
        assertFalse(accountExists(banned.email()));
    }

    @Test void theBlockCatchesEveryWritingOfTheSameMailboxAndNothingElse() {
        var admin = siteAdmin();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        // Gmail rules are under test, so its domains stay; the local parts are invented.
        String gmail = "glazelab.test.only." + suffix + "@gmail.com";
        String plain = "first.last." + suffix + "@example.test";
        var gmailUser = signUp(gmail);
        var plainUser = signUp(plain);
        banned(admin, me(gmailUser).id());
        banned(admin, me(plainUser).id());
        String gmailLocal = "glazelabtestonly" + suffix;
        // (Surrounding whitespace is refused by the address validation before it gets here; EmailAddressesTest covers it.)
        for (String alias : List.of(gmail, gmail.toUpperCase(Locale.ROOT), gmailLocal + "@gmail.com",
                "g.l.a.z.e.l.a.b.t.e.s.t.o.n.l.y." + suffix + "+kiln@googlemail.com", gmailLocal + "+a+b@GMAIL.COM",
                plain, plain.toUpperCase(Locale.ROOT), "first.last." + suffix + "+glazes@example.test")) {
            assertNull(registers(alias), alias);
            assertFalse(accountExists(alias.strip().toLowerCase(Locale.ROOT)), alias);
        }
        // Different mailboxes: dots count outside Gmail, another name or domain is another address.
        for (String other : List.of("firstlast." + suffix + "@example.test", "first.last." + suffix + "@example.org",
                gmailLocal + "x@gmail.com", "glazelab.test.only." + suffix + "@example.net"))
            assertNotNull(registers(other), other);
    }

    @Test void blockedAddressesGetNoLinksAndAnAliasAccountFromBeforeTheBanCannotUseOne() {
        var admin = siteAdmin();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bannedEmail = "testonlyab" + suffix + "@gmail.com";
        var bannedUser = signUp(bannedEmail);
        // Another account of the same mailbox registered before the ban, and a registration that raced it (pending).
        String aliasEmail = "test.only.ab" + suffix + "+old@gmail.com";
        var alias = signUp(aliasEmail);
        UUID aliasId = me(alias).id();
        String racer = "test.only.ab." + suffix + "@gmail.com";
        String racerToken = registers(racer);
        assertNotNull(racerToken);

        banned(admin, me(bannedUser).id());

        // A ban removes one account; the alias account keeps signing in with its password but no link works for it.
        login(guest(), aliasEmail, PASSWORD).expectStatus().isOk();
        guest().post().uri("/api/auth/password/reset").body(Map.of("token", accountToken(aliasId, AccountToken.Purpose.RESET_PASSWORD),
                "password", "fresh-secret-1")).exchange().expectStatus().isBadRequest();
        guest().post().uri("/api/auth/magic/confirm").body(Map.of("token", accountToken(aliasId, AccountToken.Purpose.MAGIC_LOGIN)))
                .exchange().expectStatus().isBadRequest();
        // The raced pending account can never be activated.
        activate(guest(), racerToken, PASSWORD, "Racer").expectStatus().isBadRequest();
        login(guest(), racer, PASSWORD).expectStatus().isUnauthorized();
    }

    /** An unban re-reads the admin's flag under the admin's account lock: a flag taken away meanwhile refuses with 403. */
    @Test void anUnbanReReadsTheSiteAdminFlagUnderTheLock() {
        var admin = siteAdmin();
        UUID adminId = me(admin).id();
        UUID target = me(signUp(email("unbanflag"))).id();
        banned(admin, target);
        UUID entry = entriesOf(admin, target).getFirst().id();
        jdbc.update("UPDATE app_user SET site_admin = FALSE WHERE id = ?", adminId);
        var refused = assertThrows(ResponseStatusException.class, () -> blocklist.unban(entry, adminId));
        assertEquals(403, refused.getStatusCode().value());
        assertEquals(CurrentUser.SITE_ADMIN_REQUIRED, refused.getReason());
        jdbc.update("UPDATE app_user SET site_admin = TRUE WHERE id = ?", adminId);
        assertEquals(1, entriesOf(admin, target).size());
        assertTrue(actions("UNBAN", entry).isEmpty());
    }

    @Test void unbanLetsTheAddressRegisterAgainAndTheListNamesNoAddress() {
        var admin = siteAdmin();
        UUID adminId = me(admin).id();
        String email = email("Listed");
        var target = signUp(email);
        UUID targetId = me(target).id();
        String name = me(target).displayName();
        Instant before = Instant.now().minusSeconds(1);
        ban(admin, targetId, REASON, null).expectStatus().isNoContent();

        String json = admin.get().uri("/api/admin/bans").exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        assertFalse(json.contains(email) || json.contains(email.split("@")[0] + "@"), "no address in the list");
        // 34-7: the entry names nobody, neither the banned person nor the admin; it keeps the fingerprint, date and reason.
        assertFalse(json.contains(name) || json.contains(me(admin).displayName()) || json.contains(adminId.toString())
                || json.contains(targetId.toString()), json);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE LOWER(table_name) = 'email_block'"
                + " AND LOWER(column_name) NOT IN ('id', 'email_hash', 'reason', 'banned_at')", Integer.class));
        var entry = entriesOf(admin, targetId);
        assertEquals(1, entry.size());
        assertEquals(REASON, entry.getFirst().reason());
        assertTrue(entry.getFirst().bannedAt().isAfter(before));
        String hash = jdbc.queryForObject("SELECT email_hash FROM email_block WHERE id = ?", String.class, entry.getFirst().id());
        assertTrue(hash.matches("[0-9a-f]{64}") && !hash.contains(email), hash);
        assertEquals(entry.getFirst().id(), actions("BAN", targetId).getFirst().get("target_id"));
        // Newest first, a page of 50 and the next page's flag.
        for (int i = 0; i < 51; i++)
            jdbc.update("INSERT INTO email_block (id, email_hash, reason, banned_at) VALUES (?, ?, 'Old', ?)",
                    UUID.randomUUID(), UUID.randomUUID().toString().replace("-", "") + "0".repeat(32), Instant.parse("2020-01-01T00:00:00Z"));
        var first = bans(admin, 0);
        assertTrue(first.hasMore());
        assertEquals(50, first.entries().size());
        assertTrue(first.entries().stream().anyMatch(e -> e.id().equals(entry.getFirst().id())));

        assertError(user("Nobody").delete().uri("/api/admin/bans/{id}", entry.getFirst().id()).exchange(), 403, CurrentUser.SITE_ADMIN_REQUIRED);
        assertNull(registers(email));
        admin.delete().uri("/api/admin/bans/{id}", entry.getFirst().id()).exchange().expectStatus().isNoContent();
        assertError(admin.delete().uri("/api/admin/bans/{id}", entry.getFirst().id()).exchange(), 404, EmailBlocklist.BAN_NOT_FOUND);
        assertTrue(entriesOf(admin, targetId).isEmpty());
        var unban = actions("UNBAN", entry.getFirst().id());
        assertEquals(1, unban.size());
        assertEquals(adminId, unban.getFirst().get("actor_id"));
        assertNotNull(registers(email));
        assertTrue(accountExists(email));
    }

    @Test void anEntryOutlivesItsAdminAndASecondBanOfTheSameMailboxKeepsTheFirstEntry() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        var leaving = siteAdmin();
        UUID leavingId = me(leaving).id();
        var first = signUp("testonlytwice" + suffix + "@gmail.com");
        UUID firstId = me(first).id();
        var second = signUp("test.only.t.w.i.c.e" + suffix + "@gmail.com");
        UUID secondId = me(second).id();
        banned(leaving, me(first).id());
        var admin = siteAdmin();
        ban(admin, secondId, "Second account", null).expectStatus().isNoContent();
        var entries = entriesOf(admin, firstId);
        assertEquals(1, entries.size());
        assertEquals(entries.getFirst().id(), actions("BAN", secondId).getFirst().get("target_id"));
        jdbc.update("UPDATE app_user SET site_admin = FALSE WHERE id = ?", leavingId);
        leaving.post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange().expectStatus().isNoContent();
        assertEquals(1, entriesOf(admin, firstId).size());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM admin_action WHERE actor_id = ?", Integer.class, leavingId));
    }

    @Test void aBannedLastClubAdminHandsTheClubToTheLongestStandingVerifiedMember() throws Exception {
        var admin = siteAdmin();
        UUID adminId = me(admin).id();
        var target = user("ClubOwner");
        UUID targetId = me(target).id();
        var first = user("FirstJoined");
        var second = user("SecondJoined");
        UUID club = createClub(target, unique("Handed over"));
        joinClub(target, club, first);
        joinClub(target, club, second);
        UUID glaze = addGlaze(target, unique("Club"), "Blue", "1");
        target.post().uri("/api/stock").body(Map.of("glazeId", glaze, "quantityG", 400, "ownerType", "club", "clubId", club)).exchange().expectStatus().isOk();
        UUID combination = addCombination(target, unique("Club layers"), glaze);
        UUID photo = uploadTile(target, combination, "CLUB", club);
        // The second joined earlier by the stored time; a pending leftover joined earliest and never takes over.
        UUID secondId = me(second).id();
        jdbc.update("UPDATE membership SET joined_at = ? WHERE club_id = ? AND user_id = ?", Instant.parse("2025-01-01T00:00:00Z"), club, secondId);
        String pendingEmail = email("PendingLeftover");
        register(guest(), pendingEmail, "Pending").expectStatus().isOk();
        UUID pendingId = jdbc.queryForObject("SELECT id FROM app_user WHERE email = ?", UUID.class, pendingEmail);
        jdbc.update("INSERT INTO membership (club_id, user_id, role, joined_at) VALUES (?, ?, 'MEMBER', ?)", club, pendingId,
                Instant.parse("2020-01-01T00:00:00Z"));
        // An own deletion is refused here (409); a ban is not.
        target.post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange().expectStatus().isEqualTo(409);

        ban(admin, targetId, REASON, null).expectStatus().isNoContent();

        assertEquals("ADMIN", jdbc.queryForObject("SELECT role FROM membership WHERE club_id = ? AND user_id = ?", String.class, club, secondId));
        assertEquals("MEMBER", jdbc.queryForObject("SELECT role FROM membership WHERE club_id = ? AND user_id = ?", String.class, club, me(first).id()));
        assertEquals("MEMBER", jdbc.queryForObject("SELECT role FROM membership WHERE club_id = ? AND user_id = ?", String.class, club, pendingId));
        var promoted = actions("CLUB_ADMIN_PROMOTED", club);
        assertEquals(1, promoted.size());
        assertEquals(secondId, promoted.getFirst().get("target_user_id"));
        assertEquals(adminId, promoted.getFirst().get("actor_id"));
        // The club keeps its stock and club photos; the new admin runs it.
        assertEquals(400, jdbc.queryForObject("SELECT quantity_g FROM stock WHERE club_id = ?", Integer.class, club));
        first.get().uri("/api/images/{id}", photo).exchange().expectStatus().isOk();
        second.put().uri("/api/clubs/{id}/members/{user}", club, me(first).id()).body(Map.of("role", "ADMIN")).exchange().expectStatus().isOk();
    }

    @Test void aClubWhoseOtherMembersArePendingGoesWithTheBannedAdmin() {
        var admin = siteAdmin();
        var target = user("PendingClubOwner");
        UUID club = createClub(target, unique("Pending only"));
        String pendingEmail = email("OnlyPending");
        register(guest(), pendingEmail, "Pending").expectStatus().isOk();
        UUID pendingId = jdbc.queryForObject("SELECT id FROM app_user WHERE email = ?", UUID.class, pendingEmail);
        jdbc.update("INSERT INTO membership (club_id, user_id, role) VALUES (?, ?, 'MEMBER')", club, pendingId);
        target.post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange().expectStatus().isEqualTo(409);

        banned(admin, me(target).id());

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM club WHERE id = ?", Integer.class, club));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM membership WHERE club_id = ?", Integer.class, club));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE id = ?", Integer.class, pendingId));
        assertTrue(actions("CLUB_ADMIN_PROMOTED", club).isEmpty());
    }

    @Test void aClubWithAnotherAdminNeedsNoPromotion() {
        var admin = siteAdmin();
        var target = user("CoAdmin");
        var other = user("OtherAdmin");
        UUID club = createClub(target, unique("Two admins"));
        joinClub(target, club, other);
        target.put().uri("/api/clubs/{id}/members/{user}", club, me(other).id()).body(Map.of("role", "ADMIN")).exchange().expectStatus().isOk();
        banned(admin, me(target).id());
        assertEquals(List.of("ADMIN"), jdbc.queryForList("SELECT role FROM membership WHERE club_id = ?", String.class, club));
        assertTrue(actions("CLUB_ADMIN_PROMOTED", club).isEmpty());
    }

    private static List<AvatarReportView> reportsOf(RestTestClient admin, UUID avatar) {
        return body(admin.get().uri("/api/admin/avatar-reports").exchange().expectStatus().isOk(), AvatarReportPageView.class)
                .entries().stream().filter(entry -> entry.avatarId().equals(avatar)).toList();
    }

    private static void reported(RestTestClient reporter, UUID avatar) {
        reporter.post().uri("/api/avatars/{id}/reports", avatar).body(Map.of("reason", "Shows someone else")).exchange().expectStatus().isNoContent();
    }

    @Test void blockPicturesBlocksTheReportedPicturesCurrentAndEarlierButNotDismissedOnes() {
        var admin = siteAdmin();
        var reporter = user("Reporter");
        var uploader = user("Uploader");
        var target = user("Pictured");
        UUID targetId = me(target).id();
        byte[] earlier = TestImages.unique(), dismissed = TestImages.unique(), current = TestImages.unique();
        UUID earlierId = created(uploadAvatar(target, earlier));
        reported(reporter, earlierId);
        UUID dismissedId = created(uploadAvatar(target, dismissed));
        reported(reporter, dismissedId);
        admin.post().uri("/api/admin/avatar-reports/{id}/dismiss", reportsOf(admin, dismissedId).getFirst().id()).exchange().expectStatus().isNoContent();
        UUID currentId = created(uploadAvatar(target, current));
        reported(reporter, currentId);

        ban(admin, targetId, REASON, true).expectStatus().isNoContent();

        // 34-7: the pictures go with the account, their fingerprints stay and block them.
        for (UUID picture : List.of(earlierId, currentId, dismissedId)) {
            var report = reportsOf(admin, picture).getFirst();
            assertFalse(report.hasPicture(), "deleted with the account");
            assertNull(report.ownerId());
            assertNull(report.reason());
            assertNull(jdbc.queryForObject("SELECT storage_key FROM avatar_evidence WHERE avatar_id = ?", String.class, picture));
        }
        for (UUID picture : List.of(earlierId, currentId))
            assertEquals(AvatarReportStatus.ACTIONED, reportsOf(admin, picture).getFirst().status());
        assertEquals(AvatarPictureState.REPLACED, reportsOf(admin, earlierId).getFirst().picture());
        assertEquals(AvatarPictureState.ADMIN_REMOVED, reportsOf(admin, currentId).getFirst().picture());
        assertEquals(AvatarReportStatus.DISMISSED, reportsOf(admin, dismissedId).getFirst().status());
        assertError(uploadAvatar(uploader, earlier), 409, AvatarService.PICTURE_REFUSED);
        assertError(uploadAvatar(uploader, current), 409, AvatarService.PICTURE_REFUSED);
        uploadAvatar(uploader, dismissed).expectStatus().isOk();
        var removals = jdbc.queryForList("SELECT target_id FROM admin_action WHERE action = 'AVATAR_REMOVED' AND target_user_id = ?", UUID.class, targetId);
        assertEquals(Set.of(earlierId, currentId), Set.copyOf(removals));
    }

    /**
     * 34-7: without {@code blockPictures} the pictures go with the account too, so nobody can decide their reports any
     * more: they are CLOSED without their text, and the picture is not blocked.
     */
    @Test void withoutBlockPicturesTheReportsAreClosedWithTheAccount() {
        var admin = siteAdmin();
        var reporter = user("Reporter");
        var uploader = user("Uploader");
        var target = user("PicturedOpen");
        byte[] picture = TestImages.unique();
        UUID pictureId = created(uploadAvatar(target, picture));
        reported(reporter, pictureId);

        banned(admin, me(target).id());

        var report = reportsOf(admin, pictureId).getFirst();
        assertEquals(AvatarReportStatus.CLOSED, report.status());
        assertNotNull(report.closedAt());
        assertNull(report.closedBy());
        assertNull(report.reason());
        assertEquals(AvatarPictureState.ACCOUNT_DELETED, report.picture());
        assertFalse(report.hasPicture());
        for (String decision : List.of("remove", "dismiss"))
            assertError(admin.post().uri("/api/admin/avatar-reports/{id}/" + decision, report.id()).exchange(), 409,
                    ApiErrorHandler.CHANGED_MEANWHILE);
        assertTrue(reportsOf(admin, pictureId).stream().allMatch(entry -> entry.status() == AvatarReportStatus.CLOSED));
        // Not blocked: nobody decided against it.
        created(uploadAvatar(uploader, picture));
    }

    @Test void siteAdminPictureRemovalsAreLogged() {
        var admin = siteAdmin();
        UUID adminId = me(admin).id();
        var owner = user("Moderated");
        UUID ownerId = me(owner).id();
        UUID picture = avatar(owner);
        admin.delete().uri("/api/avatars/{id}", picture).exchange().expectStatus().isNoContent();
        var rows = jdbc.queryForList("SELECT actor_id, target_user_id FROM admin_action WHERE action = 'AVATAR_REMOVED' AND target_id = ?", picture);
        assertEquals(1, rows.size());
        assertEquals(adminId, rows.getFirst().get("actor_id"));
        assertEquals(ownerId, rows.getFirst().get("target_user_id"));
        // The owner's own removal is no admin action.
        UUID own = avatar(owner);
        owner.delete().uri("/api/avatars/{id}", own).exchange().expectStatus().isNoContent();
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM admin_action WHERE target_id = ?", Integer.class, own));
    }

    @Test void aPendingAccountCanBeBannedToo() {
        var admin = siteAdmin();
        String email = email("PendingBanned");
        assertNotNull(registers(email));
        UUID id = jdbc.queryForObject("SELECT id FROM app_user WHERE email = ?", UUID.class, email);
        banned(admin, id);
        assertFalse(accountExists(email));
        assertNull(registers(email));
    }
    /**
     * 34-7: the admin log forgets its rows after the retention (90 days), the blocklist entry stays while the ban
     * stands (a pseudonymous fingerprint with its date and reason) and keeps the address blocked.
     */
    @Test void theAdminLogForgetsItsRowsAfterTheRetentionAndTheBanStays() {
        var admin = siteAdmin();
        String email = email("Forgotten");
        var target = signUp(email);
        UUID targetId = me(target).id();
        UUID recent = me(user("Recent")).id();
        ban(admin, targetId, REASON, null).expectStatus().isNoContent();
        banned(admin, recent);
        UUID entry = (UUID) actions("BAN", targetId).getFirst().get("target_id");
        Instant now = Instant.now();
        jdbc.update("UPDATE admin_action SET created_at = ? WHERE target_user_id = ?", Timestamp.from(now.minus(Duration.ofDays(91))), targetId);
        jdbc.update("UPDATE admin_action SET created_at = ? WHERE target_user_id = ?", Timestamp.from(now.minus(Duration.ofDays(89))), recent);

        assertTrue(adminLog.purge() >= 1);
        assertTrue(actions("BAN", targetId).isEmpty());
        assertEquals(1, actions("BAN", recent).size());
        assertEquals(0, adminLog.purge(), "a second run finds nothing");
        assertTrue(bans(admin, 0).entries().stream().anyMatch(row -> row.id().equals(entry) && row.reason().equals(REASON)));
        assertNull(registers(email));
        assertFalse(accountExists(email));
    }
}
