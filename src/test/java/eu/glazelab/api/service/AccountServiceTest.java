package eu.glazelab.api.service;

import eu.glazelab.api.model.AccountToken;
import eu.glazelab.api.model.AccountToken.Purpose;
import eu.glazelab.api.model.AppUser;
import eu.glazelab.api.repository.AccountTokenRepository;
import eu.glazelab.api.repository.AppUserRepository;
import eu.glazelab.api.security.SecureTokens;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:accounttest;DB_CLOSE_DELAY=-1", "spring.mail.host="})
@Transactional
class AccountServiceTest {
    @Autowired AccountService accounts;
    @Autowired AppUserRepository users;
    @Autowired AccountTokenRepository tokens;
    @Autowired PasswordEncoder passwords;
    private static final AccountService.Consent CONSENT = new AccountService.Consent(true, AccountService.TERMS_VERSION, false);

    @Test void expiredTokensCannotChangeCredentialsAndValidTokensAreConsumed() {
        AppUser user = users.save(new AppUser("expiry@example.test", passwords.encode("original123"), "Expiry"));
        String raw = SecureTokens.generate();
        AccountToken expired = new AccountToken(user, Purpose.RESET_PASSWORD);
        expired.renew(SecureTokens.hash(raw), LocalDateTime.now(ZoneOffset.UTC).minusHours(1));
        expired = tokens.saveAndFlush(expired);
        assertThrows(ResponseStatusException.class, () -> accounts.reset(raw, "replacement123", null, null));
        assertTrue(passwords.matches("original123", user.getPasswordHash()));
        assertEquals(0, user.getSessionVersion());
        expired.renew(SecureTokens.hash(raw), LocalDateTime.now(ZoneOffset.UTC));
        tokens.flush();
        accounts.reset(raw, "replacement123", null, null);
        assertTrue(passwords.matches("replacement123", user.getPasswordHash()));
        assertEquals(1, user.getSessionVersion());
        assertTrue(tokens.findByUser_IdAndPurpose(user.getId(), Purpose.RESET_PASSWORD).isEmpty());
        assertThrows(ResponseStatusException.class, () -> accounts.reset(raw, "another123", null, null));
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void concurrentResetConsumesTokenOnlyOnce() throws Exception {
        AppUser user = users.saveAndFlush(new AppUser("race@example.test", passwords.encode("original123"), "Race"));
        String raw = SecureTokens.generate();
        AccountToken token = new AccountToken(user, Purpose.RESET_PASSWORD);
        token.renew(SecureTokens.hash(raw), LocalDateTime.now(ZoneOffset.UTC));
        tokens.saveAndFlush(token);
        var gate = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> reset = () -> {
                gate.await();
                try { accounts.reset(raw, "replacement123", null, null); return true; }
                catch (ResponseStatusException ex) { assertEquals(400, ex.getStatusCode().value()); return false; }
            };
            var first = executor.submit(reset);
            var second = executor.submit(reset);
            gate.countDown();
            assertNotEquals(first.get(), second.get());
            assertEquals(1, users.findById(user.getId()).orElseThrow().getSessionVersion());
        }
    }

    @Test void magicLinksExpireAndVerifyTheMailboxOnConsumption() {
        AppUser user = users.save(new AppUser("magic@example.test", passwords.encode("original123"), "Magic"));
        user.awaitVerification(LocalDateTime.now(ZoneOffset.UTC).plusHours(24));
        String raw = SecureTokens.generate();
        AccountToken token = new AccountToken(user, Purpose.MAGIC_LOGIN);
        token.renew(SecureTokens.hash(raw), LocalDateTime.now(ZoneOffset.UTC).minusMinutes(16));
        token = tokens.saveAndFlush(token);
        assertThrows(ResponseStatusException.class, () -> accounts.magicLogin(raw, "replacement123", "Mailbox owner", CONSENT));
        token.renew(SecureTokens.hash(raw), LocalDateTime.now(ZoneOffset.UTC));
        tokens.flush();
        assertTrue(accounts.magicLogin(raw, "replacement123", "Mailbox owner", CONSENT).isEmailVerified());
        assertThrows(ResponseStatusException.class, () -> accounts.magicLogin(raw, "replacement123", "Mailbox owner", CONSENT));
    }
}
