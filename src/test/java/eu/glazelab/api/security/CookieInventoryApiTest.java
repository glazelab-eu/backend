package eu.glazelab.api.security;

import eu.glazelab.api.support.ApiTestBase;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cookie inventory (23-72): the service sets only strictly necessary cookies, and the terms of service say which
 * ({@code terms.section.cookies}). A new cookie needs a decision and a terms change; this test names the allowed ones and
 * their flags, and the browser e2e ({@code cookies.spec.ts}) checks the same in a real browser.
 */
class CookieInventoryApiTest extends ApiTestBase {
    private static final String CSRF_COOKIE = "XSRF-TOKEN";

    /** Every Set-Cookie header the client's responses carried, in order. */
    private final List<String> setCookies = new ArrayList<>();

    /** A browser with a cookie jar and the SPA's CSRF handling that also writes down every Set-Cookie header. */
    private RestTestClient recordingBrowser() {
        ClientHttpRequestInterceptor recorder = (request, body, execution) -> {
            var response = execution.execute(request, body);
            setCookies.addAll(response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE));
            return response;
        };
        var http = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        var requests = new JdkClientHttpRequestFactory(http);
        String origin = "http://localhost:" + port;
        RestClient tokens = RestClient.builder().requestFactory(requests).baseUrl(origin).requestInterceptor(recorder).build();
        return RestTestClient.bindToServer(requests).baseUrl(origin).requestInterceptor(recorder)
                .requestInterceptor((request, body, execution) -> {
                    if (request.getMethod() != HttpMethod.GET && request.getHeaders().getFirst(CSRF_HEADER) == null)
                        request.getHeaders().set(CSRF_HEADER, tokens.get().uri("/api/auth/csrf").retrieve().body(Map.class).get("token").toString());
                    return execution.execute(request, body);
                }).build();
    }

    private static String name(String setCookie) { return setCookie.substring(0, setCookie.indexOf('=')); }
    private List<String> named(String cookie) { return setCookies.stream().filter(header -> name(header).equals(cookie)).toList(); }

    @Test void aGuestReadingTheServiceReceivesNoCookie() {
        RestTestClient guest = recordingBrowser();
        guest.get().uri("/api/health").exchange().expectStatus().isOk();
        guest.get().uri("/api/auth/options").exchange().expectStatus().isOk();
        guest.get().uri("/api/auth/me").exchange().expectStatus().isUnauthorized();
        guest.get().uri("/api/auth/clear-cache").exchange().expectStatus().isNoContent();
        guest.get().uri("/api/glazes").exchange().expectStatus().isOk();
        guest.get().uri("/api/combinations").exchange().expectStatus().isOk();
        guest.get().uri("/api/stock").exchange().expectStatus().isUnauthorized();
        guest.get().uri("/api/sync/glazes").exchange();
        guest.get().uri("/login").exchange().expectStatus().isUnauthorized();
        guest.get().uri("/api/openapi").exchange();
        assertThat(setCookies).as("Set-Cookie headers of a guest's reads").isEmpty();
    }

    @Test void aMembersJourneyOnlyEverSetsTheSignInAndTheCsrfCookie() {
        RestTestClient browser = recordingBrowser();
        String email = email("Inventory");
        activate(browser, setupToken(register(browser, email, "Inventory")), PASSWORD, "Inventory").expectStatus().isOk();
        login(browser, email, "wrong").expectStatus().isUnauthorized();
        login(browser, email, PASSWORD).expectStatus().isOk();
        UUID glaze = addGlaze(browser, "Cookie Maker", "Inventory glaze", "IG-1");
        UUID combination = addCombination(browser, "Inventory combination", glaze);
        UUID image = uploadTile(browser, combination, "PUBLIC", null);
        browser.get().uri("/api/images/{id}", image).exchange().expectStatus().isOk();
        browser.post().uri("/api/auth/password/change").body(Map.of("currentPassword", PASSWORD, "newPassword", "another secret 456"))
                .exchange().expectStatus().isOk();
        browser.post().uri("/api/auth/logout").exchange().expectStatus().isOk();

        assertThat(setCookies).isNotEmpty();
        assertThat(setCookies.stream().map(CookieInventoryApiTest::name).distinct())
                .as("cookie names set along the journey (no JSESSIONID, no locale, remember-me or tracking cookie)")
                .containsOnly(LoginSessions.COOKIE, CSRF_COOKIE);

        // The sign-in cookie: a persistent HttpOnly SameSite=Lax cookie for the whole site (Secure follows the profile), kept
        // by the browser for the absolute lifetime of a sign-in (365 days); the server ends it after 30 idle days.
        for (String header : named(LoginSessions.COOKIE)) {
            assertThat(header).contains("; Path=/", "; HttpOnly", "; SameSite=Lax");
            if (!header.startsWith(LoginSessions.COOKIE + "=;")) assertThat(header).contains("; Max-Age=31536000");
            else assertThat(header).contains("; Max-Age=0");
        }
        // The CSRF cookie: a browser-session cookie (no lifetime) that is HttpOnly and SameSite=Lax; a deletion carries an
        // expiry in the past.
        assertThat(named(CSRF_COOKIE)).isNotEmpty().allSatisfy(header -> {
            assertThat(header).contains("; Path=/", "; HttpOnly", "; SameSite=Lax").doesNotContain("Max-Age");
            if (header.startsWith(CSRF_COOKIE + "=;")) assertThat(header).contains("Expires=Thu, 01 Jan 1970");
            else assertThat(header).doesNotContain("Expires");
        });
    }
}
