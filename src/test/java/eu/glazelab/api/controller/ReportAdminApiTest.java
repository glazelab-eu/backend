package eu.glazelab.api.controller;

import eu.glazelab.api.dto.ReportDtos.ReportBuildsView;
import eu.glazelab.api.dto.ReportDtos.ReportGroupPageView;
import eu.glazelab.api.dto.ReportDtos.ReportGroupView;
import eu.glazelab.api.dto.ReportDtos.ReportOccurrencePageView;
import eu.glazelab.api.security.CurrentUser;
import eu.glazelab.api.security.TermsFilter;
import eu.glazelab.api.service.ReportAdminService;
import eu.glazelab.api.support.ApiTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.test.web.servlet.client.RestTestClient.ResponseSpec;

import static eu.glazelab.api.controller.ReportApiTest.batch;
import static eu.glazelab.api.controller.ReportApiTest.event;
import static eu.glazelab.api.controller.ReportApiTest.optIn;
import static eu.glazelab.api.controller.ReportApiTest.send;
import static org.junit.jupiter.api.Assertions.*;

/** Site admins reading the error reports (backend guide "Error reports"). */
class ReportAdminApiTest extends ApiTestBase {
    private static final String BASE = "/api/admin/error-reports";

    private static ResponseSpec get(RestTestClient client, String uri) {
        return client.get().uri(uri).exchange();
    }

    private static ReportGroupPageView groups(RestTestClient admin, String query) {
        return body(get(admin, BASE + query).expectStatus().isOk(), ReportGroupPageView.class);
    }

    private static ReportGroupView only(ReportGroupPageView page) {
        assertEquals(1, page.groups().size());
        return page.groups().getFirst();
    }

    private RestTestClient reporter(String name) {
        var client = user(name);
        optIn(client);
        return client;
    }

    private static Map<String, Object> eventOf(String message, String kind, String build) {
        var event = ReportApiTest.event(message);
        event.put("kind", kind);
        event.put("build", build);
        return event;
    }

    @Test void onlySiteAdminsReadOrDeleteReports() {
        jdbc.update("DELETE FROM client_report");
        var admin = siteAdmin();
        var user = reporter("Sender");
        send(user, batch(List.of(event("Boom 1")))).expectStatus().isNoContent();
        String fingerprint = groups(admin, "").groups().getFirst().fingerprint();
        UUID report = body(get(admin, BASE + "/" + fingerprint + "/occurrences").expectStatus().isOk(),
                ReportOccurrencePageView.class).occurrences().getFirst().id();
        var uris = List.of(BASE, BASE + "/builds", BASE + "/" + fingerprint + "/occurrences");
        for (String uri : uris) {
            assertError(get(guest(), uri), 401, CurrentUser.SIGN_IN_REQUIRED);
            assertError(get(user, uri), 403, CurrentUser.SITE_ADMIN_REQUIRED);
        }
        var writes = List.of(BASE + "/" + fingerprint, BASE + "/occurrences/" + report);
        for (String uri : writes) {
            assertError(guest().delete().uri(uri).exchange(), 401, CurrentUser.SIGN_IN_REQUIRED);
            assertError(user.delete().uri(uri).exchange(), 403, CurrentUser.SITE_ADMIN_REQUIRED);
            guestWithoutCsrf().delete().uri(uri).exchange().expectStatus().isForbidden();
        }
        assertEquals(1, groups(admin, "").groups().size());
        // The current terms are needed for the writes, not for reading.
        jdbc.update("UPDATE app_user SET accepted_terms_version = '2000-01-01.1' WHERE id = ?", me(admin).id());
        assertError(admin.delete().uri(writes.getFirst()).exchange(), 428, TermsFilter.TERMS_REQUIRED);
        groups(admin, "");
        assertEquals(1, count());
    }

