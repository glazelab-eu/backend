package eu.glazelab.api.service;

import eu.glazelab.api.repository.AdminActionRepository;
import eu.glazelab.api.repository.EmailBlockRepository;
import eu.glazelab.api.security.CurrentUser;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The blocklist's key handling (backend guide "Bans"): never a usable list without a real secret. */
class EmailBlocklistTest {
    private static final String SECRET = "a-test-secret-of-forty-characters-123456";
    private static final String OTHER = "another-test-secret-of-forty-characters!";
    private final EmailBlockRepository blocks = mock(EmailBlockRepository.class);

    private EmailBlocklist blocklist(String profile, String secret) {
        var environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        return new EmailBlocklist(blocks, mock(AdminActionRepository.class), mock(CurrentUser.class), environment, secret);
    }

    @Test void refusesToStartWithoutALongEnoughSecretAndNamesOnlyTheVariable() {
        for (String weak : new String[] {"", "   ", "short-secret", "x".repeat(31)}) {
            var error = assertThrows(IllegalStateException.class, () -> blocklist("dev", weak));
            assertTrue(error.getMessage().startsWith("GLAZELAB_BAN_SECRET must be set"), error.getMessage());
            if (!weak.isBlank()) assertFalse(error.getMessage().contains(weak));
        }
    }

    @Test void prodRefusesTheDevelopmentDefault() {
        String devDefault = "dev-only-ban-secret-3f9c2a7e5b1d48c6a0e2f4b8d6c1a9e7";
        assertNotNull(blocklist("dev", devDefault));
        var error = assertThrows(IllegalStateException.class, () -> blocklist("prod", devDefault));
        assertFalse(error.getMessage().contains(devDefault));
        assertNotNull(blocklist("prod", SECRET));
    }

    @Test void theHashIsKeyedHexAndTheSameForEveryWritingOfOneMailbox() throws Exception {
        var first = blocklist("dev", SECRET);
        String hash = first.hash("Glazelab.Test.Only+kiln@googlemail.com");
        assertTrue(hash.matches("[0-9a-f]{64}"), hash);
        assertEquals(hash, first.hash("  glazelabtestonly@GMAIL.com "));
        assertNotEquals(hash, first.hash("glazelabtestonly2@gmail.com"));
        // Another secret gives another hash: a copy of the table is useless without the secret.
        assertNotEquals(hash, blocklist("dev", OTHER).hash("glazelabtestonly@gmail.com"));
        // HMAC-SHA-256, not a bare SHA-256 of the address.
        assertNotEquals(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest("glazelabtestonly@gmail.com".getBytes(java.nio.charset.StandardCharsets.UTF_8))), hash);
    }

    /** One secret, one hash, one indexed statement: there is no previous secret (a changed secret empties the list). */
    @Test void aLookupAsksForTheCurrentSecretsHashOnly() {
        when(blocks.existsByEmailHash(any())).thenReturn(true);
        var list = blocklist("prod", OTHER);
        assertTrue(list.blocked("potter@example.test"));
        verify(blocks, times(1)).existsByEmailHash(list.hash("potter@example.test"));
        assertFalse(blocklist("prod", SECRET).hash("potter@example.test").equals(list.hash("potter@example.test")));
    }
}
