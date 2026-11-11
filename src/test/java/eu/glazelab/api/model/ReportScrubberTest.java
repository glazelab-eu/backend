package eu.glazelab.api.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/** The server's second line of defence for report text (backend guide "Error reports"). */
class ReportScrubberTest {
    private static final String TOKEN = "Abc_DEF-ghi0123456789jklMNOpqrStuVWxyz-_09A"; // 43 base64url characters, like SecureTokens

    private static String scrub(String text) {
        return ReportScrubber.scrub(text, ReportScrubber.STACK_LENGTH);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            // Hash routes and fragments of every kind, token routes included.
            "Failed on https://glazelab.eu/#/buddy/TOKEN | Failed on https://glazelab.eu/#[removed]",
            "Route #/invite/TOKEN broke | Route #[removed] broke",
            "at #/reset/TOKEN | at #[removed]",
            "Old link #verify=TOKEN | Old link #[removed]",
            "Page #/glazes/12 failed | Page #[removed] failed",
            // Query strings.
            "GET /api/x?token=TOKEN failed | GET /api/x?[removed] failed",
            "Loading https://example.test/a?b=c&d=e#f | Loading https://example.test/a?[removed]#[removed]",
            // Email addresses, also with sub-addresses and non-ASCII letters.
            "No account for ana.maria+tag@example.co.uk | No account for [email]",
            "Address josé@exämple.pt is taken | Address [email] is taken",
            // Bearer values and named secrets.
            "Authorization: Bearer abc.def.ghi | Authorization: [removed] [removed]",
            "header X-Xsrf-Token: 1234 rejected | header X-Xsrf-Token: [removed] rejected",
            "cookie GLAZELAB_SESSION=abcd; path | cookie GLAZELAB_SESSION=[removed]; path",
            "{\"password\":\"hunter2\"} | {\"password\":\"[removed]\"}",
            "api_key=xyz and apiKey: 42 | api_key=[removed] and apiKey: [removed]",
            // Token-shaped runs (base64url and hex) go; a UUID stays.
            "Invalid link TOKEN | Invalid link [token]",
            "hash 3f2a9c1e5b7d40e8a6c2f19b0d3e5a7c9e1f2b4d6a8c0e2f4a6b8c0d2e4f6a8 | hash [token]",
            "Glaze 3b241101-e2bb-4255-8caf-4136c566a962 missing | Glaze 3b241101-e2bb-4255-8caf-4136c566a962 missing",
    })
    void removesWhatCouldIdentifyOrAuthenticate(String raw, String expected) {
        assertEquals(expected, scrub(raw.replace("TOKEN", TOKEN)));
    }

    @Test void keepsOrdinaryMessagesAndStacks() {
        String chrome = "TypeError: Cannot read properties of undefined (reading 'name')\n"
                + "    at render (https://glazelab.eu/assets/index-B1x2Yz.js:1:2345)\n"
                + "    at https://glazelab.eu/assets/index-B1x2Yz.js:3:45";
        assertEquals(chrome, scrub(chrome));
        String firefox = "onClick@https://glazelab.eu/assets/index-B1x2Yz.js:12:7\nflush@https://glazelab.eu/assets/vendor-x.js:1:1";
        assertEquals(firefox, scrub(firefox));
        assertEquals("Did it fail? Yes. #", scrub("Did it fail? Yes. #"));
        // A name that merely contains a secret word loses its value too: over-removal is the safe side.
        assertEquals("sessionStorage: [removed]", scrub("sessionStorage: denied"));
        assertNull(ReportScrubber.scrub(null, 10));
    }

    @Test void dropsControlCharactersAndUnpairedSurrogatesButKeepsLinesTabsAndEmoji() {
        assertEquals("a\nb\tc d🙂", scrub("a\u0000\nb\tc\u001b d\ud800🙂"));
    }

    @Test void truncatesWithoutSplittingACharacter() {
        assertEquals("abcd…", ReportScrubber.scrub("abcdefgh", 5));
        assertEquals("abc…", ReportScrubber.scrub("abc🙂🙂🙂", 5));
        assertEquals("abcde", ReportScrubber.scrub("abcde", 5));
        assertEquals(ReportScrubber.MESSAGE_LENGTH, ReportScrubber.scrub("x ".repeat(2000), ReportScrubber.MESSAGE_LENGTH).length());
    }

    @Test void longInputScrubsQuickly() {
        long start = System.nanoTime();
        for (String text : new String[] {"a".repeat(4000), "a@".repeat(2000), "token".repeat(800), "a-".repeat(2000), "#?".repeat(2000)})
            ReportScrubber.scrub(text, ReportScrubber.STACK_LENGTH);
        assertTrue(System.nanoTime() - start < 2_000_000_000L, "no catastrophic backtracking");
    }

    @Test void fingerprintsGroupByKindAndMessageWithNumbersIgnored() {
        String first = ReportScrubber.fingerprint("ERROR", "Index 3 out of range");
        assertEquals(64, first.length());
        assertEquals(first, ReportScrubber.fingerprint("ERROR", "Index 41 out of range"));
        assertNotEquals(first, ReportScrubber.fingerprint("WARNING", "Index 3 out of range"));
        assertNotEquals(first, ReportScrubber.fingerprint("ERROR", "Index out of range"));
    }
}
