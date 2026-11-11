package eu.glazelab.api.security;

import eu.glazelab.api.service.AccountService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/**
 * Refuses writes (428) from accounts that have not accepted the current terms of service, including
 * accounts from before V9 (NULL). Account requests under /api/auth/ (accepting the terms, sign-out,
 * recovery) stay open, and so does {@code DELETE /api/avatars/{id}}: removing one's own profile picture must not
 * need new terms, and {@code AvatarService.remove} applies the terms check itself to anyone else's removal (site
 * admins), and {@code PUT /api/account/diagnostics}: turning error reports off must not need new terms either, and
 * {@code ReportService.choose} applies the terms check itself to turning them on. Paths are compared decoded, as
 * Spring MVC matches them. Runs after CSRF and session checks; registered
 * only in Spring Security's chain.
 */
public class TermsFilter extends OncePerRequestFilter {
    public static final String TERMS_REQUIRED = "Please accept the updated terms of service to continue.";
    private static final Set<String> READS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
    private static final Pattern AVATAR = Pattern.compile("/api/avatars/[^/]+");
    private static final String DIAGNOSTICS = "/api/account/diagnostics";

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        if (auth != null && auth.getPrincipal() instanceof AccountPrincipal principal
                && !READS.contains(request.getMethod()) && !path.startsWith("/api/auth/") && !avatarRemoval(request.getMethod(), path)
                && !diagnosticsChoice(request.getMethod(), path)) {
            var user = CurrentUser.validatedUser(request, principal.getUserId());
            if (user == null) {
                SecurityConfig.error(response, 401, CurrentUser.SIGN_IN_REQUIRED);
                return;
            }
            if (!AccountService.TERMS_VERSION.equals(user.getAcceptedTermsVersion())) {
                SecurityConfig.error(response, 428, TERMS_REQUIRED);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private static boolean diagnosticsChoice(String method, String path) {
        return "PUT".equals(method) && DIAGNOSTICS.equals(path);
    }

    private static boolean avatarRemoval(String method, String path) {
        return "DELETE".equals(method) && AVATAR.matcher(path).matches();
    }
}
