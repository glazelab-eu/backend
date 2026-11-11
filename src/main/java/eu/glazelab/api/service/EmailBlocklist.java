package eu.glazelab.api.service;

import eu.glazelab.api.dto.BanDtos.BanPageView;
import eu.glazelab.api.dto.BanDtos.BanView;
import eu.glazelab.api.model.AdminAction;
import eu.glazelab.api.model.EmailAddresses;
import eu.glazelab.api.model.EmailBlock;
import eu.glazelab.api.repository.AdminActionRepository;
import eu.glazelab.api.repository.EmailBlockRepository;
import eu.glazelab.api.security.CurrentUser;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The ban blocklist (backend guide "Bans"): keyed hashes of banned addresses, never the addresses. The hash is the hex
 * HMAC-SHA-256 of {@link EmailAddresses#canonical} under {@code glazelab.ban.secret} ({@code GLAZELAB_BAN_SECRET}), set
 * once: a changed or lost secret makes every entry stop matching. A lookup asks the unique index once
 * ({@link EmailBlockRepository#existsByEmailHash}).
 */
@Service @Slf4j
public class EmailBlocklist {
    public static final String BAN_NOT_FOUND = "Ban not found";
    /** At least this many characters: the secret is the only thing between a copy of the table and a dictionary test. */
    static final int MIN_SECRET_LENGTH = 32;
    /** The dev profile's published default starts with this; the prod profile refuses it. */
    static final String DEV_PREFIX = "dev-only-";

    private final EmailBlockRepository blocks;
    private final AdminActionRepository actions;
    private final CurrentUser current;
    private final SecretKeySpec key;

    public EmailBlocklist(EmailBlockRepository blocks, AdminActionRepository actions, CurrentUser current,
            Environment environment, @Value("${glazelab.ban.secret:}") String secret) {
        this.blocks = blocks;
        this.actions = actions;
        this.current = current;
        this.key = key(secret, environment.acceptsProfiles(Profiles.of("prod")));
    }

    /** Refuses to start without a usable secret; the message names the variable, never the value. */
    private static SecretKeySpec key(String secret, boolean prod) {
        if (secret.strip().length() < MIN_SECRET_LENGTH)
            throw new IllegalStateException("GLAZELAB_BAN_SECRET must be set to a random value of at least "
                    + MIN_SECRET_LENGTH + " characters (README \"Bans\")");
        if (prod && secret.strip().startsWith(DEV_PREFIX))
            throw new IllegalStateException("GLAZELAB_BAN_SECRET is the development default; set a random production secret");
        return new SecretKeySpec(secret.strip().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    /** The secret's hash of the address, the one an entry stores. */
    String hash(String email) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(EmailAddresses.canonical(email).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("HmacSHA256 is unavailable", impossible);
        }
    }

    /** Whether the address (in any written form that has the same canonical form) is banned: one indexed statement. */
    @Transactional(readOnly = true)
    public boolean blocked(String email) {
        return blocks.existsByEmailHash(hash(email));
    }

    /**
     * Adds the banned account's address inside the ban's transaction (after the account locks) and logs the ban. A
     * mailbox banned before keeps its first entry (the unique hash); the log row names the entry either way.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void add(String email, String reason, UUID adminId, UUID bannedAccount) {
        Instant now = Instant.now();
        String hash = hash(email);
        UUID entry = blocks.idOf(hash).orElseGet(() -> blocks.save(new EmailBlock(hash, reason, now)).getId());
        actions.save(new AdminAction(adminId, AdminAction.Type.BAN, bannedAccount, entry, reason, now));
    }

    /**
     * Unban: deletes the entry, so the address may register again, and logs it; 404 for an unknown entry (also one
     * another admin removed first). Locks the admin's account first and re-reads the site-admin flag under it (403).
     */
    @Transactional
    public void unban(UUID entryId, UUID adminId) {
        current.lockSiteAdmin(adminId);
        if (blocks.deleteOne(entryId) != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND, BAN_NOT_FOUND);
        actions.save(new AdminAction(adminId, AdminAction.Type.UNBAN, null, entryId, null, Instant.now()));
        log.info("Site admin {} lifted the ban {}", adminId, entryId);
    }

    @Transactional(readOnly = true)
    public BanPageView page(Pageable page) {
        var result = blocks.list(page);
        return new BanPageView(result.stream().map(row -> new BanView(row.getId(), row.getReason(), row.getBannedAt())).toList(),
                result.hasNext());
    }
}
