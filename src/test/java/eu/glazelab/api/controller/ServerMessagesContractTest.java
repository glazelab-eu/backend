package eu.glazelab.api.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Every English message the API answers has a key in the frontend's {@code lib/i18n/server-messages.ts} (guide topic
 * "Contract"), so a changed wording fails here instead of showing English in every language. Reads
 * the main sources: each literal or string constant passed where an answer's message goes (a
 * {@code ResponseStatusException}, {@code SecurityConfig.error}, an {@code ErrorView}, a status case of
 * {@code ApiErrorHandler}, a model {@code Invalid…} exception or a Bean Validation {@code message}). Skips when
 * {@code ../frontend} is absent.
 */
class ServerMessagesContractTest {
    private static final Path SOURCES = Path.of("src/main/java");
    private static final Path TRANSLATIONS = Path.of("../frontend/src/lib/i18n/server-messages.ts");
    /**
     * Answered on purpose without a translation key; the client shows its generic error: the 500 text, and a negative
     * page number, which only a hand-made request sends.
     */
    private static final Set<String> UNTRANSLATED = Set.of("Request failed.", "Invalid page");

    private static final String LITERAL = "\"(?:[^\"\\\\]|\\\\.)*\"";
    private static final String ARGUMENT = "(" + LITERAL + "(?:\\s*\\+\\s*" + LITERAL + ")*|[A-Z][A-Za-z]*\\.[A-Z][A-Z0-9_]*|[A-Z][A-Z0-9_]+)";
    private static final Pattern[] SITES = {
        Pattern.compile("new ResponseStatusException\\(\\s*HttpStatus\\.\\w+\\s*,\\s*" + ARGUMENT + "\\s*\\)"),
        Pattern.compile("\\berror\\(\\s*response\\s*,[^,;]+,\\s*" + ARGUMENT + "\\s*\\)"),
        Pattern.compile("new ErrorView\\(\\s*" + ARGUMENT + "\\s*\\)"),
        Pattern.compile("case \\d+ -> " + ARGUMENT + ";"),
        Pattern.compile("new \\w*Invalid\\w*\\(\\s*" + ARGUMENT + "\\s*[,)]"),
        Pattern.compile("\\bmessage\\s*=\\s*" + ARGUMENT),
        Pattern.compile("\\brefused\\(\\s*" + LITERAL + "\\s*,\\s*" + ARGUMENT + "\\s*\\)"),
    };
    private static final Pattern CONSTANT = Pattern.compile(
            "static final String ([A-Z][A-Z0-9_]*)\\s*=\\s*(" + LITERAL + "(?:\\s*\\+\\s*" + LITERAL + ")*)\\s*;");
    private static final Pattern KEY = Pattern.compile("^\\s*(" + LITERAL + "|'(?:[^'\\\\]|\\\\.)*')\\s*:", Pattern.MULTILINE);

    /** A literal (or concatenated literals) as its Java value; a single-quoted TypeScript string too. */
    private static String unquote(String text) {
        var value = new StringBuilder();
        Matcher part = Pattern.compile(LITERAL + "|'(?:[^'\\\\]|\\\\.)*'").matcher(text);
        while (part.find()) {
            String quoted = part.group();
            value.append(quoted, 1, quoted.length() - 1);
        }
        return value.toString().replace("\\\"", "\"").replace("\\'", "'").replace("\\\\", "\\");
    }

    /** Every message the main sources answer, with where it is answered. */
    static Map<String, String> answered() throws IOException {
        Map<String, String> sources = new HashMap<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList())
                sources.put(file.getFileName().toString().replace(".java", ""), Files.readString(file));
        }
        Map<String, String> constants = new HashMap<>();
        sources.forEach((type, text) -> {
            Matcher constant = CONSTANT.matcher(text);
            while (constant.find()) constants.put(type + "." + constant.group(1), unquote(constant.group(2)));
        });
        Map<String, String> messages = new TreeMap<>();
        sources.forEach((type, text) -> {
            for (Pattern site : SITES) {
                Matcher use = site.matcher(text);
                while (use.find()) {
                    String argument = use.group(1);
                    String message = argument.startsWith("\"") ? unquote(argument)
                            : constants.get(argument.contains(".") ? argument : type + "." + argument);
                    // A reference to anything but a string constant (a variable, an enum) is assembled at run time.
                    if (message != null) messages.putIfAbsent(message, type);
                }
            }
        });
        return messages;
    }

    @Test void theScanFindsTheKnownSitesOfEveryKind() throws IOException {
        var messages = answered();
        for (String known : new String[] {"This changed meanwhile. Please try again.", "Too many requests. Please wait a moment and try again.",
                "Unsupported image", "Use letters and digits for the glaze code, such as PC-20", "Choose an image under 12 MB"})
            assertTrue(messages.containsKey(known), known + " in " + messages.keySet());
    }

    @Test void everyAnsweredMessageHasAFrontendTranslation() throws IOException {
        assumeTrue(Files.exists(TRANSLATIONS), "../frontend is absent");
        Set<String> keys = new TreeSet<>();
        Matcher key = KEY.matcher(Files.readString(TRANSLATIONS));
        while (key.find()) keys.add(unquote(key.group(1)));
        Map<String, String> missing = new TreeMap<>(answered());
        missing.keySet().removeAll(keys);
        missing.keySet().removeAll(UNTRANSLATED);
        assertEquals(Map.of(), missing, "answered by the API (message -> class) but missing in " + TRANSLATIONS);
    }
}
