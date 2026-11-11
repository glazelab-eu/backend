package eu.glazelab.api.model;

import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class BarcodesTest {
    /** The same table as frontend src/lib/barcodes/barcodes.test.ts; change both together ("" = invalid). */
    @ParameterizedTest(name = "[{0}] -> [{1}]")
    @CsvSource(delimiter = '|', quoteCharacter = '"', textBlock = """
            4006381333931         | 4006381333931
            " 4006381 333931 "    | 4006381333931
            4-006381-333931       | 4006381333931
            036000291452          | 0036000291452
            0036000291452         | 0036000291452
            "0 36000 29145 2"     | 0036000291452
            96385074              | 96385074
            4006381333932         | ""
            036000291453          | ""
            96385075              | ""
            400638133393          | ""
            40063813339310        | ""
            4006381333931X        | ""
            "4006381.333931"      | ""
            "４００６３８１３３３９３１" | ""
            "   "                 | ""
            """)
    void acceptsEan13UpcAAndEan8WithValidCheckDigits(String input, String expected) {
        assertEquals(expected.isEmpty() ? null : expected, Barcodes.normalize(input));
    }

    @Test void validThrowsForInvalidValuesAndEditsKeepTheLimit() {
        assertEquals(Barcodes.INVALID, assertThrows(Barcodes.InvalidBarcode.class, () -> Barcodes.valid("123")).getMessage());
        assertNull(Barcodes.normalize(null));
        assertEquals(Set.of("0036000291452", "96385074"),
                Barcodes.edited(Set.of("4006381333931"), List.of("036000291452", "9638 5074"), List.of("4006381-333931", "absent")));
        Set<String> full = Barcodes.edited(Set.of(), IntStream.range(0, 10).mapToObj(BarcodesTest::ean13).toList(), List.of());
        assertEquals(10, full.size());
        assertEquals(Barcodes.TOO_MANY, assertThrows(Barcodes.InvalidBarcode.class,
                () -> Barcodes.edited(full, List.of(ean13(10)), List.of())).getMessage());
        assertEquals(10, Barcodes.edited(full, List.of(ean13(10)), List.of(ean13(0))).size());
    }

    /** A valid EAN-13 with the number as its last payload digits. */
    static String ean13(int number) {
        String payload = String.format("400000%06d", number);
        for (int check = 0; check < 10; check++) {
            if (Barcodes.checkDigitValid(payload + check)) return payload + check;
        }
        throw new IllegalStateException();
    }
}
