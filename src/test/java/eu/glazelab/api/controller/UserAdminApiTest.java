package eu.glazelab.api.controller;

import eu.glazelab.api.dto.AdminUserDtos.AccountStatus;
import eu.glazelab.api.dto.AdminUserDtos.AdminUserPageView;
import eu.glazelab.api.dto.AdminUserDtos.AdminUserView;
import eu.glazelab.api.security.CurrentUser;
import eu.glazelab.api.support.ApiTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The site admins' account list (backend guide "Admin user list"): roles, paging, the pending state and the fields it never carries. */
class UserAdminApiTest extends ApiTestBase {
    private static final String LIST = "/api/admin/users";

    @Test void onlySiteAdminsReadTheListAndPagesAreChecked() {
        var admin = siteAdmin();
        assertError(guest().get().uri(LIST).exchange(), 401, CurrentUser.SIGN_IN_REQUIRED);
        assertError(user("Plain").get().uri(LIST).exchange(), 403, CurrentUser.SITE_ADMIN_REQUIRED);
        admin.get().uri(LIST).exchange().expectStatus().isOk();
        admin.get().uri(LIST + "?page=-1").exchange().expectStatus().isBadRequest();
        admin.get().uri(LIST + "?page=100001").exchange().expectStatus().isBadRequest();
        assertTrue(body(admin.get().uri(LIST + "?page=99999").exchange().expectStatus().isOk(), AdminUserPageView.class).entries().isEmpty());
    }

