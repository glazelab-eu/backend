package eu.glazelab.api.model;

import eu.glazelab.api.model.Text.Kind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TextTest {
    @Test void controlCharactersAreRefusedEverywhere() {
        for (Kind kind : Kind.values())
            for (String bad : new String[] {"a\u0000b", "a\u0001", "\u001Fa", "a\u007F", "a\u0085b", "a\u009Fb", "a\uD800b", "a\uDC00"})
                assertFalse(Text.valid(bad, kind), kind + " " + bad.codePoints().boxed().toList());
    }

    @Test void lineBreaksAndTabsOnlyInMultiLineText() {
        for (String text : new String[] {"a\nb", "a\tb", "a\r\nb", "a b"}) {
            assertTrue(Text.valid(text, Kind.MULTI_LINE), text);
            assertFalse(Text.valid(text, Kind.LINE), text);
            assertFalse(Text.valid(text, Kind.NAME), text);
        }
    }

    @Test void namesRefuseFormatCharactersAndNeedSomethingVisible() {
        for (String bad : new String[] {"‮Ada", "Ada⁦", "A‎da", "​", "⁠⁠", "A﻿da", "ㅤ", " ́ "})
            assertFalse(Text.valid(bad, Kind.NAME), bad.codePoints().boxed().toList().toString());
        for (String good : new String[] {"Ada", "Élise", "דנה", "مريم",
                "👩‍🎨", "नि‍", "Jo­e".replace("­", ""), ""})
            assertTrue(Text.valid(good, Kind.NAME), good);
        // Bidi controls and zero-width characters stay allowed in other text (mixed-direction titles need them).
        assertTrue(Text.valid("‮title​", Kind.LINE));
    }

    @Test void cleanStripsUnicodeWhitespaceAndOptionalMakesBlankNull() {
        assertEquals("Ada", Text.clean("  Ada ", Kind.NAME));
        assertNull(Text.clean(null, Kind.LINE));
        assertNull(Text.optional(" 　 ", Kind.LINE));
        assertEquals("a\nb", Text.optional(" a\nb\n", Kind.MULTI_LINE));
        var refused = assertThrows(Text.InvalidText.class, () -> Text.clean("\u0000", Kind.LINE));
        assertEquals(Text.INVALID_TEXT, refused.getMessage());
        assertEquals(Text.INVALID_NAME, assertThrows(Text.InvalidText.class, () -> Text.clean("​", Kind.NAME)).getMessage());
    }
}
