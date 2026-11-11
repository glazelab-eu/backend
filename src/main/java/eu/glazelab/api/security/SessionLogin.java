package eu.glazelab.api.security;

import eu.glazelab.api.model.AppUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.stereotype.Component;

/**
 * Signs a request in or out outside Spring Security's form login (magic link, password change, account deletion)
 * the way form login does: a new CSRF token, and the context saved through {@link LoginSessions}, which issues a new
 * session token (never keeping the one the request came with) or ends the request's session and clears its cookie.
 */
@Component
public class SessionLogin {
    private final SecurityContextRepository contexts;
    private final CsrfAuthenticationStrategy csrf;
    public SessionLogin(SecurityContextRepository contexts, CsrfTokenRepository csrf) {
        this.contexts = contexts;
        this.csrf = new CsrfAuthenticationStrategy(csrf);
    }
    public void signIn(AppUser account, HttpServletRequest request, HttpServletResponse response) {
        var principal = new AccountPrincipal(account);
        principal.eraseCredentials();
        var authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        csrf.onAuthentication(authentication, request, response);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
    }
    /** Account deletion's sign-out: ends the request's session and clears its cookie, nothing else (LOGOUT-2). */
    public void signOut(HttpServletRequest request, HttpServletResponse response) {
        SecurityContextHolder.clearContext();
        contexts.saveContext(SecurityContextHolder.createEmptyContext(), request, response);
    }
}
