package eu.glazelab.api.model;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class TagsTest {
    /** The same table as frontend src/lib/tags/tags.test.ts; change both together. */
    @ParameterizedTest(name = "[{0}] -> [{1}]")
    @CsvSource(delimiter = '|', quoteCharacter = '"', textBlock = """
            "  Satin Matte "      | satin-matte
            satin_matte           | satin-matte
            satin--matte          | satin-matte
            Satin-Matte           | satin-matte
            Glänzend              | glänzend
            glanzend              | glanzend
            "Çor  Azul"           | çor-azul
            "Café"          | café
            "Cone 6 / Oxidation"  | cone-6-oxidation
            UPPER.case            | upper-case
            "#food-safe!"         | food-safe
            tenmoku_2             | tenmoku-2
            ΟΔΟΣ                  | οδος
            "日本 釉薬"            | 日本-釉薬
            "--"                  | ""
            "🔥"                  | ""
            "   "                 | ""
            """)
    void normalizesToLowerCaseWordsJoinedByHyphens(String input, String expected) {
        assertEquals(expected, Tags.normalize(input));
    }

    @Test void limitsLengthAndRejectsEmptyTags() {
        assertEquals("a".repeat(40), Tags.valid(" " + "A".repeat(40) + " "));
        assertEquals(Tags.TOO_LONG, assertThrows(Tags.InvalidTag.class, () -> Tags.valid("a".repeat(41))).getMessage());
        assertEquals(Tags.EMPTY, assertThrows(Tags.InvalidTag.class, () -> Tags.valid(" -- ")).getMessage());
    }

    @Test void editsRemoveThenAddNormalizedTagsWithinTheLimit() {
        assertEquals(List.of("glossy", "satin-matte"),
                List.copyOf(Tags.edited(Set.of("old", "satin-matte"), List.of("Satin Matte", " glossy", "GLOSSY"), List.of("OLD", "absent"))));
        assertEquals(Set.of("cone-6"), Tags.edited(Set.of("cone-6"), List.of("Cone 6"), List.of("cone 6")));
        Set<String> full = Tags.edited(Set.of(), java.util.stream.IntStream.range(0, 20).mapToObj(i -> "t" + i).toList(), List.of());
        assertEquals(20, full.size());
        assertEquals(Tags.TOO_MANY, assertThrows(Tags.InvalidTag.class,
                () -> Tags.edited(full, List.of("one-more"), List.of())).getMessage());
        assertEquals(20, Tags.edited(full, List.of("one-more"), List.of("t0")).size());
    }

    @Test void ofNormalizesANewItemsTagsAndTreatsNullAsNone() {
        assertEquals(Set.of(), Tags.of(null));
        assertEquals(List.of("cone-6", "glossy"), List.copyOf(Tags.of(List.of("Glossy", "cone 6", "GLOSSY"))));
        assertEquals(Tags.TOO_MANY, assertThrows(Tags.InvalidTag.class,
                () -> Tags.of(java.util.stream.IntStream.range(0, 21).mapToObj(i -> "t" + i).toList())).getMessage());
    }
}