    @Test void listsEveryAccountInPagesWithStatusAndRoleAndNothingElse() {
        var admin = siteAdmin();
        UUID adminId = me(admin).id();
        String pendingEmail = email("Pending");
        register(guest(), pendingEmail, "Pending Pat").expectStatus().isOk();
        String prefix = "Zz" + UUID.randomUUID().toString().substring(0, 6);
        for (int i = 0; i < 52; i++) {
            String address = email("bulk" + i);
            jdbc.update("INSERT INTO app_user (id, email, email_key, password_hash, display_name, email_verified, session_version, site_admin)"
                    + " VALUES (?, ?, ?, 'unusable-secret-hash', ?, TRUE, 0, FALSE)", UUID.randomUUID(), address, address, String.format("%s %02d", prefix, i));
        }
        var all = new ArrayList<AdminUserView>();
        String raw = null;
        int page = 0;
        while (true) {
            var response = admin.get().uri(LIST + "?page=" + page).exchange().expectStatus().isOk();
            var result = body(response, AdminUserPageView.class);
            assertTrue(result.entries().size() <= 50, "a page holds at most 50");
            if (page == 0) {
                raw = admin.get().uri(LIST).exchange().expectBody(String.class).returnResult().getResponseBody();
                assertTrue(result.hasMore(), "more than one page");
            }
            all.addAll(result.entries());
            if (!result.hasMore()) break;
            page++;
        }
        assertEquals(jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class), all.size());
        assertEquals(all.size(), all.stream().map(AdminUserView::id).distinct().count(), "no account twice");
        // Ordered by display name (the bulk accounts keep their numeric order).
        var bulk = all.stream().filter(row -> row.displayName().startsWith(prefix)).map(AdminUserView::displayName).toList();
        assertEquals(52, bulk.size());
        assertEquals(bulk.stream().sorted().toList(), bulk);
        var self = all.stream().filter(row -> row.id().equals(adminId)).findFirst().orElseThrow();
        assertTrue(self.siteAdmin());
        assertEquals(AccountStatus.ACTIVE, self.status());
        var pending = all.stream().filter(row -> "Pending Pat".equals(row.displayName())).findFirst().orElseThrow();
        assertEquals(AccountStatus.PENDING, pending.status());
        assertFalse(pending.siteAdmin());
        assertEquals(pendingEmail, pending.email());
        assertNotNull(pending.createdAt(), "a registration records its time");
        assertTrue(java.time.Duration.between(pending.createdAt(), java.time.Instant.now()).abs().toMinutes() < 5);
        var bulkRow = all.stream().filter(row -> row.displayName().startsWith(prefix)).findFirst().orElseThrow();
        assertNull(bulkRow.createdAt(), "an account without a recorded time shows none");
        assertEquals(jdbc.queryForObject("SELECT email FROM app_user WHERE id = ?", String.class, adminId), self.email());
        // The address is expected (site admins only); never a hash or any session or token data.
        var keys = new java.util.TreeSet<String>();
        var matcher = java.util.regex.Pattern.compile("\"(\\w+)\":").matcher(raw);
        while (matcher.find()) keys.add(matcher.group(1));
        assertEquals(new java.util.TreeSet<>(List.of("entries", "hasMore", "id", "displayName", "email", "status", "siteAdmin", "createdAt")), keys);
        assertFalse(raw.contains("unusable-secret-hash") || raw.toLowerCase().contains("password"));
        jdbc.update("DELETE FROM app_user WHERE display_name LIKE ?", prefix + "%");
    }

    private List<AdminUserView> search(org.springframework.test.web.servlet.client.RestTestClient admin, String q, int page) {
        return body(admin.get().uri(uri -> uri.path(LIST).queryParam("q", q).queryParam("page", page).build()).exchange().expectStatus().isOk(),
                AdminUserPageView.class).entries();
    }

    @Test void searchKeepsAccountsWhoseNameOrEmailContainsTheTextAndEscapesWildcards() {
        var admin = siteAdmin();
        String tag = "Sq" + UUID.randomUUID().toString().substring(0, 6);
        UUID byName = UUID.randomUUID(), byEmail = UUID.randomUUID(), literal = UUID.randomUUID(), other = UUID.randomUUID();
        String insert = "INSERT INTO app_user (id, email, email_key, password_hash, display_name, email_verified, session_version, site_admin)"
                + " VALUES (?, ?, ?, 'unusable-secret-hash', ?, TRUE, 0, FALSE)";
        String plain = email("plain-" + tag), mail = tag.toLowerCase() + "-mail@example.test", lit = email("lit-" + tag),
                unrelated = "unrelated-" + UUID.randomUUID() + "@example.test";
        jdbc.update(insert, byName, plain, plain, "Mary " + tag.toUpperCase());
        jdbc.update(insert, byEmail, mail, mail, "Quiet Person");
        jdbc.update(insert, literal, lit, lit, "100%_" + tag + "\\x");
        jdbc.update(insert, other, unrelated, unrelated, "Unrelated");
        try {
            assertEquals(java.util.Set.of(byName, byEmail, literal), search(admin, tag.toLowerCase(), 0).stream().map(AdminUserView::id).collect(java.util.stream.Collectors.toSet()));
            assertEquals(3, search(admin, "  " + tag.toUpperCase() + "  ", 0).size(), "trimmed and case-insensitive");
            assertEquals(List.of(byName), search(admin, "mary " + tag, 0).stream().map(AdminUserView::id).toList());
            assertEquals(List.of(byEmail), search(admin, tag.toLowerCase() + "-mail@", 0).stream().map(AdminUserView::id).toList());
            // Wildcards and the escape character are literal text.
            assertEquals(List.of(literal), search(admin, "100%_" + tag, 0).stream().map(AdminUserView::id).toList());
            assertEquals(List.of(literal), search(admin, tag + "\\x", 0).stream().map(AdminUserView::id).toList());
            assertTrue(search(admin, "%" + tag, 0).isEmpty(), "% is not a wildcard");
            assertTrue(search(admin, tag.charAt(0) + "_" + tag.substring(2), 0).isEmpty(), "_ is not a wildcard");
            assertTrue(search(admin, "no-such-" + tag, 0).isEmpty());
            // Blank means no search; a very long text is cut to 100 characters instead of failing.
            assertFalse(search(admin, "   ", 0).isEmpty());
            assertEquals(search(admin, "", 0), search(admin, "   ", 0));
            assertEquals(200, admin.get().uri(uri -> uri.path(LIST).queryParam("q", "x".repeat(500)).build()).exchange().returnResult(String.class).getStatus().value());
        } finally {
            jdbc.update("DELETE FROM app_user WHERE id IN (?, ?, ?, ?)", byName, byEmail, literal, other);
        }
    }
}
