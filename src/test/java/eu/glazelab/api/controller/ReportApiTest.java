package eu.glazelab.api.controller;

import eu.glazelab.api.service.AccountService;
import eu.glazelab.api.service.ReportService;
import eu.glazelab.api.security.CurrentUser;
import eu.glazelab.api.security.TermsFilter;
import eu.glazelab.api.support.ApiTestBase;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.test.web.servlet.client.RestTestClient.ResponseSpec;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in error reports (backend guide "Error reports"). */
class ReportApiTest extends ApiTestBase {
    private static final String TOKEN = "q7Lm2Xc9Vb4Nz8Kp1Rt6Yw3Hs5Jd0Fg-Ae_Ui7Oo9Pp";
    @Autowired ReportService service;

    /** A device as the app describes it. */
    static Map<String, Object> device() {
        var device = new HashMap<String, Object>();
        device.put("browser", "Chrome");
        device.put("browserVersion", "142.0.7444.59");
        device.put("os", "Android");
        device.put("osVersion", "16");
        device.put("screenWidth", 412);
        device.put("screenHeight", 915);
        device.put("viewportWidth", 412);
        device.put("viewportHeight", 780);
        device.put("pixelRatio", 2.625);
        device.put("pointer", "COARSE");
        device.put("language", "pt-PT");
        device.put("standalone", true);
        device.put("online", true);
        device.put("connection", "4g");
        device.put("serviceWorker", "ACTIVE");
        device.put("updatePending", false);
        device.put("reducedMotion", false);
        device.put("colorScheme", "DARK");
        device.put("timezoneOffsetMinutes", -60);
        device.put("deviceMemoryGb", 8.0);
        return device;
    }

    static Map<String, Object> event(String message) {
        var event = new HashMap<String, Object>();
        event.put("kind", "ERROR");
        event.put("message", message);
        event.put("stack", "TypeError: " + message + "\n    at render (https://glazelab.eu/assets/index-B1x2.js:1:2)");
        event.put("count", 2);
        event.put("occurredAt", Instant.now().minusSeconds(5).toString());
        event.put("build", "0.1.0 (src 1a2b3c4d5e)");
        event.put("route", "glaze");
        return event;
    }

    static Map<String, Object> batch(List<Map<String, Object>> events) {
        return Map.of("device", device(), "events", events);
    }

    static ResponseSpec send(RestTestClient client, Object body) {
        return client.post().uri("/api/reports").contentType(MediaType.APPLICATION_JSON).body(body).exchange();
    }

    static ResponseSpec choose(RestTestClient client, Object enabled) {
        var body = new HashMap<String, Object>();
        body.put("enabled", enabled);
        return client.put().uri("/api/account/diagnostics").body(body).exchange();
    }

    /** Opts in and checks the account's view says so. */
    static void optIn(RestTestClient client) {
        choose(client, true).expectStatus().isNoContent();
        assertTrue(me(client).diagnosticsOptIn());
    }

    private int reportsOf(UUID userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM client_report WHERE user_id = ?", Integer.class, userId);
    }

    @Test void nothingIsSentOrStoredUnlessTheAccountOptedIn() {
        var user = user("Quiet");
        UUID id = me(user).id();
        assertFalse(me(user).diagnosticsOptIn());
        assertError(send(user, batch(List.of(event("Boom")))), 409, ReportService.DIAGNOSTICS_OFF);
        assertEquals(0, reportsOf(id));
        // Guests never send: no account, nothing to link a report to.
        assertError(send(guest(), batch(List.of(event("Boom")))), 401, CurrentUser.SIGN_IN_REQUIRED);
        assertError(choose(guest(), true), 401, CurrentUser.SIGN_IN_REQUIRED);
        // Both writes need the CSRF token like every other write.
        guestWithoutCsrf().post().uri("/api/reports").contentType(MediaType.APPLICATION_JSON)
                .body(batch(List.of(event("Boom")))).exchange().expectStatus().isForbidden();
    }

