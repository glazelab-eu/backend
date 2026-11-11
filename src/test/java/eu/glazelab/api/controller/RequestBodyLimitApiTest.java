package eu.glazelab.api.controller;

import eu.glazelab.api.security.BodySizeFilter;
import eu.glazelab.api.support.ApiTestBase;
import eu.glazelab.api.support.TestImages;
import java.io.ByteArrayInputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/** BE-1: a JSON body above 128 KB is refused (413) before anything parses it; the largest real bodies still pass. */
class RequestBodyLimitApiTest extends ApiTestBase {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** {"title":"T","steps":[{},{},…]} of at least {@code bytes} bytes: cheap to send, costly to bind and validate. */
    private static String emptySteps(int bytes) {
        var body = new StringBuilder("{\"title\":\"T\",\"steps\":[{}");
        while (body.length() < bytes) body.append(",{}");
        return body.append("]}").toString();
    }

    /** {@code length} characters of a three-byte UTF-8 character, the widest text a person types in these fields. */
    private static String wide(int length) {
        return "陶".repeat(length);
    }

    @Test void theLargestRealCombinationStillPasses() {
        RestTestClient ada = user("Ada");
        var steps = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 30; i++) steps.add(Map.of("type", "CUSTOM", "label", wide(80), "notes", wide(500)));
        var tags = new ArrayList<String>();
        for (int i = 0; i < 20; i++) tags.add("t" + i + "x".repeat(37));
        var body = new HashMap<String, Object>(Map.of("title", wide(160), "description", wide(2000), "steps", steps,
                "tags", tags, "clayBody", wide(120), "kiln", wide(80), "cone", "6"));
        byte[] json = JSON.writeValueAsBytes(body);
        assertTrue(json.length > 48 * 1024 && json.length < BodySizeFilter.MAX_BYTES, "body of " + json.length + " bytes");
        created(ada.post().uri("/api/combinations").contentType(MediaType.APPLICATION_JSON).body(json).exchange());
    }

    @Test void aBodyOverTheCapIsRefusedAtOnce() {
        RestTestClient ada = user("Bea");
        String justOver = emptySteps(BodySizeFilter.MAX_BYTES + 1);
        long start = System.nanoTime();
        assertError(ada.post().uri("/api/combinations").contentType(MediaType.APPLICATION_JSON).body(justOver).exchange(),
                413, BodySizeFilter.TOO_LARGE);
        // Megabytes that the proxy would also refuse: still no parsing, so well under a second.
        assertError(ada.post().uri("/api/combinations").contentType(MediaType.APPLICATION_JSON)
                .body(emptySteps(12 * 1024 * 1024)).exchange(), 413, BodySizeFilter.TOO_LARGE);
        assertTrue(System.nanoTime() - start < 5_000_000_000L, "took " + (System.nanoTime() - start) / 1_000_000 + " ms");
        // A body under the cap with more than 30 steps is refused by the list bound, not after validating each step.
        assertError(ada.post().uri("/api/combinations").contentType(MediaType.APPLICATION_JSON)
                .body(emptySteps(BodySizeFilter.MAX_BYTES - 100)).exchange(), 400, "steps: Maximum 30 steps");
        assertError(ada.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("email=a%40b.c&password=" + "x".repeat(BodySizeFilter.MAX_BYTES)).exchange(), 413, BodySizeFilter.TOO_LARGE);
    }

    @Test void aChunkedBodyIsCutAtTheCap() throws Exception {
        var http = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String base = "http://localhost:" + port;
        var token = http.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/csrf")).build(), HttpResponse.BodyHandlers.ofString());
        String csrf = JSON.readTree(token.body()).get("token").asString();
        byte[] big = ("{\"email\":\"" + "a".repeat(BodySizeFilter.MAX_BYTES) + "@example.com\"}").getBytes(StandardCharsets.UTF_8);
        // An input stream publisher has no length: the request goes chunked, without Content-Length.
        var request = HttpRequest.newBuilder(URI.create(base + "/api/auth/register")).header(CSRF_HEADER, csrf)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(big))).build();
        var answer = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(413, answer.statusCode(), answer.body());
        assertEquals(BodySizeFilter.TOO_LARGE, JSON.readTree(answer.body()).get("error").asString());
    }

    @Test void uploadsAboveTheJsonCapStillPass() {
        RestTestClient ada = user("Cy");
        UUID glaze = addGlaze(ada, unique("Upload"), "Shino", "PC-41");
        UUID combination = addCombination(ada, unique("Large upload"), glaze);
        byte[] photo = TestImages.photo(1200, 900);
        assertTrue(photo.length > BodySizeFilter.MAX_BYTES, "photo of " + photo.length + " bytes");
        created(upload(ada, combination, Map.of("visibility", "PUBLIC", "kind", "TEST_TILE"), photo));
        sendFeedback(ada, "Large photo", Map.of(), photo).expectStatus().isOk();
    }
}
