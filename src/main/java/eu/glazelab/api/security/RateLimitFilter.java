package eu.glazelab.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.springframework.web.util.UrlPathHelper;

/**
 * Applies {@link RateLimiter} budgets before sign-in or any other work: over the limit answers 429 with
 * Retry-After and a JSON error. A flagged client occasionally gets the {@link Tarpit} decoy instead of an unknown
 * picture's 404, and only there: JSON answers and pictures that exist are never replaced, so a client flagged by
 * mistake loses nothing it could have loaded.
 * The client address is the real one: in prod Tomcat's RemoteIpValve takes it from trusted proxies only.
 * Requests are classified by their decoded path (the one Spring MVC matches), so percent-encoding cannot move a
 * request into a looser budget. Registered only in Spring Security's chain, not as a second servlet filter.
 */
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {
    public static final String TOO_MANY = "Too many requests. Please wait a moment and try again.";
    private static final String HIDDEN_PICTURE = RateLimitFilter.class.getName() + ".hiddenPicture";

    private final RateLimiter limiter;
    private final Tarpit tarpit;

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        RateLimiter.Kind kind = limiter.enabled() ? RateLimiter.kind(request.getMethod(), UrlPathHelper.defaultInstance.getPathWithinApplication(request)) : null;
        if (kind == null) {
            chain.doFilter(request, response);
            return;
        }
        var auth = SecurityContextHolder.getContext().getAuthentication();
        UUID userId = auth != null && auth.getPrincipal() instanceof AccountPrincipal principal ? principal.getUserId() : null;
        String key = RateLimiter.clientKey(request.getRemoteAddr(), RateLimiter.perAddress(kind) ? null : userId);
        // Drawn before the budget is taken, so a flagged client's chance of a decoy does not depend on its pace.
        boolean decoy = kind == RateLimiter.Kind.IMAGES && limiter.tarpitCandidate(key, request.getMethod());
        long retryAfter = limiter.take(key, kind, userId != null);
        if (retryAfter > 0) {
            response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
            SecurityConfig.error(response, HttpStatus.TOO_MANY_REQUESTS.value(), TOO_MANY);
            return;
        }
        if (!decoy) {
            chain.doFilter(request, response);
            if (kind == RateLimiter.Kind.IMAGES && unknownPicture(request, response.getStatus())) limiter.unknownImage(key);
            return;
        }
        // The answer is held back so an unknown picture's 404 can still become the decoy; anything else goes out unchanged.
        var held = new ContentCachingResponseWrapper(response);
        chain.doFilter(request, held);
        if (unknownPicture(request, held.getStatus())) {
            limiter.unknownImage(key);
            if (!response.isCommitted() && tarpit.start(request, response)) return;
        }
        held.copyBodyToResponse();
    }

    /**
     * Marks the current request's 404 as a picture that exists but is hidden from the caller: a stale listing (a club
     * left, a picture made private) asks for those, enumeration does not, so they never count towards the tarpit.
     */
    public static void hiddenPicture() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)
            attributes.getRequest().setAttribute(HIDDEN_PICTURE, Boolean.TRUE);
    }

    /** A 404 for an ID no picture has (or a malformed ID): listings never link to one, so it hints at enumeration. */
    private static boolean unknownPicture(HttpServletRequest request, int status) {
        return status == 400 || status == 404 && request.getAttribute(HIDDEN_PICTURE) == null;
    }
}
