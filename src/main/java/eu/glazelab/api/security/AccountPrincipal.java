package eu.glazelab.api.security;

import eu.glazelab.api.model.AppUser;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

/**
 * The signed-in identity: the account's immutable ID and the session version it signed in with. Built from the
 * account for a sign-in (its password hash is erased before the sign-in is stored) and from a stored
 * {@link LoginSessions} row on every later request (ID and version only; the name is the ID, never the email).
 */
public class AccountPrincipal extends User {
    private static final long serialVersionUID = 1L;
    private static final List<SimpleGrantedAuthority> AUTHORITIES = List.of(new SimpleGrantedAuthority("ROLE_USER"));
    private final UUID userId;
    private final int sessionVersion;
    public AccountPrincipal(AppUser account) {
        super(account.getEmail(), account.getPasswordHash(), account.isEmailVerified(), true, true, true, AUTHORITIES);
        userId = account.getId(); sessionVersion = account.getSessionVersion();
    }
    AccountPrincipal(UUID userId, int sessionVersion) {
        super(userId.toString(), "", AUTHORITIES);
        eraseCredentials();
        this.userId = userId; this.sessionVersion = sessionVersion;
    }
    public UUID getUserId() { return userId; }
    public int getSessionVersion() { return sessionVersion; }
}
