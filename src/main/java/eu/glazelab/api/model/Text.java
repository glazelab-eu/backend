package eu.glazelab.api.model;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The one character rule and normaliser of free text. Every kind refuses C0 and C1 control characters (NUL included,
 * which PostgreSQL cannot store) and unpaired surrogates; {@link Kind#MULTI_LINE} allows tab, line feed and carriage
 * return; {@link Kind#NAME} also refuses line separators and format characters (bidi controls, zero-width spaces, word
 * joiners) except the zero-width joiner and non-joiner that emoji and several scripts need, and needs a visible
 * character. Text is stripped of Unicode white space at both ends ({@link #strip}, never {@code trim}, which also eats
 * control characters instead of refusing them, and keeps no-break spaces).
 */
public final class Text {
    public static final String INVALID_TEXT = "Remove control characters from the text.";
    public static final String INVALID_NAME = "Use visible characters in a name, without control or formatting characters.";

    public enum Kind {
        /** A title, label or caption: one line. */
        LINE,
        /** Notes, descriptions, house rules, reasons, feedback. */
        MULTI_LINE,
        /** A display name or club name, shown inside sentences: one line, no format characters, something visible. */
        NAME;

        String message() { return this == NAME ? INVALID_NAME : INVALID_TEXT; }
    }

    /** A refused text; the message is {@link #INVALID_TEXT} or {@link #INVALID_NAME} (400). */
    public static final class InvalidText extends IllegalArgumentException {
        InvalidText(String message) { super(message); }
    }

    private Text() {}

    /** True when the characters of {@code value} (null included) are allowed for {@code kind}. */
    public static boolean valid(String value, Kind kind) {
        if (value == null) return true;
        boolean visible = false;
        for (int i = 0; i < value.length(); ) {
            int c = value.codePointAt(i);
            i += Character.charCount(c);
            if (Character.isSurrogate((char) c) && c <= 0xFFFF) return false;
            if (c < 0x20 || (c >= 0x7F && c <= 0x9F)) {
                if (kind != Kind.MULTI_LINE || (c != '\t' && c != '\n' && c != '\r')) return false;
                continue;
            }
            if (kind == Kind.MULTI_LINE) continue;
            if (c == 0x2028 || c == 0x2029) return false;
            if (kind == Kind.NAME) {
                int type = Character.getType(c);
                if (type == Character.FORMAT && c != 0x200C && c != 0x200D && !(c >= 0xE0020 && c <= 0xE007F)) return false;
                visible |= visible(c, type);
            }
        }
        return kind != Kind.NAME || visible || value.isEmpty();
    }

    private static boolean visible(int c, int type) {
        return !Character.isWhitespace(c) && !Character.isSpaceChar(c) && type != Character.FORMAT
                && type != Character.NON_SPACING_MARK && type != Character.ENCLOSING_MARK && type != Character.UNASSIGNED
                && c != 0x115F && c != 0x1160 && c != 0x3164 && c != 0xFFA0 && c != 0x2800;
    }

    /** Throws {@link InvalidText} unless {@link #valid}; returns the value unchanged. */
    public static String check(String value, Kind kind) {
        if (!valid(value, kind)) throw new InvalidText(kind.message());
        return value;
    }

    /** Checked and stripped; null stays null. */
    public static String clean(String value, Kind kind) {
        return value == null ? null : strip(check(value, kind));
    }

    /**
     * Without white space at both ends: everything {@link String#strip} removes plus the no-break spaces and U+FEFF, as
     * JavaScript's {@code trim} does in the app, so both sides agree on what a blank field is.
     */
    public static String strip(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && space(value.charAt(start))) start++;
        while (end > start && space(value.charAt(end - 1))) end--;
        return value.substring(start, end);
    }

    private static boolean space(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '\uFEFF';
    }

    /** Checked and stripped; null or blank is null. */
    public static String optional(String value, Kind kind) {
        String clean = clean(value, kind);
        return clean == null || clean.isEmpty() ? null : clean;
    }

    /**
     * Bean Validation of a request field (also {@code List<@Plain String>} and {@code Optional<String>}): the field
     * answers 400 with the kind's message alone, before any lookup or write.
     */
    @Documented
    @Constraint(validatedBy = PlainValidator.class)
    @Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.TYPE_USE, ElementType.METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Plain {
        Kind value() default Kind.LINE;
        String message() default "";
        Class<?>[] groups() default {};
        Class<? extends Payload>[] payload() default {};
    }

    public static final class PlainValidator implements ConstraintValidator<Plain, String> {
        private Kind kind;

        @Override public void initialize(Plain annotation) { kind = annotation.value(); }

        @Override public boolean isValid(String value, ConstraintValidatorContext context) {
            if (valid(value, kind)) return true;
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(kind.message()).addConstraintViolation();
            return false;
        }
    }
}
