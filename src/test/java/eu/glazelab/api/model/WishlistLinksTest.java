package eu.glazelab.api.model;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WishlistLinksTest {
    private record LinkCase(String link, boolean valid) {}

    @Test void sharesTheLinkGrammarWithTheFrontend() throws Exception {
        Path fixture = Path.of("../frontend/src/lib/clubs/wishlist-links.json");
        assumeTrue(Files.exists(fixture), "frontend link vectors are not available");
        LinkCase[] cases = JsonMapper.builder().build().readValue(Files.readString(fixture), LinkCase[].class);
        assertTrue(cases.length > 40);
        for (LinkCase row : cases) assertEquals(row.valid(), WishlistLinks.valid(row.link()), row.link());
    }

    @Test void aLinkHasAtMostTwoThousandCharacters() {
        String base = "https://example.com/";
        assertTrue(WishlistLinks.valid(base + "a".repeat(WishlistLinks.MAX_LENGTH - base.length())));
        assertFalse(WishlistLinks.valid(base + "a".repeat(WishlistLinks.MAX_LENGTH - base.length() + 1)));
        // A long host of many labels matches without deep recursion.
        assertFalse(WishlistLinks.valid("https://" + "a.".repeat(5000) + "com/"));
    }

    @Test void cleanStripsAndRefusesWithThePlainMessages() {
        assertEquals("https://example.com/x", WishlistLinks.clean("  https://example.com/x "));
        assertEquals(WishlistLinks.REQUIRED, assertThrows(Text.InvalidText.class, () -> WishlistLinks.clean("  ")).getMessage());
        assertEquals(WishlistLinks.REQUIRED, assertThrows(Text.InvalidText.class, () -> WishlistLinks.clean(null)).getMessage());
        assertEquals(WishlistLinks.INVALID, assertThrows(Text.InvalidText.class, () -> WishlistLinks.clean("ftp://x.test")).getMessage());
    }
}
