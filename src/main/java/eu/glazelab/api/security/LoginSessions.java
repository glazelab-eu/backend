package eu.glazelab.api.security;

import eu.glazelab.api.model.LoginSession;
import eu.glazelab.api.repository.LoginSessionRepository;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.DeferredSecurityContext;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpRequestResponseHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

/**
 * The store of sign-ins, in the database so they survive API restarts and deployments, behind a persistent cookie so
 * they survive browser restarts. The cookie holds a random token ({@link SecureTokens}); the row only its hash, the
 * account and the session version, never anything about the device. A sign-in ({@link #saveContext} with an account)
 * always issues a new token and ends the one the request came with, so a session ID is never kept across a sign-in;
 * a sign-out ends the row and clears the cookie. The cookie is set only then: a sign-in sends it once with a Max-Age of
 * the absolute lifetime, and no other answer carries it, so no cacheable answer holds the token and no late answer of
 * an older request hands a replaced token back to the browser (23-70b). Use slides the lifetime in the row alone: at
 * most once per {@code renew-after} a valid request ({@link SessionVersionFilter}) moves the stored renewal time, and
 * the idle timeout is enforced from it here, never by the browser. The account itself is loaded and checked per
 * request by {@link SessionVersionFilter}, never from here.
 */
@Component
public class LoginSessions implements SecurityContextRepository {
    public static final String COOKIE = "GLAZELAB_SESSION";
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    /** This request's session: the stored row, or empty when it has none (cached, so a request looks up once). */
    private static final String CURRENT = LoginSessions.class.getName() + ".current";
    /** Set when this request signed in, so a second save of that sign-in issues no second token. */
    private static final String ISSUED = LoginSessions.class.getName() + ".issued";
    private final LoginSessionRepository sessions;
    private final Duration idle;
    private final Duration absolute;
    private final Duration renewAfter;
    private final boolean secure;

    public LoginSessions(LoginSessionRepository sessions,
            @Value("${glazelab.session.idle-timeout}") Duration idle,
            @Value("${glazelab.session.absolute-timeout}") Duration absolute,
            @Value("${glazelab.session.renew-after}") Duration renewAfter,
            @Value("${server.servlet.session.cookie.secure:false}") boolean secure) {
        if (idle.compareTo(absolute) > 0 || renewAfter.compareTo(idle) >= 0)
            throw new IllegalStateException("glazelab.session: renew-after < idle-timeout <= absolute-timeout");
        this.sessions = sessions; this.idle = idle; this.absolute = absolute; this.renewAfter = renewAfter;
        this.secure = secure;
    }

    @Override public DeferredSecurityContext loadDeferredContext(HttpServletRequest request) {
        return new DeferredSecurityContext() {
            private SecurityContext context;
            @Override public SecurityContext get() {
                if (context == null) context = load(request);
                return context;
            }
            @Override public boolean isGenerated() { return get().getAuthentication() == null; }
        };
    }
    @Deprecated @Override public SecurityContext loadContext(HttpRequestResponseHolder holder) {
        return loadDeferredContext(holder.getRequest()).get();
    }
    @Override public boolean containsContext(HttpServletRequest request) { return current(request).isPresent(); }

