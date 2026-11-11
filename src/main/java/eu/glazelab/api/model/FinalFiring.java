package eu.glazelab.api.model;

import java.time.LocalDate;
import java.util.regex.Pattern;

/**
 * The firing that produced a combination's result, every field optional; earlier or extra firings are
 * custom steps. {@link #of} is the one normaliser: text is trimmed and blank becomes null.
 */
public record FinalFiring(String cone, Integer temperatureC, FiringAtmosphere atmosphere, String clayBody,
                          String kiln, LocalDate firedOn) {
    public static final FinalFiring NONE = new FinalFiring(null, null, null, null, null, null);
    /** An Orton cone as potters write it: an optional leading 0 (022 to 01), then 1 or 2 digits, e.g. 6, 10, 04. */
    private static final Pattern CONE = Pattern.compile("0?[1-9][0-9]?");
    private static final int MIN_TEMPERATURE_C = 100;
    private static final int MAX_TEMPERATURE_C = 1600;
    public static final int CLAY_BODY_LENGTH = 120;
    public static final int KILN_LENGTH = 80;
    public static final String INVALID_CONE = "Enter a cone such as 6 or 04";
    public static final String INVALID_TEMPERATURE = "Enter a temperature from 100 to 1600 °C";
    public static final String CLAY_BODY_TOO_LONG = "A clay body can have at most 120 characters";
    public static final String KILN_TOO_LONG = "A kiln can have at most 80 characters";

    /** A rejected field; the message is one of the public constants above. */
    public static final class InvalidFiring extends IllegalArgumentException {
        InvalidFiring(String message) { super(message); }
    }

    /** The checked, normalised firing of a request; throws {@link InvalidFiring} for a bad field
     *  (or {@link PhotoDetails.InvalidDetails} for a date outside the photo range, also a 400). */
    public static FinalFiring of(String cone, Integer temperatureC, FiringAtmosphere atmosphere, String clayBody,
                                 String kiln, LocalDate firedOn) {
        String normalCone = blankToNull(cone);
        if (normalCone != null && !CONE.matcher(normalCone).matches()) throw new InvalidFiring(INVALID_CONE);
        if (temperatureC != null && (temperatureC < MIN_TEMPERATURE_C || temperatureC > MAX_TEMPERATURE_C))
            throw new InvalidFiring(INVALID_TEMPERATURE);
        String normalClay = blankToNull(clayBody);
        if (normalClay != null && normalClay.length() > CLAY_BODY_LENGTH) throw new InvalidFiring(CLAY_BODY_TOO_LONG);
        String normalKiln = blankToNull(kiln);
        if (normalKiln != null && normalKiln.length() > KILN_LENGTH) throw new InvalidFiring(KILN_TOO_LONG);
        // The same plausible range as a photo's date (and well inside what PostgreSQL stores): 400, never a 409.
        return new FinalFiring(normalCone, temperatureC, atmosphere, normalClay, normalKiln, PhotoDetails.firedOn(firedOn));
    }

    private static String blankToNull(String text) {
        return Text.optional(text, Text.Kind.LINE);
    }
}
