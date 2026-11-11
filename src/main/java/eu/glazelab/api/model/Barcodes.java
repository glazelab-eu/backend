package eu.glazelab.api.model;

import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;

/**
 * Retail barcodes (GTINs) of glaze packaging. One GTIN names one trade item worldwide, so a barcode
 * belongs to at most one glaze in the whole library; a glaze may have several (jar sizes, powder).
 * Accepted: EAN-13, UPC-A and EAN-8 with a valid GS1 check digit; spaces and hyphens are ignored.
 * UPC-A is stored as its EAN-13 form (a leading 0), so either scan finds the glaze. The frontend's
 * lib/barcodes/barcodes.ts normalizes identically and shares the test table; change both together.
 */
public final class Barcodes {
    public static final int MAX_PER_GLAZE = 10;
    public static final String INVALID = "Enter an EAN-13, UPC-A or EAN-8 barcode with a valid check digit.";
    public static final String TOO_MANY = "A glaze can have at most 10 barcodes.";

    private Barcodes() {}

    /** Thrown for a value that is no valid EAN-13, UPC-A or EAN-8, or one barcode too many. */
    public static class InvalidBarcode extends IllegalArgumentException {
        InvalidBarcode(String message) { super(message); }
    }

    /** The stored form (13 or 8 digits), or null when the value is not a valid barcode. */
    public static String normalize(String value) {
        if (value == null) return null;
        String digits = value.replaceAll("[\\s-]", "");
        if (!digits.matches("[0-9]{8}|[0-9]{12,13}")) return null;
        if (digits.length() == 12) digits = "0" + digits;
        return checkDigitValid(digits) ? digits : null;
    }

    /** The stored form, or InvalidBarcode. */
    public static String valid(String value) {
        String barcode = normalize(value);
        if (barcode == null) throw new InvalidBarcode(INVALID);
        return barcode;
    }

    /** GS1 mod-10: from the right, excluding the check digit, weights 3, 1, 3, ... */
    static boolean checkDigitValid(String digits) {
        int sum = 0;
        for (int i = digits.length() - 2, weight = 3; i >= 0; i--, weight = 4 - weight) {
            sum += (digits.charAt(i) - '0') * weight;
        }
        return (10 - sum % 10) % 10 == digits.charAt(digits.length() - 1) - '0';
    }

    /** The barcodes after removing, then adding (both normalized), within the limit. */
    public static Set<String> edited(Set<String> barcodes, Collection<String> add, Collection<String> remove) {
        Set<String> result = new TreeSet<>(barcodes);
        for (String barcode : remove) {
            String normalized = normalize(barcode);
            if (normalized != null) result.remove(normalized);
        }
        for (String barcode : add) result.add(valid(barcode));
        if (result.size() > MAX_PER_GLAZE) throw new InvalidBarcode(TOO_MANY);
        return result;
    }
}
