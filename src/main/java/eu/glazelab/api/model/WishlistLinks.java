package eu.glazelab.api.model;

import java.util.regex.Pattern;

/**
 * The link of a club wishlist item: an absolute {@code http} or {@code https} URL of printable ASCII, at most
 * {@link #MAX_LENGTH} characters, with a host name (letters, digits, hyphens and dots) or a bracketed IPv6 address, an
 * optional port, path, query and fragment of RFC 3986 characters with valid percent escapes, and never credentials
 * ({@code user:pass@}), white space, control characters or a backslash. One strict grammar instead of a lenient parser,
 * so the server and the app agree on every link: the frontend's {@code lib/clubs/wishlist.ts} checks the same grammar
 * and shares the test table {@code wishlist-links.json}; change both together. The server stores the link only: it
 * never fetches, previews or redirects through it.
 */
public final class WishlistLinks {
    public static final int MAX_LENGTH = 2000;
    public static final String INVALID = "Enter a link that starts with http:// or https://, without spaces or sign-in details, up to 2000 characters.";
    public static final String REQUIRED = "Add a link for the item.";

    /** The characters of a path segment (RFC 3986 {@code pchar}), the percent sign checked separately. */
    private static final String PCHAR = "A-Za-z0-9\\-._~!$&'()*+,;=:@%";
    private static final Pattern LINK = Pattern.compile("[Hh][Tt][Tt][Pp][Ss]?://([A-Za-z0-9.-]+|\\[[0-9A-Fa-f:.]+\\])(?::[0-9]{1,5})?"
            + "(?:/[" + PCHAR + "/]*)?(?:\\?[" + PCHAR + "/?]*)?(?:#[" + PCHAR + "/?]*)?");
    private static final Pattern BAD_ESCAPE = Pattern.compile("%(?![0-9A-Fa-f]{2})");
    private static final Pattern LABEL = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?");

    private WishlistLinks() {}

    /** True for a link of the grammar above, as stored (no surrounding white space). */
    public static boolean valid(String link) {
        if (link == null || link.isEmpty() || link.length() > MAX_LENGTH) return false;
        var match = LINK.matcher(link);
        if (!match.matches() || BAD_ESCAPE.matcher(link).find()) return false;
        String host = match.group(1);
        if (host.startsWith("[")) return true;
        String[] labels = (host.endsWith(".") ? host.substring(0, host.length() - 1) : host).split("\\.", -1);
        for (String label : labels) if (!LABEL.matcher(label).matches()) return false;
        return true;
    }

    /** The stripped link ({@link Text#strip}), or 400 {@link #INVALID} ({@link Text.InvalidText}); null or blank is {@link #REQUIRED}. */
    public static String clean(String link) {
        String stripped = link == null ? "" : Text.strip(link);
        if (stripped.isEmpty()) throw new Text.InvalidText(REQUIRED);
        if (!valid(stripped)) throw new Text.InvalidText(INVALID);
        return stripped;
    }
}
