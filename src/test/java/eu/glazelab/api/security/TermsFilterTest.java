package eu.glazelab.api.security;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;

/** All three exemptions compare the decoded path Spring MVC matches, never the raw URI. */
class TermsFilterTest {
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    /** Runs the filter for a signed-in principal whose account the request has not validated: a checked write answers 401. */
    private static MockFilterChain filter(String method, String uri) throws Exception {
        var principal = new AccountPrincipal(UUID.randomUUID(), 1);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        var request = new MockHttpServletRequest(method, uri);
        var chain = new MockFilterChain();
        new TermsFilter().doFilter(request, new MockHttpServletResponse(), chain);
        return chain;
    }

    @Test void exemptionsMatchTheDecodedPath() throws Exception {
        assertNotNull(filter("POST", "/api/%61uth/logout").getRequest());
        assertNotNull(filter("DELETE", "/api/%61vatars/x").getRequest());
        assertNotNull(filter("PUT", "/api/account/di%61gnostics").getRequest());
    }

    @Test void otherWritesAreChecked() throws Exception {
        assertNull(filter("POST", "/api/glazes").getRequest());
        assertNull(filter("POST", "/api/authx/logout").getRequest());
        assertNotNull(filter("GET", "/api/glazes").getRequest());
    }
}