    private int count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM client_report", Integer.class);
    }

    @Test void messagesAreGroupedByFingerprintWithTheirSpread() {
        jdbc.update("DELETE FROM client_report");
        var admin = siteAdmin();
        var first = reporter("First");
        var second = reporter("Second");
        send(first, batch(List.of(eventOf("Cannot read item 5 of 12", "ERROR", "0.1.0 (a)")))).expectStatus().isNoContent();
        send(second, batch(List.of(eventOf("Cannot read item 7 of 3", "ERROR", "0.2.0 (b)"),
                eventOf("Cannot read item 7 of 3", "WARNING", "0.2.0 (b)")))).expectStatus().isNoContent();
        var page = groups(admin, "");
        assertEquals(2, page.groups().size());
        assertFalse(page.hasMore());
        var errors = only(groups(admin, "?kind=ERROR"));
        assertEquals("ERROR", errors.kind().name());
        assertEquals(2, errors.reports());
        assertEquals(4, errors.occurrences());
        assertEquals(List.of("0.1.0 (a)", "0.2.0 (b)"), errors.builds().stream().map(b -> b.build()).sorted().toList());
        assertEquals(1, errors.builds().getFirst().reports());
        assertEquals(1, errors.devices().size());
        assertEquals("Chrome", errors.devices().getFirst().browser());
        assertEquals("Android", errors.devices().getFirst().os());
        assertEquals(2, errors.devices().getFirst().reports());
        assertEquals(412, errors.minViewportWidth());
        assertEquals(412, errors.maxViewportWidth());
        assertFalse(errors.firstSeen().isAfter(errors.lastSeen()));
        assertEquals(64, errors.fingerprint().length());
        // Filters: kind, exact build, text (case-insensitive, wildcards literal), and together.
        assertEquals("WARNING", only(groups(admin, "?kind=WARNING")).kind().name());
        var oneBuild = only(groups(admin, "?kind=ERROR&build=0.1.0 (a)"));
        assertEquals(1, oneBuild.reports());
        assertEquals(1, oneBuild.builds().size());
        assertEquals(2, groups(admin, "?q=CANNOT READ").groups().size());
        assertEquals(0, groups(admin, "?q=cannot%").groups().size());
        assertEquals(0, groups(admin, "?q=nothing").groups().size());
        assertEquals(0, groups(admin, "?build=other").groups().size());
        assertEquals(0, groups(admin, "?kind=ERROR&q=nothing").groups().size());
        assertEquals(List.of("0.1.0 (a)", "0.2.0 (b)"), body(get(admin, BASE + "/builds").expectStatus().isOk(),
                ReportBuildsView.class).builds().stream().sorted().toList());
        // Bad input is a 400.
        get(admin, BASE + "?kind=FATAL").expectStatus().isBadRequest();
        get(admin, BASE + "?page=-1").expectStatus().isBadRequest();
        assertError(get(admin, BASE + "?q=" + "x".repeat(101)), 400, ReportAdminService.QUERY_TOO_LONG);
    }

    @Test void groupsArePagedNewestFirst() {
        jdbc.update("DELETE FROM client_report");
        var admin = siteAdmin();
        var user = reporter("Many");
        for (int from = 0; from < 52; from += 20) {
            var events = new ArrayList<Map<String, Object>>();
            for (int i = from; i < Math.min(from + 20, 52); i++) events.add(event("Distinct word" + "abcdefghijklmnopqrstuvwxyz".charAt(i % 26) + "x".repeat(i / 26 + 1)));
            send(user, batch(events)).expectStatus().isNoContent();
        }
        // Make the order known: the message named "...y" is the newest, "...z" the oldest.
        jdbc.update("UPDATE client_report SET received_at = received_at - INTERVAL '1' HOUR WHERE message LIKE '%wordz%'");
        jdbc.update("UPDATE client_report SET received_at = received_at + INTERVAL '1' HOUR WHERE message LIKE '%wordy%'");
        var first = groups(admin, "?page=0");
        var second = groups(admin, "?page=1");
        assertEquals(50, first.groups().size());
        assertTrue(first.hasMore());
        assertEquals(2, second.groups().size());
        assertFalse(second.hasMore());
        assertTrue(first.groups().getFirst().message().startsWith("Distinct wordy"));
        assertTrue(second.groups().getLast().message().startsWith("Distinct wordz"));
        var seen = new java.util.HashSet<String>();
        first.groups().forEach(g -> assertTrue(seen.add(g.fingerprint())));
        second.groups().forEach(g -> assertTrue(seen.add(g.fingerprint())));
        assertEquals(52, seen.size());
        assertTrue(groups(admin, "?page=2").groups().isEmpty());
    }

    @Test void occurrencesCarryStackAndDeviceButNeverTheAccount() {
        jdbc.update("DELETE FROM client_report");
        var admin = siteAdmin();
        var user = reporter("Secretname");
        UUID userId = me(user).id();
        var events = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 20; i++) events.add(event("Failure " + i));
        send(user, batch(events)).expectStatus().isNoContent();
        send(user, batch(List.of(event("Failure 99")))).expectStatus().isNoContent();
        jdbc.update("UPDATE client_report SET received_at = received_at - INTERVAL '1' MINUTE WHERE id IN"
                + " (SELECT id FROM client_report ORDER BY id FETCH FIRST 3 ROWS ONLY)");
        String fingerprint = only(groups(admin, "")).fingerprint();
        assertEquals(21, only(groups(admin, "")).reports());
        String raw = get(admin, BASE + "/" + fingerprint + "/occurrences").expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        String all = raw + get(admin, BASE).expectBody(String.class).returnResult().getResponseBody()
                + get(admin, BASE + "/builds").expectBody(String.class).returnResult().getResponseBody();
        for (String secret : List.of(userId.toString(), "Secretname", "userId", "user_id", "email", "@")) assertFalse(all.contains(secret), secret);
        var page = body(get(admin, BASE + "/" + fingerprint + "/occurrences").expectStatus().isOk(), ReportOccurrencePageView.class);
        assertEquals(ReportAdminService.OCCURRENCE_PAGE_SIZE, page.occurrences().size());
        assertTrue(page.hasMore());
        var newest = page.occurrences().getFirst();
        assertEquals(2, newest.occurrences());
        assertEquals("0.1.0 (src 1a2b3c4d5e)", newest.build());
        assertEquals("glaze", newest.route());
        assertTrue(newest.stack().startsWith("TypeError: Failure"), newest.stack());
        assertEquals("Chrome", newest.device().browser());
        assertEquals(412, newest.device().viewportWidth());
        assertEquals("COARSE", newest.device().pointer().name());
        assertEquals(8.0, newest.device().deviceMemoryGb());
        for (int i = 1; i < page.occurrences().size(); i++)
            assertFalse(page.occurrences().get(i).receivedAt().isAfter(page.occurrences().get(i - 1).receivedAt()));
        var rest = body(get(admin, BASE + "/" + fingerprint + "/occurrences?page=1").expectStatus().isOk(), ReportOccurrencePageView.class);
        assertEquals(1, rest.occurrences().size());
        assertFalse(rest.hasMore());
        get(admin, BASE + "/" + fingerprint + "/occurrences?page=-1").expectStatus().isBadRequest();
        assertError(get(admin, BASE + "/nothing/occurrences"), 404, ReportAdminService.REPORT_NOT_FOUND);
        assertError(get(admin, BASE + "/" + "0".repeat(64) + "/occurrences"), 404, ReportAdminService.REPORT_NOT_FOUND);
    }

    @Test void adminsDeleteOneReportOrAWholeMessage() {
        jdbc.update("DELETE FROM client_report");
        var admin = siteAdmin();
        var user = reporter("Deleted");
        send(user, batch(List.of(event("Same 1"), event("Same 2"), event("Other thing")))).expectStatus().isNoContent();
        var page = groups(admin, "");
        String same = page.groups().stream().filter(g -> g.reports() == 2).findFirst().orElseThrow().fingerprint();
        String other = page.groups().stream().filter(g -> g.reports() == 1).findFirst().orElseThrow().fingerprint();
        UUID one = body(get(admin, BASE + "/" + same + "/occurrences").expectStatus().isOk(), ReportOccurrencePageView.class)
                .occurrences().getFirst().id();
        admin.delete().uri(BASE + "/occurrences/" + one).exchange().expectStatus().isNoContent();
        assertEquals(2, count());
        assertError(admin.delete().uri(BASE + "/occurrences/" + one).exchange(), 404, ReportAdminService.REPORT_NOT_FOUND);
        assertError(admin.delete().uri(BASE + "/occurrences/" + UUID.randomUUID()).exchange(), 404, ReportAdminService.REPORT_NOT_FOUND);
        admin.delete().uri(BASE + "/occurrences/not-an-id").exchange().expectStatus().isBadRequest();
        assertEquals(1, groups(admin, "?q=same").groups().getFirst().reports());
        admin.delete().uri(BASE + "/" + same).exchange().expectStatus().isNoContent();
        assertEquals(other, only(groups(admin, "")).fingerprint());
        assertError(admin.delete().uri(BASE + "/" + same).exchange(), 404, ReportAdminService.REPORT_NOT_FOUND);
        assertError(admin.delete().uri(BASE + "/nothing").exchange(), 404, ReportAdminService.REPORT_NOT_FOUND);
        assertEquals(1, count());
        // The account keeps sending afterwards: deleting reports changes no choice.
        assertTrue(me(user).diagnosticsOptIn());
    }
}
