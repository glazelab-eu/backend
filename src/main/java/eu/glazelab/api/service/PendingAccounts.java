package eu.glazelab.api.service;

import eu.glazelab.api.model.AppUser;
import eu.glazelab.api.model.EmailAddresses;
import eu.glazelab.api.repository.AccountTokenRepository;
import eu.glazelab.api.repository.AppUserRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Dormant registrations cannot sign in, so they own no application data; the purge still removes the rows
 * {@link AccountRows} lists (memberships added before pending addresses were refused) before the account.
 */
@Service @Transactional @RequiredArgsConstructor @Slf4j
public class PendingAccounts {
    private final AppUserRepository users;
    private final AccountTokenRepository tokens;
    private final AccountRows accountRows;
    private final TransactionTemplate transactions;

    /** Locks only an expired account: a live one may be held by a mail task, and registration must not wait for it. */
    public void expireEmail(String email, LocalDateTime now) {
        users.expiredUnverifiedIdByEmailKey(EmailAddresses.key(email), now)
                .flatMap(users::lockById).filter(user -> user.verificationExpired(now)).ifPresent(this::remove);
    }

    /**
     * Bounded work, one transaction per account so one failure never rolls back the others;
     * first registration after expiry also releases the email immediately.
     */
    @Scheduled(fixedDelay = 900_000, initialDelay = 900_000)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void expireBatch() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        for (var id : users.expiredUnverifiedIds(now, PageRequest.of(0, 500))) {
            try {
                transactions.executeWithoutResult(status ->
                        users.lockById(id).filter(user -> user.verificationExpired(now)).ifPresent(this::remove));
            } catch (RuntimeException error) {
                // The account ID only: exception messages can carry row values (addresses).
                log.warn("Could not purge expired pending account {} ({})", id, error.getClass().getSimpleName());
            }
        }
    }

    /**
     * Hourly: drops account tokens past their expiry, which no request can consume. One transaction per account under
     * its lock, which every token issue and consumption holds, so a token renewed meanwhile is never removed.
     */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 1_200_000)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int expireTokens() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int removed = 0;
        for (var id : tokens.expiredUserIds(now, PageRequest.of(0, 500))) {
            try {
                Integer count = transactions.execute(status ->
                        users.lockById(id).map(user -> tokens.deleteExpired(id, now)).orElse(0));
                removed += count == null ? 0 : count;
            } catch (RuntimeException error) {
                // The account ID only: exception messages can carry row values.
                log.warn("Could not purge expired tokens of account {} ({})", id, error.getClass().getSimpleName());
            }
        }
        if (removed > 0) log.info("Removed {} expired account tokens", removed);
        return removed;
    }

    private void remove(AppUser user) {
        accountRows.delete(user.getId());
        users.flush();
        users.delete(user);
        users.flush();
    }
}