    @Test void optInStoresScrubbedReportsWithTheDeviceAndOptOutDeletesThem() {
        var user = user("Helper");
        UUID id = me(user).id();
        optIn(user);
        var leaky = event("No account for ana@example.test at https://glazelab.eu/#/buddy/" + TOKEN + "?token=" + TOKEN);
        leaky.put("stack", "Error\n    at go (https://glazelab.eu/assets/a.js?v=" + TOKEN + ":1:2)\n    at #/invite/" + TOKEN);
        send(user, batch(List.of(leaky, event("Second")))).expectStatus().isNoContent();
        assertEquals(2, reportsOf(id));
        var row = jdbc.queryForMap("SELECT * FROM client_report WHERE user_id = ? AND message LIKE 'No account%'", id);
        assertEquals("No account for [email] at https://glazelab.eu/#[removed]", row.get("message"));
        String stack = (String) row.get("stack");
        assertFalse(stack.contains(TOKEN), stack);
        assertEquals("Error\n    at go (https://glazelab.eu/assets/a.js?[removed])\n    at #[removed]", stack);
        assertEquals("ERROR", row.get("kind"));
        assertEquals(2, row.get("occurrences"));
        assertEquals("0.1.0 (src 1a2b3c4d5e)", row.get("build_label"));
        assertEquals("glaze", row.get("route_name"));
        assertEquals("Chrome", row.get("browser"));
        assertEquals("142.0.7444.59", row.get("browser_version"));
        assertEquals("Android", row.get("os"));
        assertEquals(412, row.get("screen_width"));
        assertEquals(780, row.get("viewport_height"));
        assertEquals(2.625, row.get("pixel_ratio"));
        assertEquals("COARSE", row.get("pointer"));
        assertEquals("pt-PT", row.get("language"));
        assertEquals(true, row.get("standalone"));
        assertEquals("4g", row.get("connection_type"));
        assertEquals("ACTIVE", row.get("service_worker"));
        assertEquals("DARK", row.get("color_scheme"));
        assertEquals(-60, row.get("timezone_offset_minutes"));
        assertEquals(8.0, row.get("device_memory_gb"));
        assertEquals(64, ((String) row.get("fingerprint")).length());
        // Linked by the account ID only: no address, name or IP column exists.
        var columns = jdbc.queryForList("SELECT LOWER(column_name) FROM information_schema.columns WHERE LOWER(table_name) = 'client_report'", String.class);
        assertTrue(columns.stream().noneMatch(c -> c.contains("email") || c.contains("name") && !c.equals("route_name") || c.contains("ip") || c.contains("agent")), columns.toString());

        // Off: nothing more is stored and every stored report of the account is gone; on again starts empty.
        var other = user("OtherHelper");
        optIn(other);
        send(other, batch(List.of(event("Theirs")))).expectStatus().isNoContent();
        choose(user, false).expectStatus().isNoContent();
        assertFalse(me(user).diagnosticsOptIn());
        assertEquals(0, reportsOf(id));
        assertEquals(1, reportsOf(me(other).id()));
        assertError(send(user, batch(List.of(event("Late")))), 409, ReportService.DIAGNOSTICS_OFF);
        choose(user, false).expectStatus().isNoContent();
        optIn(user);
        assertEquals(0, reportsOf(id));
    }

    @Test void theChoiceFollowsTheAccountToEveryDevice() {
        String email = email("Devices");
        var phone = signUp(email);
        var laptop = guest();
        login(laptop, email, PASSWORD).expectStatus().isOk();
        optIn(phone);
        assertTrue(me(laptop).diagnosticsOptIn());
        send(laptop, batch(List.of(event("From the laptop")))).expectStatus().isNoContent();
        choose(laptop, false).expectStatus().isNoContent();
        assertError(send(phone, batch(List.of(event("From the phone")))), 409, ReportService.DIAGNOSTICS_OFF);
    }

    @Test void accountDeletionRemovesTheReports() {
        var user = user("Leaving");
        UUID id = me(user).id();
        optIn(user);
        send(user, batch(List.of(event("Before leaving")))).expectStatus().isNoContent();
        assertEquals(1, reportsOf(id));
        user.post().uri("/api/auth/delete").body(Map.of("password", PASSWORD)).exchange().expectStatus().isNoContent();
        assertEquals(0, reportsOf(id));
    }

    @Test void pendingTermsBlockSendingAndOptingInButNeverOptingOut() {
        var user = user("Terms");
        UUID id = me(user).id();
        optIn(user);
        send(user, batch(List.of(event("Kept")))).expectStatus().isNoContent();
        jdbc.update("UPDATE app_user SET accepted_terms_version = '2000-01-01.1' WHERE id = ?", id);
        assertError(send(user, batch(List.of(event("Blocked")))), 428, TermsFilter.TERMS_REQUIRED);
        // Withdrawing consent never waits for new terms, and deletes what was sent.
        choose(user, false).expectStatus().isNoContent();
        assertEquals(0, reportsOf(id));
        assertError(choose(user, true), 428, TermsFilter.TERMS_REQUIRED);
        assertFalse(me(user).diagnosticsOptIn());
        user.post().uri("/api/auth/terms").body(Map.of("termsVersion", AccountService.TERMS_VERSION)).exchange().expectStatus().isOk();
        optIn(user);
    }

