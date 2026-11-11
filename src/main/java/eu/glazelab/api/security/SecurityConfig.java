package eu.glazelab.api.security;

import eu.glazelab.api.dto.CommonDtos.ErrorView;
import eu.glazelab.api.model.AppUser;
import eu.glazelab.api.model.EmailAddresses;
import eu.glazelab.api.model.Text;
import eu.glazelab.api.repository.AppUserRepository;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AccountStatusUserDetailsChecker;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.savedrequest.NullRequestCache;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@EnableConfigurationProperties(RateLimitSettings.class)
public class SecurityConfig {
    private static final JsonMapper ERRORS = JsonMapper.builder().build();
    /** Open to anonymous requests; {@code config.ErrorResponses} reads them to decide which operations can answer 401. */
    public static final String[] PUBLIC_GET = {"/api/auth/csrf", "/api/auth/me", "/api/auth/options", "/api/auth/operator-contact", "/api/auth/clear-cache", "/api/health", "/api/openapi",
            "/api/glazes", "/api/glazes/*/images", "/api/glazes/*/history", "/api/barcodes/*", "/api/combinations", "/api/images/**",
            "/api/sync", "/api/sync/combinations", "/api/favorites/counts"};
    public static final String[] PUBLIC_POST = {"/api/auth/register", "/api/auth/login",
            "/api/auth/password/request", "/api/auth/password/reset",
            "/api/auth/verification/request", "/api/auth/verification/confirm",
            "/api/auth/magic/request", "/api/auth/magic/confirm"};

    @Bean PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * Sign-in by email; the only source of credentials, used through {@link #authentication}. An address with a control
     * character (NUL fails the PostgreSQL lookup) is unknown without a lookup: no stack trace per attempt, and
     * {@code DaoAuthenticationProvider} still hashes the password, so it costs what any unknown address costs.
     */
    static UserDetailsService users(AppUserRepository accounts) {
        return email -> (Text.valid(email, Text.Kind.LINE) ? accounts.findByEmailKey(EmailAddresses.key(email)) : Optional.<AppUser>empty())
                .map(AccountPrincipal::new)
                .orElseThrow(() -> new UsernameNotFoundException(email));
    }

    /**
     * Sign-in compares the password first and checks the account status (a pending, unverified account is disabled)
     * only after a match, so a pending account costs the same hash as a wrong password or an unknown address and its
     * refusal never answers faster. Pending accounts hold the hash of a discarded secret, so they fail the comparison.
     */
    @Bean DaoAuthenticationProvider authentication(AppUserRepository accounts, PasswordEncoder passwords) {
        return accountAuthentication(users(accounts), passwords);
    }
    static DaoAuthenticationProvider accountAuthentication(UserDetailsService users, PasswordEncoder passwords) {
        var provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(passwords);
        provider.setPreAuthenticationChecks(user -> {});
        provider.setPostAuthenticationChecks(new AccountStatusUserDetailsChecker());
        return provider;
    }

    @Bean CsrfTokenRepository csrfTokens() {
        var csrf = new CookieCsrfTokenRepository();
        csrf.setCookieCustomizer(cookie -> cookie.sameSite("Lax"));
        return csrf;
    }
    /**
     * Sign-ins live in {@link LoginSessions} (database and persistent cookie), never in the servlet session. Primary:
     * every save and load goes through the request attribute first, also {@link SessionLogin}'s.
     */
    @Bean @Primary SecurityContextRepository securityContexts(LoginSessions sessions) {
        return new DelegatingSecurityContextRepository(new RequestAttributeSecurityContextRepository(), sessions);
    }
    @Bean SecurityFilterChain security(HttpSecurity http, AppUserRepository accounts,
            CsrfTokenRepository csrf,
            SecurityContextRepository contexts, LoginSessions sessions,
            RateLimiter limiter, Tarpit tarpit, SignInDelays delays) throws Exception {
        // The SPA fetches the raw token from /api/auth/csrf; it never reads cookies.
        var handler = new CsrfTokenRequestAttributeHandler();
        // Rate limits run before CSRF checks and sign-in (password hashing), with the session's user known.
        http.addFilterBefore(new RateLimitFilter(limiter, tarpit), CsrfFilter.class)
            // Oversized bodies (413) before CSRF reads a parameter or anything parses JSON.
            .addFilterAfter(new BodySizeFilter(), RateLimitFilter.class)
            // The per-address sign-in delay, after CSRF and before any password is compared.
            .addFilterBefore(new SignInDelayFilter(delays), UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(new SessionVersionFilter(accounts, sessions), AuthorizationFilter.class)
            // Writes need the current terms; after the session check, so ended sessions get 401 instead.
            .addFilterAfter(new TermsFilter(), SessionVersionFilter.class)
            .csrf(c -> c.csrfTokenRepository(csrf).csrfTokenRequestHandler(handler))
            .securityContext(context -> context.securityContextRepository(contexts))
            .requestCache(cache -> cache.requestCache(new NullRequestCache()))
            .authorizeHttpRequests(a -> a
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers(HttpMethod.GET, PUBLIC_GET).permitAll()
                .requestMatchers(HttpMethod.HEAD, "/api/health", "/api/glazes", "/api/glazes/*/images", "/api/glazes/*/history", "/api/barcodes/*",
                        "/api/combinations", "/api/images/**").permitAll()
                .requestMatchers(HttpMethod.POST, PUBLIC_POST).permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().denyAll())
            .formLogin(f -> f.loginPage("/api/auth/login")
                .loginProcessingUrl("/api/auth/login")
                .usernameParameter("email")
                .successHandler((req, res, auth) -> {
                    delays.reset(req.getParameter("email"));
                    json(res, 200, "{\"ok\":true}");
                })
                .failureHandler((req, res, error) -> {
                    limiter.signInFailed(RateLimiter.clientKey(req.getRemoteAddr(), null));
                    error(res, 401, "Invalid email or password");
                }))
            .logout(l -> l.logoutUrl("/api/auth/logout")
                .logoutSuccessHandler((req, res, auth) -> json(res, 200, "{\"ok\":true}")))
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req, res, error) -> error(res, 401, CurrentUser.SIGN_IN_REQUIRED))
                .accessDeniedHandler((req, res, error) -> error(res, 403,
                        "Access denied. Refresh your session and try again.")));
        return http.build();
    }

    /** The one writer of security-layer errors: the {@link ErrorView} JSON of {@code ApiErrorHandler}, escaped by Jackson. */
    static void error(HttpServletResponse response, int status, String message) throws IOException {
        json(response, status, ERRORS.writeValueAsString(new ErrorView(message)));
    }

    private static void json(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        // JSON is UTF-8; without this the servlet writer declares and uses ISO-8859-1.
        response.setCharacterEncoding(StandardCharsets.UTF_8);
        response.getWriter().write(body);
    }
}
