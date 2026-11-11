package eu.glazelab.api.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Operator signals for {@code docker compose logs api}: one startup summary and a rate-limited line for slow requests.
 * Neither carries a secret, an address, an ID or a query: settings are named, routes are the MVC template.
 */
@Component
public class OperationsLog extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(OperationsLog.class);

    private final Environment env;
    private final ObjectProvider<Flyway> flyway;
    private final long slowNanos;
    /** One line per route template and minute. */
    private final MinuteSummary slowRoutes = new MinuteSummary();

    OperationsLog(Environment env, ObjectProvider<Flyway> flyway, @Value("${glazelab.log.slow-request:2s}") Duration slow) {
        this.env = env;
        this.flyway = flyway;
        this.slowNanos = slow.toNanos();
    }

    @EventListener void started(ApplicationReadyEvent event) {
        Flyway migrations = flyway.getIfAvailable();
        var current = migrations == null ? null : migrations.info().current();
        log.info("Glazelab API started: profiles={} storage={} mail={} email-verification={} rate-limit={} (factor {}) tarpit={} "
                        + "schema={} heap-max={} MiB slow-request-threshold={}ms",
                String.join(",", env.getActiveProfiles().length == 0 ? env.getDefaultProfiles() : env.getActiveProfiles()),
                env.getProperty("glazelab.storage"), !env.getProperty("spring.mail.host", "").isBlank(),
                env.getProperty("glazelab.require-email-verification", "false"),
                env.getProperty("glazelab.rate-limit.enabled"), env.getProperty("glazelab.rate-limit.factor"),
                env.getProperty("glazelab.rate-limit.tarpit.enabled"),
                current == null ? "unknown" : current.getVersion(), Runtime.getRuntime().maxMemory() >> 20, slowNanos / 1_000_000);
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long took = System.nanoTime() - start;
            if (took >= slowNanos && !request.isAsyncStarted()) slow(request, response.getStatus(), took);
        }
    }

    private void slow(HttpServletRequest request, int status, long took) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String route = request.getMethod() + " " + (pattern == null ? "(unmatched)" : pattern);
        int events = slowRoutes.count(route);
        if (events > 0)
            log.warn("Slow request: {} took {} ms (status {}); {} slow request(s) on this route since the last line", route,
                    took / 1_000_000, status, events);
    }
}
