package eu.glazelab.api.config;

import eu.glazelab.api.dto.CommonDtos.ErrorView;
import eu.glazelab.api.security.SecurityConfig;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem.HttpMethod;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Documents the {@link ErrorView} body of the error answers. The {@code @ExceptionHandler} methods and the
 * security filters carry no springdoc annotations, so the statuses are derived from the shape of each
 * operation and from the security rules; an operation's own responses are never replaced.
 */
final class ErrorResponses {
    /** Mail operations: 503 when e-mail cannot be sent (image uploads, being multipart, answer 503 as well). */
    private static final Set<String> MAIL = Set.of("/api/auth/register", "/api/auth/password/request",
            "/api/auth/verification/request", "/api/auth/magic/request");
    /**
     * Look up a token or an account from the body: an inactive, replaced or unknown invite or buddy link is 404, and so
     * is a buddy request to someone who is not a member of the club.
     */
    private static final Set<String> BODY_LOOKUPS = Set.of("/api/clubs/invitations/preview", "/api/clubs/join",
            "/api/buddies/link/preview", "/api/buddies/link/request", "/api/buddies/requests");
    /** Club reads that check the membership of any ID (403), never whether the club exists. */
    private static final Set<String> MEMBERSHIP_READS = Set.of("/api/clubs/{id}/members", "/api/clubs/{id}/wishlist");
    /** Answers 404 while the operator address is not configured. */
    private static final String OPERATOR_CONTACT = "/api/auth/operator-contact";
    private static final Map<String, String> DESCRIPTIONS = Map.of(
            "400", "Invalid input", "401", "Sign-in required", "403", "Forbidden or CSRF token missing",
            "404", "Not found", "409", "Conflict with the current state", "413", "Body too large",
            "428", "Updated terms must be accepted", "429", "Too many requests", "503", "Service unavailable");
    private static final List<PathPattern> PUBLIC_GET = patterns(SecurityConfig.PUBLIC_GET);
    private static final List<PathPattern> PUBLIC_POST = patterns(SecurityConfig.PUBLIC_POST);

    private ErrorResponses() {}

    static void document(OpenAPI api) {
        Schema<?> error = ModelConverters.getInstance().resolveAsResolvedSchema(new AnnotatedType(ErrorView.class)).schema;
        api.getComponents().addSchemas("ErrorView", error);
        var body = new Content().addMediaType("application/json",
                new MediaType().schema(new Schema<>().$ref("#/components/schemas/ErrorView")));
        api.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
            var responses = operation.getResponses();
            for (String status : statuses(method, path, operation))
                if (!responses.containsKey(status))
                    responses.addApiResponse(status, new ApiResponse().description(DESCRIPTIONS.get(status)).content(body));
        }));
    }

    /** Ascending, so the generated JSON is stable. */
    static Set<String> statuses(HttpMethod method, String path, Operation operation) {
        var found = new TreeSet<String>();
        boolean write = method != HttpMethod.GET;
        boolean auth = path.startsWith("/api/auth/");
        boolean body = operation.getRequestBody() != null;
        boolean multipart = body && operation.getRequestBody().getContent().containsKey("multipart/form-data");
        boolean pathParameter = path.contains("{");
        boolean publicOperation = matches(write ? PUBLIC_POST : PUBLIC_GET, path);
        // The sign-in and sign-out operations are answered by the security chain: only CSRF and the rate limit apply.
        if (path.equals("/api/auth/login") || path.equals("/api/auth/logout")) {
            found.add("403");
            found.add("429");
            return found;
        }
        if (body || pathParameter || operation.getParameters() != null) found.add("400");
        if (!publicOperation || path.equals("/api/auth/me")) found.add("401");
        // Reads answer 403 only where a role is checked (site admin, club admin); writes also meet the CSRF check.
        if (write || (!publicOperation && (pathParameter || path.startsWith("/api/admin/")))) found.add("403");
        // The club member list and wishlist check the membership of any ID (403), never whether the club exists.
        if ((pathParameter && (write || !MEMBERSHIP_READS.contains(path))) || BODY_LOOKUPS.contains(path) || path.equals(OPERATOR_CONTACT))
            found.add("404");
        if (write) found.add("409");
        // Every body has a cap: multipart 12 MB, any other 128 KB (security.BodySizeFilter), error reports 32 KB.
        if (body) found.add("413");
        if (write && !auth) found.add("428");
        // Never limited (RateLimiter.kind).
        if (!path.equals("/api/health") && !path.equals("/api/auth/clear-cache")) found.add("429");
        if (multipart || MAIL.contains(path)) found.add("503");
        return found;
    }

    private static boolean matches(List<PathPattern> patterns, String path) {
        PathContainer container = PathContainer.parsePath(path);
        return patterns.stream().anyMatch(pattern -> pattern.matches(container));
    }

    private static List<PathPattern> patterns(String[] paths) {
        return Arrays.stream(paths).map(PathPatternParser.defaultInstance::parse).toList();
    }
}
