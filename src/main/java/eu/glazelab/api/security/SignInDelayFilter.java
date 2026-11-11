package eu.glazelab.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/**
 * The sign-in delay per address ({@link SignInDelays}) in front of the form sign-in, after CSRF and the per-IP AUTH
 * budget: a delayed address answers 429 with Retry-After before any password is compared.
 */
@RequiredArgsConstructor
class SignInDelayFilter extends OncePerRequestFilter {
    static final String LOGIN = "/api/auth/login";
    private final SignInDelays delays;

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        if (delays.enabled() && request.getMethod().equals("POST")
                && UrlPathHelper.defaultInstance.getPathWithinApplication(request).equals(LOGIN)) {
            long wait = delays.attempt(request.getParameter("email"));
            if (wait > 0) {
                response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(wait));
                SecurityConfig.error(response, HttpStatus.TOO_MANY_REQUESTS.value(), SignInDelays.DELAYED);
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
