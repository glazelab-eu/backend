package eu.glazelab.api.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The server's scrubbing of error report text (backend guide "Error reports"), the second line of defence after the
 * app's own: removes control characters and unpaired surrogates (PostgreSQL refuses NUL), bearer values,
 * {@code name=value} pairs whose name says token, password, secret, session, CSRF/XSRF, authorization or API key, email
 * addresses, query strings, URL fragments (every hash route, so {@code #/buddy/<token>}, {@code #/invite/…} and
 * {@code #/reset/…} too) and every token-shaped run of 32 or more base64url characters except a UUID, then truncates.
 * Pure, so the vectors in {@code ReportScrubberTest} pin it.
 */
public final class ReportScrubber {
    public static final int MESSAGE_LENGTH = 1000;
    public static final int STACK_LENGTH = 4000;
    static final String REMOVED = "[removed]";
    static final String EMAIL = "[email]";
    static final String TOKEN = "[token]";

    private static final Pattern BEARER = Pattern.compile("(?i)\\b(bearer)\\s+[^\\s\"'<>()]+");
    private static final Pattern SECRET_PAIR = Pattern.compile("(?i)(?<![\\w-])([\\w-]*(?:token|password|passwd|secret|session|csrf|xsrf"
            + "|authorization|api[_-]?key|cookie)[\\w-]*)([\"']?\\s*[=:]\\s*[\"']?)[^\\s\"'<>(),;&]+");
    private static final Pattern EMAIL_ADDRESS = Pattern.compile(
            "(?<![\\p{L}\\p{N}._%+-])[\\p{L}\\p{N}._%+-]+@[\\p{L}\\p{N}-]+(?:\\.[\\p{L}\\p{N}-]+)*\\.\\p{L}{2,}");
    private static final Pattern QUERY = Pattern.compile("\\?[^\\s\"'<>()#]+");
    private static final Pattern FRAGMENT = Pattern.compile("#[^\\s\"'<>()]+");
    private static final Pattern TOKEN_SHAPED = Pattern.compile("[A-Za-z0-9_-]{32,}");
    private static final Pattern UUID_SHAPED = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern DIGITS = Pattern.compile("[0-9]+");

    private ReportScrubber() {}

    /** The scrubbed text, at most {@code max} characters (a cut one ends with an ellipsis); null stays null. */
    public static String scrub(String text, int max) {
        if (text == null) return null;
        String clean = printable(text);
        clean = BEARER.matcher(clean).replaceAll("$1 " + Matcher.quoteReplacement(REMOVED));
        clean = SECRET_PAIR.matcher(clean).replaceAll("$1$2" + Matcher.quoteReplacement(REMOVED));
        clean = EMAIL_ADDRESS.matcher(clean).replaceAll(Matcher.quoteReplacement(EMAIL));
        clean = QUERY.matcher(clean).replaceAll(Matcher.quoteReplacement("?" + REMOVED));
        clean = FRAGMENT.matcher(clean).replaceAll(Matcher.quoteReplacement("#" + REMOVED));
        clean = TOKEN_SHAPED.matcher(clean).replaceAll(match -> UUID_SHAPED.matcher(match.group()).matches()
                ? match.group() : Matcher.quoteReplacement(TOKEN));
        return truncate(clean, max);
    }

    /**
     * Groups repeats of one problem: SHA-256 (hex) of the kind and the scrubbed message with every digit run written as
     * 0, so "index 3" and "index 4" group, and builds of the app (whose stacks differ) share one group.
     */
    public static String fingerprint(String kind, String scrubbedMessage) {
        String key = kind + "\n" + DIGITS.matcher(scrubbedMessage).replaceAll("0");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Drops control characters except newline and tab, and unpaired surrogates. */
    private static String printable(String text) {
        var out = new StringBuilder(text.length());
        text.codePoints().forEach(point -> {
            if (Character.isSurrogate((char) point) && Character.isBmpCodePoint(point)) return;
            if (Character.getType(point) == Character.CONTROL && point != '\n' && point != '\t') return;
            out.appendCodePoint(point);
        });
        return out.toString();
    }

    private static String truncate(String text, int max) {
        if (text.length() <= max) return text;
        int end = max - 1;
        if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end) + "…";
    }
}
