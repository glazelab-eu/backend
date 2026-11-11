package eu.glazelab.api.model;

import java.text.Normalizer;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Tag rules shared by glazes and combinations; frontend lib/tags/tags.ts mirrors them, with the same
 * test table. A tag is lower case words of letters, marks and digits joined by single hyphens.
 * Accents are kept (glänzend and glanzend are different tags); only the Unicode form is unified (NFC).
 */
public final class Tags {
    public static final int MAX_LENGTH = 40;
    public static final int MAX_PER_ITEM = 20;
    public static final String EMPTY = "Use letters or digits in a tag";
    public static final String TOO_LONG = "A tag can have at most 40 characters";
    public static final String TOO_MANY = "An item can have at most 20 tags";
    private static final Pattern SEPARATORS = Pattern.compile("[^\\p{L}\\p{M}\\p{N}]+");
    private static final Pattern EDGES = Pattern.compile("^-|-$");

    private Tags() {}

    /** Thrown for a tag that is empty or too long after normalizing, or one tag too many. */
    public static class InvalidTag extends IllegalArgumentException {
        InvalidTag(String message) { super(message); }
    }

    /** "  Satin Matte ", "satin_matte" and "satin--matte" all become satin-matte; may return "". */
    public static String normalize(String tag) {
        String lower = Normalizer.normalize(tag, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        return EDGES.matcher(SEPARATORS.matcher(lower).replaceAll("-")).replaceAll("");
    }

    /** The normalized tag, or InvalidTag when nothing or too much remains. */
    public static String valid(String tag) {
        String normalized = normalize(tag);
        if (normalized.isEmpty()) throw new InvalidTag(EMPTY);
        if (normalized.length() > MAX_LENGTH) throw new InvalidTag(TOO_LONG);
        return normalized;
    }

    /** The normalized tags of a new or fully replaced item; null means none. */
    public static Set<String> of(List<String> tags) {
        return edited(Set.of(), tags == null ? List.of() : tags, List.of());
    }

    /** The tags after removing, then adding (both normalized, so duplicates collapse), in order. */
    public static Set<String> edited(Set<String> tags, Collection<String> add, Collection<String> remove) {
        Set<String> result = new TreeSet<>(tags);
        for (String tag : remove) result.remove(normalize(tag));
        for (String tag : add) result.add(valid(tag));
        if (result.size() > MAX_PER_ITEM) throw new InvalidTag(TOO_MANY);
        return result;
    }
}
