package eu.glazelab.api.model;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;

/**
 * The form of an address the ban blocklist compares (backend guide "Bans"): one mailbox, one form, so the usual ways of
 * writing the same mailbox again meet the same entry. Steps: trim, Unicode NFC, lower case (locale-neutral), a trailing
 * dot of the domain dropped; a sub-address ({@code +tag}, RFC 5233, used by Gmail, Outlook, iCloud, Fastmail, Proton,
 * mailbox.org and most others) cut from the local part; for Gmail ({@code gmail.com}, {@code googlemail.com}, which is
 * the same mailbox) every dot of the local part removed and the domain written {@code gmail.com}.
 * Not caught: other alias schemes (Yahoo's {@code -} disposable addresses, Fastmail's subdomain addressing, aliases set
 * up at any provider), a domain written in punycode instead of Unicode, catch-all or own domains, and any new mailbox.
 * The one false-positive risk: a provider where {@code +} is part of a different person's mailbox name (rare).
 */
public final class EmailAddresses {
    private static final Set<String> GMAIL = Set.of("gmail.com", "googlemail.com");

    private EmailAddresses() {}

    /**
     * The form an account's address is unique in and looked up by (sign-in, registration, account mail, the sign-in
     * delay): trimmed, lower case (locale-neutral), then every code point folded through upper case and back, so a long
     * s, a dotless i, a final sigma or a Kelvin sign meets the plain letter's account, as the earlier upper-case
     * comparison did. Stored as {@code app_user.email_key} (V37, unique): no expression index, because H2 has none and
     * the database's {@code lower()} differs from Java's. A change here needs a migration that recomputes every key.
     */
    public static String key(String email) {
        return email.trim().toLowerCase(Locale.ROOT).codePoints().map(Character::toUpperCase).map(Character::toLowerCase)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
    }

    public static String canonical(String email) {
        String address = Normalizer.normalize(email.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        int at = address.lastIndexOf('@');
        if (at <= 0 || at == address.length() - 1) return address;
        String local = address.substring(0, at), domain = address.substring(at + 1);
        if (domain.endsWith(".")) domain = domain.substring(0, domain.length() - 1);
        int plus = local.indexOf('+');
        if (plus > 0) local = local.substring(0, plus);
        if (GMAIL.contains(domain)) {
            local = local.replace(".", "");
            domain = "gmail.com";
        }
        return local + "@" + domain;
    }
}
