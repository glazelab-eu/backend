package eu.glazelab.api.security;

import eu.glazelab.api.repository.AppUserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

// Registered only in Spring Security's chain, not as a second servlet filter.
@RequiredArgsConstructor
public class SessionVersionFilter extends OncePerRequestFilter {
    private final AppUserRepository users;
    private final LoginSessions sessions;
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AccountPrincipal principal) {
            var account = users.findById(principal.getUserId()).filter(user -> user.isEmailVerified()).orElse(null);
            if (account == null || account.getSessionVersion() != principal.getSessionVersion()) {
                // This request is refused as signed out and its stored session ends; a sign-in made meanwhile by a
                // concurrent request of the same browser has its own token and stays.
                request.removeAttribute(CurrentUser.VALIDATED_USER);
                SecurityContextHolder.clearContext();
                sessions.endStale(request);
            } else {
                request.setAttribute(CurrentUser.VALIDATED_USER, account);
                sessions.renew(request);
            }
        }
        chain.doFilter(request, response);
    }
}
