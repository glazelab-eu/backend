package eu.glazelab.api.controller;

import eu.glazelab.api.support.ApiTestBase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.test.json.JsonCompareMode;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class OpenApiContractTest extends ApiTestBase {
    // Absent in the backend-only Docker build context.
    private static final Path SNAPSHOT = Path.of("../frontend/openapi.json");

    @Autowired Environment environment;

    @Test void devIsTheDefaultProfile() {
        assertThat(environment.getActiveProfiles()).isEmpty();
        assertThat(environment.getDefaultProfiles()).containsExactly("dev");
    }

    @Test void runningContractMatchesFrontendSnapshot() throws Exception {
        assumeTrue(Files.exists(SNAPSHOT), "frontend/openapi.json is not available");
        // On failure, regenerate the snapshot and the client (docs/guide/contract.md).
        guest().get().uri("/api/openapi").exchange().expectStatus().isOk()
                .expectBody().json(Files.readString(SNAPSHOT), JsonCompareMode.STRICT);
    }

    @Test void errorBodyIsDocumentedOnTheOperationsThatAnswerIt() {
        var spec = JsonMapper.builder().build().readTree(guest().get().uri("/api/openapi").exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody());
        var error = spec.at("/components/schemas/ErrorView");
        assertThat(error.at("/properties/error/type").asString()).isEqualTo("string");
        assertThat(error.at("/required/0").asString()).isEqualTo("error");
        // A signed-in write can fail in every layer: validation, session, CSRF, lookup, state, body size, terms, rate limit.
        assertThat(statuses(spec, "/api/combinations/{id}", "put"))
                .containsExactly("204", "400", "401", "403", "404", "409", "413", "428", "429");
        assertThat(spec.at("/paths/~1api~1stock/post/responses/428/content/application~1json/schema/$ref").asString())
                .isEqualTo("#/components/schemas/ErrorView");
        // Uploads add the size and encoder limits; the success schema stays untouched.
        assertThat(statuses(spec, "/api/glazes/{id}/images", "post"))
                .containsExactly("200", "400", "401", "403", "404", "409", "413", "428", "429", "503");
        assertThat(spec.at("/paths/~1api~1glazes~1{id}~1images/post/responses/200/content/*~1*/schema/$ref").asString())
                .isEqualTo("#/components/schemas/ImageView");
        // Public and security-chain operations claim only what they can answer.
        assertThat(statuses(spec, "/api/health", "get")).containsExactly("200");
        assertThat(statuses(spec, "/api/glazes", "get")).containsExactly("200", "429");
        assertThat(statuses(spec, "/api/auth/logout", "post")).containsExactly("200", "403", "429");
        assertThat(statuses(spec, "/api/auth/me", "get")).containsExactly("200", "401", "429");
        // 404 only where an ID in the path or an invite token in the body is looked up; 403 on a read only where a
        // role is checked (writes also meet the CSRF check).
        assertThat(statuses(spec, "/api/stock", "get")).containsExactly("200", "401", "429");
        assertThat(statuses(spec, "/api/stock", "post")).containsExactly("200", "400", "401", "403", "409", "413", "428", "429");
        assertThat(statuses(spec, "/api/stock/add", "post")).containsExactly("200", "400", "401", "403", "409", "413", "428", "429");
        assertThat(statuses(spec, "/api/clubs", "post")).doesNotContain("404");
        assertThat(statuses(spec, "/api/glazes", "post")).doesNotContain("404");
        assertThat(statuses(spec, "/api/combinations", "post")).doesNotContain("404");
        assertThat(statuses(spec, "/api/clubs/join", "post")).contains("404");
        assertThat(statuses(spec, "/api/clubs/invitations/preview", "post")).contains("404");
        assertThat(statuses(spec, "/api/buddies/link/preview", "post")).contains("404");
        assertThat(statuses(spec, "/api/buddies/link/request", "post")).contains("404");
        assertThat(statuses(spec, "/api/buddies/requests", "post")).contains("403", "404");
        assertThat(statuses(spec, "/api/buddies/link", "post")).containsExactly("200", "401", "403", "409", "428", "429");
        assertThat(statuses(spec, "/api/buddies/availability", "get")).containsExactly("200", "401", "429");
        assertThat(statuses(spec, "/api/buddies", "get")).containsExactly("200", "401", "429");
        assertThat(statuses(spec, "/api/clubs/{id}/members", "get")).containsExactly("200", "400", "401", "403", "429");
        // Members join only through an invite link: there is no operation that adds one by address.
        assertThat(spec.at("/paths/~1api~1clubs~1{id}~1members/post").isMissingNode()).isTrue();
        assertThat(statuses(spec, "/api/admin/image-deletions", "get")).containsExactly("200", "400", "401", "403", "429");
        // Profile pictures: signed-in only, the upload is a multipart write of the caller's own picture (no ID to miss).
        assertThat(statuses(spec, "/api/avatars", "post")).containsExactly("200", "400", "401", "403", "409", "413", "428", "429", "503");
        assertThat(statuses(spec, "/api/avatars/{id}", "get")).contains("401", "404").doesNotContain("428");
        assertThat(statuses(spec, "/api/avatars/{id}/small", "get")).contains("401", "404");
        assertThat(statuses(spec, "/api/avatars/{id}", "delete")).contains("401", "403", "404", "428");
        assertThat(statuses(spec, "/api/avatars/{id}/reports", "post")).contains("400", "401", "404", "428");
        assertThat(statuses(spec, "/api/combinations/author-avatars", "get")).containsExactly("200", "401", "429");
        assertThat(statuses(spec, "/api/admin/avatar-reports", "get")).containsExactly("200", "400", "401", "403", "429");
        assertThat(statuses(spec, "/api/admin/avatar-reports/{id}/dismiss", "post")).contains("401", "403", "404", "409");
        assertThat(statuses(spec, "/api/admin/avatar-reports/{id}/remove", "post")).contains("401", "403", "404", "409", "428");
        assertThat(statuses(spec, "/api/admin/avatar-reports/{id}/picture", "get")).containsExactly("200", "400", "401", "403", "404", "429");
        assertThat(spec.at("/components/schemas/AvatarReportView/properties/status/enum").toString())
                .isEqualTo("[\"OPEN\",\"DISMISSED\",\"ACTIONED\",\"CLOSED\"]");
        assertThat(spec.at("/components/schemas/AvatarReportView/properties/picture/enum").toString())
                .isEqualTo("[\"CURRENT\",\"REPLACED\",\"OWNER_REMOVED\",\"ADMIN_REMOVED\",\"ACCOUNT_DELETED\"]");
        // Activity feed: a signed-in paged read and two signed-in writes; the type is a shared string enum.
        assertThat(statuses(spec, "/api/feed", "get")).containsExactly("200", "400", "401", "429");
        assertThat(statuses(spec, "/api/feed/unread", "get")).containsExactly("200", "401", "429");
        assertThat(statuses(spec, "/api/feed/seen", "post")).containsExactly("204", "400", "401", "403", "409", "413", "428", "429");
        assertThat(statuses(spec, "/api/account/feed", "put")).containsExactly("204", "400", "401", "403", "409", "413", "428", "429");
        assertThat(spec.at("/components/schemas/FeedEntryView/properties/type/enum").toString()).isEqualTo(
                "[\"BUDDY_COMBINATION\",\"BUDDY_GLAZE\",\"NEW_BUDDY\",\"BUDDY_REQUEST\",\"CLUB_COMBINATION\",\"CLUB_MEMBER\",\"CLUB_GLAZE\"]");
        // Bans: site admins only; the ban is a write naming an account, the list a paged read, the unban a write naming an entry.
        assertThat(statuses(spec, "/api/admin/users/{id}/ban", "post")).containsExactly("204", "400", "401", "403", "404", "409", "413", "428", "429");
        assertThat(statuses(spec, "/api/admin/bans", "get")).containsExactly("200", "400", "401", "403", "429");
        assertThat(statuses(spec, "/api/admin/bans/{id}", "delete")).containsExactly("204", "400", "401", "403", "404", "409", "428", "429");
        assertThat(spec.at("/components/schemas/BanInput/required").toString()).isEqualTo("[\"reason\"]");
        assertThat(spec.at("/components/schemas/BanView/properties").propertyNames())
                .containsExactlyInAnyOrder("id", "reason", "bannedAt");
        // Error reports: the switch is a plain write (also answering 428 when turning reports on with pending terms);
        // a batch is a capped JSON body (413) of an opted-in account (409 otherwise).
        assertThat(statuses(spec, "/api/account/diagnostics", "put")).containsExactly("204", "400", "401", "403", "409", "413", "428", "429");
        assertThat(statuses(spec, "/api/admin/error-reports", "get")).containsExactly("200", "400", "401", "403", "429");
        assertThat(statuses(spec, "/api/admin/error-reports/builds", "get")).containsExactly("200", "401", "403", "429");
        assertThat(statuses(spec, "/api/admin/error-reports/{fingerprint}/occurrences", "get")).containsExactly("200", "400", "401", "403", "404", "429");
        assertThat(statuses(spec, "/api/admin/error-reports/{fingerprint}", "delete")).containsExactly("204", "400", "401", "403", "404", "409", "428", "429");
        assertThat(statuses(spec, "/api/admin/error-reports/occurrences/{id}", "delete")).containsExactly("204", "400", "401", "403", "404", "409", "428", "429");
        assertThat(statuses(spec, "/api/reports", "post")).containsExactly("204", "400", "401", "403", "409", "413", "428", "429");
        assertThat(spec.at("/paths/~1api~1reports/post/requestBody/content/application~1json/schema/$ref").asString())
                .isEqualTo("#/components/schemas/ReportBatch");
        assertThat(spec.at("/components/schemas/ReportEvent/properties/kind/enum").toString())
                .isEqualTo("[\"ERROR\",\"WARNING\",\"UNHANDLED_REJECTION\"]");
        assertThat(spec.at("/components/schemas/ReportBatch/properties/events/maxItems").asInt()).isEqualTo(20);
        assertThat(spec.at("/components/schemas/UserView/required").toString()).contains("\"diagnosticsOptIn\"");
        assertThat(spec.at("/paths/~1api~1auth~1login/post/responses/401/description").asString())
                .isEqualTo("Invalid credentials");
        // An operation's own error response is kept.
        assertThat(spec.at("/paths/~1api~1glazes~1{id}/delete/responses/409/content/*~1*/schema/$ref").asString())
                .isEqualTo("#/components/schemas/GlazeInUseView");
    }

    @Test void registrationTakesNoPasswordAndAnswersOnlyAnOptionalSetupToken() {
        var spec = JsonMapper.builder().build().readTree(guest().get().uri("/api/openapi").exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody());
        var input = spec.at("/components/schemas/Register/properties");
        assertThat(input.propertyNames()).containsExactlyInAnyOrder("email", "displayName");
        // Consent comes with the activation, on each of the three account links that can activate a pending account.
        for (String link : new String[] {"ConfirmationInput", "ResetInput"})
            assertThat(spec.at("/components/schemas/" + link + "/properties").propertyNames())
                    .contains("termsAccepted", "termsVersion", "diagnosticsOptIn");
        var answer = spec.at("/components/schemas/RegistrationView");
        assertThat(answer.at("/properties").propertyNames()).containsExactlyInAnyOrder("verificationRequired", "setupToken");
        assertThat(answer.at("/properties/setupToken/type").toString()).isEqualTo("[\"string\",\"null\"]");
        assertThat(answer.at("/required").toString()).isEqualTo("[\"setupToken\",\"verificationRequired\"]");
    }

    private static List<String> statuses(JsonNode spec, String path, String method) {
        return spec.at("/paths").get(path).get(method).get("responses").propertyNames().stream().toList();
    }
}
