package eu.glazelab.api.model;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The ban blocklist's normalisation vectors (backend guide "Bans"): what one entry catches and what it cannot; and the
 * account lookup key's (backend guide "Schema", V37).
 */
class EmailAddressesTest {
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            // Case and surrounding whitespace never matter.
            "Potter@Example.TEST|potter@example.test",
            "'  potter@example.test\t'|potter@example.test",
            // A sub-address is the same mailbox at almost every provider.
            "potter+glazes@example.test|potter@example.test",
            "potter+a+b@example.test|potter@example.test",
            // Dots are part of the name outside Gmail.
            "jo.potter@example.test|jo.potter@example.test",
            "j.o.potter+x@example.org|j.o.potter@example.org",
            // Gmail ignores dots, and googlemail.com is the same mailbox (its domains are under test; the local part is invented).
            "Glazelab.Test.Only@gmail.com|glazelabtestonly@gmail.com",
            "g.l.a.z.e.l.a.b.t.e.s.t.o.n.l.y+kiln@GoogleMail.com|glazelabtestonly@gmail.com",
            "glazelabtestonly@gmail.com.|glazelabtestonly@gmail.com",
            // Unicode is compared in NFC: a precomposed and a decomposed ü are one address.
            "müller@example.test|müller@example.test",
            "müller@example.test|müller@example.test",
            // Not caught, by design: another alias scheme (a hyphen), punycode, a leading plus (no sub-address), malformed input.
            "potter-glazes@example.test|potter-glazes@example.test",
            "potter@xn--mller-kva.example|potter@xn--mller-kva.example",
            "+potter@example.test|+potter@example.test",
            "no-at-sign|no-at-sign" })
    void canonicalForms(String address, String canonical) {
        assertEquals(canonical, EmailAddresses.canonical(address));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "'  Potter@Example.TEST '|potter@example.test",
            // A long s, a dotless i, a final sigma and a Kelvin sign meet the plain letter (upper case S, I, Σ, then K).
            "jeſſ@example.test|jess@example.test",
            "ıvy@example.test|ivy@example.test",
            "JEſſ@EXAMPLE.TEST|jess@example.test",
            "ας@example.test|ασ@example.test",
            "\u212Aate@example.test|kate@example.test",
            // A dotted capital I lower-cases to i and a combining dot, both ways of writing it alike.
            "İvy@example.test|i\u0307vy@example.test",
            "i\u0307vy@example.test|i\u0307vy@example.test",
            // No other folding: sub-addresses, dots, ß and decomposed letters stay as written (only the blocklist joins them).
            "potter+glazes@example.test|potter+glazes@example.test",
            "straße@example.test|straße@example.test",
            "mu\u0308ller@example.test|mu\u0308ller@example.test" })
    void lookupKeys(String address, String key) {
        String raw = unescape(address);
        assertEquals(unescape(key), EmailAddresses.key(raw));
        // Registration stores the trimmed lower-case form: it has the key of what was typed.
        assertEquals(EmailAddresses.key(raw), EmailAddresses.key(raw.trim().toLowerCase(java.util.Locale.ROOT)));
    }

    private static String unescape(String text) {
        var match = java.util.regex.Pattern.compile("\\\\u([0-9A-Fa-f]{4})").matcher(text);
        return match.replaceAll(found -> String.valueOf((char) Integer.parseInt(found.group(1), 16)));
    }
}