    @Test void batchesAndEventsAreCappedAndValidated() {
        var user = user("Caps");
        UUID id = me(user).id();
        optIn(user);
        var many = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 21; i++) many.add(event("Many " + i));
        assertError(send(user, batch(many)), 400, ReportController.TOO_MANY_EVENTS);
        send(user, batch(many.subList(0, 20))).expectStatus().isNoContent();
        assertEquals(20, reportsOf(id));
        // An event is at most 4 KB as JSON (here: a 1000-character message and a 3000-character stack of 2-byte letters).
        var large = event("é".repeat(1000));
        large.put("stack", "é".repeat(3000));
        assertError(send(user, batch(List.of(large))), 400, ReportController.EVENT_TOO_LARGE);
        // The whole body is read only up to 32 KB.
        var padded = new HashMap<String, Object>(batch(List.of(event("Padded"))));
        padded.put("padding", "x".repeat(ReportController.MAX_BATCH_BYTES));
        assertError(send(user, padded), 413, ReportController.BATCH_TOO_LARGE);
        // Only plain device facts and a route name: anything shaped like a URL or free text is refused.
        var url = event("Route");
        url.put("route", "#/buddy/" + TOKEN);
        var device = device();
        device.put("language", "en-GB; ana@example.test");
        for (Object body : List.of(batch(List.of(url)), Map.of("device", device, "events", List.of(event("Device"))),
                batch(List.of()), Map.of("events", List.of(event("No device"))), "not json", "{\"device\":{},\"events\":[{\"kind\":0}]}",
                batch(List.of(Map.of("kind", "FATAL", "message", "x", "occurredAt", Instant.now().toString(), "build", "1")))))
            assertError(send(user, body), 400, eu.glazelab.api.exception.ApiErrorHandler.INVALID_INPUT);
        assertEquals(20, reportsOf(id));
        user.put().uri("/api/account/diagnostics").body(Map.of()).exchange().expectStatus().isBadRequest();
    }

    @Test void minimalEventsFarOffClocksAndTheDailyCap() {
        var user = user("Minimal");
        UUID id = me(user).id();
        optIn(user);
        var minimal = Map.<String, Object>of("kind", "UNHANDLED_REJECTION", "message", "Nope",
                "occurredAt", "1999-01-01T00:00:00Z", "build", "dev");
        send(user, Map.of("device", Map.of(), "events", List.of(minimal))).expectStatus().isNoContent();
        var row = jdbc.queryForMap("SELECT * FROM client_report WHERE user_id = ?", id);
        assertEquals(1, row.get("occurrences"));
        assertNull(row.get("stack"));
        assertNull(row.get("browser"));
        // A device clock far off is stored as the receive time.
        assertEquals(row.get("received_at"), row.get("occurred_at"));
        // Beyond the daily cap a batch is accepted and dropped (the app would only try again).
        jdbc.update("INSERT INTO client_report (id, user_id, received_at, occurred_at, kind, fingerprint, message, occurrences, build_label)"
                + " SELECT RANDOM_UUID(), user_id, received_at, occurred_at, kind, fingerprint, message, occurrences, build_label"
                + " FROM client_report, SYSTEM_RANGE(1, 499) WHERE user_id = ?", id);
        assertEquals(500, reportsOf(id));
        send(user, batch(List.of(event("Over the cap")))).expectStatus().isNoContent();
        assertEquals(500, reportsOf(id));
        // Yesterday's reports no longer count.
        jdbc.update("UPDATE client_report SET received_at = ? WHERE user_id = ?", java.sql.Timestamp.from(Instant.now().minus(25, ChronoUnit.HOURS)), id);
        send(user, batch(List.of(event("Next day")))).expectStatus().isNoContent();
        assertEquals(501, reportsOf(id));
    }

    @Test void thePurgeDeletesReportsOlderThanTheRetention() {
        var user = user("Purged");
        UUID id = me(user).id();
        optIn(user);
        send(user, batch(List.of(event("Old"), event("New")))).expectStatus().isNoContent();
        jdbc.update("UPDATE client_report SET received_at = ? WHERE user_id = ? AND message = 'Old'",
                java.sql.Timestamp.from(Instant.now().minus(30, ChronoUnit.DAYS).minusSeconds(60)), id);
        assertTrue(service.purge() >= 1);
        assertEquals(List.of("New"), jdbc.queryForList("SELECT message FROM client_report WHERE user_id = ?", String.class, id));
    }

    @Test void onlyTheActivationCarriesTheChoiceNeverTheRegistration() {
        // Mail-free here: an older client's choice at registration is ignored; the activation (the mailbox owner) sets it.
        String chosen = email("Chosen"), plain = email("Plain");
        var answer = registration(chosen, true);
        assertTrue(answer.startsWith("200 {\"verificationRequired\":true,\"setupToken\":\""), answer);
        assertEquals(Boolean.FALSE, flag(chosen));
        String token = answer.substring(answer.indexOf("\"setupToken\":\"") + 14, answer.length() - 2);
        var body = new HashMap<String, Object>(activation(token, PASSWORD, "Chooser"));
        body.put("diagnosticsOptIn", true);
        guest().post().uri("/api/auth/verification/confirm").body(body).exchange().expectStatus().isOk();
        assertEquals(Boolean.TRUE, flag(chosen));
        signUp(plain);
        assertEquals(Boolean.FALSE, flag(plain));
    }

    private String registration(String email, Boolean diagnostics) {
        var body = new HashMap<String, Object>(Map.of("termsAccepted", true, "termsVersion", AccountService.TERMS_VERSION,
                "email", email, "displayName", "Chooser"));
        if (diagnostics != null) body.put("diagnosticsOptIn", diagnostics);
        return statusAndBody(guest().post().uri("/api/auth/register").body(body).exchange());
    }

    private Boolean flag(String email) {
        return jdbc.queryForObject("SELECT diagnostics_opt_in FROM app_user WHERE email = ?", Boolean.class, email);
    }
}
