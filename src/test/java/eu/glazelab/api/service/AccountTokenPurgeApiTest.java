package eu.glazelab.api.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import eu.glazelab.api.support.ApiTestBase;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import static org.junit.jupiter.api.Assertions.*;

class AccountTokenPurgeApiTest extends ApiTestBase {
    @Autowired PendingAccounts pending;

    private void token(UUID user, String purpose, LocalDateTime expiresAt) {
        jdbc.update("INSERT INTO account_token (id, user_id, purpose, token_hash, issued_at, expires_at) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), user, purpose, UUID.randomUUID().toString().replace("-", ""), expiresAt.minusMinutes(15), expiresAt);
    }

    private int tokens(UUID user, String purpose) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM account_token WHERE user_id = ? AND purpose = ?", Integer.class, user, purpose);
    }

    @Test void theHourlyPurgeDropsOnlyExpiredTokens() {
        UUID ada = me(user("Ada")).id();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        token(ada, "MAGIC_LOGIN", now.minusMinutes(1));
        token(ada, "RESET_PASSWORD", now.plusMinutes(20));
        var logger = (Logger) LoggerFactory.getLogger(PendingAccounts.class);
        var captured = new ListAppender<ILoggingEvent>();
        captured.start();
        logger.addAppender(captured);
        int removed;
        try { removed = pending.expireTokens(); } finally { logger.detachAppender(captured); }
        assertTrue(removed >= 1);
        // A retention purge logs its count only, never an account, address or token.
        assertEquals(java.util.List.of("Removed " + removed + " expired account tokens"),
                captured.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
        assertEquals(0, tokens(ada, "MAGIC_LOGIN"));
        assertEquals(1, tokens(ada, "RESET_PASSWORD"));
    }
}
