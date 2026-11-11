package eu.glazelab.api.security;

import eu.glazelab.api.model.AppUser;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;

/** Sign-in compares the password before the account status, so a pending account costs the same hash as any other. */
class AccountAuthenticationTest {
    private static final PasswordEncoder REAL = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    private final AtomicInteger comparisons = new AtomicInteger();
    private final PasswordEncoder counting = new PasswordEncoder() {
        @Override public String encode(CharSequence raw) { return REAL.encode(raw); }
        @Override public boolean matches(CharSequence raw, String encoded) {
            comparisons.incrementAndGet();
            return REAL.matches(raw, encoded);
        }
    };

    private static AppUser account(String password, boolean verified) {
        AppUser account = new AppUser("ada@example.test", REAL.encode(password), "Ada");
        if (!verified) account.awaitVerification(LocalDateTime.now(ZoneOffset.UTC).plusHours(24));
        return account;
    }

    private UsernamePasswordAuthenticationToken signIn(AppUser account, String password) {
        UserDetailsService users = email -> new AccountPrincipal(account);
        var result = SecurityConfig.accountAuthentication(users, counting)
                .authenticate(UsernamePasswordAuthenticationToken.unauthenticated("ada@example.test", password));
        return (UsernamePasswordAuthenticationToken) result;
    }

    @Test void aPendingAccountIsRefusedAfterTheSameHashComparisonAsAWrongPassword() {
        AppUser pending = account("discarded random secret", false);
        assertThrows(BadCredentialsException.class, () -> signIn(pending, "a guess"));
        assertEquals(1, comparisons.get());
    }

    @Test void aPendingAccountStaysRefusedEvenWhenItsStoredPasswordMatches() {
        // Pending accounts registered before passwords left registration still store the registrant's hash.
        AppUser legacy = account("registered password", false);
        assertThrows(DisabledException.class, () -> signIn(legacy, "registered password"));
        assertEquals(1, comparisons.get());
    }

    @Test void aVerifiedAccountSignsInWithItsPasswordOnly() {
        AppUser verified = account("owner password", true);
        assertTrue(signIn(verified, "owner password").isAuthenticated());
        assertThrows(BadCredentialsException.class, () -> signIn(verified, "a guess"));
    }
}