    /** A signed-in context issues a new session (sign-in); any other context ends this request's session (sign-out). */
    @Override public void saveContext(SecurityContext context, HttpServletRequest request, HttpServletResponse response) {
        var auth = context.getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof AccountPrincipal principal) {
            if (request.getAttribute(ISSUED) instanceof LoginSession issued && issued.getUserId().equals(principal.getUserId())
                    && issued.getSessionVersion() == principal.getSessionVersion()) return;
            endRequested(request);
            String token = SecureTokens.generate();
            LoginSession session;
            try {
                session = sessions.save(new LoginSession(SecureTokens.hash(token), principal.getUserId(),
                        principal.getSessionVersion(), Instant.now()));
            } catch (DataIntegrityViolationException gone) {
                // The account went (deletion or ban) after the password check: its foreign key fails the insert. A
                // sign-in answers like a wrong password, the other callers like any request of a removed account (34-3).
                throw new BadCredentialsException("The account was removed during sign-in");
            }
            request.setAttribute(CURRENT, Optional.of(session));
            request.setAttribute(ISSUED, session);
            response.addHeader(HttpHeaders.SET_COOKIE, cookie(token, absolute, secure));
        } else {
            endRequested(request);
            request.setAttribute(CURRENT, Optional.empty());
            request.removeAttribute(ISSUED);
            response.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO, secure));
        }
    }

    /**
     * Renews this request's session when its last renewal is at least {@code renew-after} old: the stored time moves
     * (of concurrent requests one writes). Never sends the cookie, which the sign-in issued for the absolute lifetime.
     * Called by {@link SessionVersionFilter} for a session it has just validated.
     */
    void renew(HttpServletRequest request) {
        LoginSession session = current(request).orElse(null);
        if (session == null || request.getAttribute(ISSUED) != null) return;
        Instant now = Instant.now();
        Instant due = now.minus(renewAfter);
        if (session.getRenewedAt().isBefore(due)) sessions.renew(session.getTokenHash(), now, due);
    }

    /**
     * Ends this request's stale session (password changed, account gone or unverified) and leaves the cookie alone:
     * a concurrent request of the same browser may have signed in meanwhile with a new token, which a cleared cookie
     * would throw away. The old token names no row any more, so it is simply signed out.
     */
    void endStale(HttpServletRequest request) {
        current(request).ifPresent(session -> sessions.end(session.getTokenHash()));
        request.setAttribute(CURRENT, Optional.empty());
    }

    /** Hourly: drops sessions past their idle or absolute timeout (they already count as signed out). */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 600_000)
    public int purge() {
        Instant now = Instant.now();
        int removed = sessions.purge(now.minus(idle), now.minus(absolute));
        if (removed > 0) LoggerFactory.getLogger(LoginSessions.class).info("Removed {} expired sign-ins", removed);
        return removed;
    }

    private SecurityContext load(HttpServletRequest request) {
        SecurityContext context = SecurityContextHolder.getContextHolderStrategy().createEmptyContext();
        current(request).ifPresent(session -> {
            var principal = new AccountPrincipal(session.getUserId(), session.getSessionVersion());
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        });
        return context;
    }
    @SuppressWarnings("unchecked")
    private Optional<LoginSession> current(HttpServletRequest request) {
        if (request.getAttribute(CURRENT) instanceof Optional<?> cached) return (Optional<LoginSession>) cached;
        String token = token(request);
        // Signed out until the lookup answers: when the database fails here, the request's error answer (an error
        // dispatch of the same request) is rendered signed out instead of failing the lookup again and again.
        request.setAttribute(CURRENT, Optional.empty());
        if (token == null) return Optional.empty();
        Instant now = Instant.now();
        Optional<LoginSession> session = sessions.findById(SecureTokens.hash(token))
                .filter(s -> s.getRenewedAt().plus(idle).isAfter(now) && s.getCreatedAt().plus(absolute).isAfter(now));
        request.setAttribute(CURRENT, session);
        return session;
    }
    /** Ends whatever row the request's cookie names, valid or not, so a sign-in or sign-out never leaves it behind. */
    private void endRequested(HttpServletRequest request) {
        String token = token(request);
        if (token != null) sessions.end(SecureTokens.hash(token));
    }
    private static String token(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies)
            if (COOKIE.equals(cookie.getName()) && TOKEN.matcher(cookie.getValue()).matches()) return cookie.getValue();
        return null;
    }
    static String cookie(String value, Duration maxAge, boolean secure) {
        return ResponseCookie.from(COOKIE, value).path("/").httpOnly(true).secure(secure).sameSite("Lax")
                .maxAge(maxAge).build().toString();
    }
}
