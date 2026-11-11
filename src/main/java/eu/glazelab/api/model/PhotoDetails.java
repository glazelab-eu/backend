package eu.glazelab.api.model;

import java.time.LocalDate;

/**
 * The optional details of a photo: a caption and the date it was fired. These are the one normaliser and
 * range of both (upload and PATCH): text is trimmed and blank becomes null, and the date must be a plausible
 * one (1900 to 2100, the range a combination's final-firing date shares through {@link FinalFiring#of}).
 */
public final class PhotoDetails {
    private PhotoDetails() {}
    public static final int MAX_CAPTION = 200;
    public static final LocalDate EARLIEST = LocalDate.of(1900, 1, 1);
    public static final LocalDate LATEST = LocalDate.of(2100, 12, 31);
    public static final String CAPTION_TOO_LONG = "A caption can have at most 200 characters";
    public static final String DATE_OUT_OF_RANGE = "Enter a firing date between 1900 and 2100";
    public static final String FOCUS_INVALID = "A focus point needs both coordinates, each from 0 to 100";
    /** The largest focus coordinate: percent of the width or height. */
    public static final int MAX_FOCUS = 100;

    /** A rejected field; the message is one of the public constants above. */
    public static final class InvalidDetails extends IllegalArgumentException {
        InvalidDetails(String message) { super(message); }
    }

    /** The caption checked and stripped ({@link Text}), null when blank; the length counts after stripping. */
    public static String caption(String caption) {
        String normal = Text.optional(caption, Text.Kind.LINE);
        if (normal == null) return null;
        if (normal.length() > MAX_CAPTION) throw new InvalidDetails(CAPTION_TOO_LONG);
        return normal;
    }

    /** The date itself, or null; throws {@link InvalidDetails} outside 1900 to 2100. */
    public static LocalDate firedOn(LocalDate firedOn) {
        if (firedOn != null && (firedOn.isBefore(EARLIEST) || firedOn.isAfter(LATEST))) throw new InvalidDetails(DATE_OUT_OF_RANGE);
        return firedOn;
    }

    /** Where covers centre their crop, in percent of the width and height; {@code ImageRecord}'s {@code ck_image_focus} backs it. */
    public record Focus(int x, int y) {}

    /**
     * Both coordinates (0 to {@link #MAX_FOCUS}) as a focus, or null for neither (the centre); throws {@link InvalidDetails}
     * for one without the other or a value outside the range.
     */
    public static Focus focus(Integer x, Integer y) {
        if (x == null && y == null) return null;
        if (x == null || y == null || x < 0 || x > MAX_FOCUS || y < 0 || y > MAX_FOCUS) throw new InvalidDetails(FOCUS_INVALID);
        return new Focus(x, y);
    }
}
