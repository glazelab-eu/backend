package eu.glazelab.api.model;

import eu.glazelab.api.dto.GlazeDtos.NewGlaze;
import jakarta.validation.Validation;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class GlazeCodeTest {
    private record CodeCase(String input, String normalized, boolean valid) {}

    @Test void sharesPrintedCodeNormalizationAndValidationWithTheFrontend() throws Exception {
        Path fixture = Path.of("../frontend/src/lib/glazes/code-cases.json");
        assumeTrue(Files.exists(fixture), "frontend normalization vectors are not available");
        CodeCase[] cases = JsonMapper.builder().build().readValue(Files.readString(fixture), CodeCase[].class);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            for (CodeCase row : cases) {
                assertEquals(row.normalized(), Glaze.code(row.input()), row.input());
                NewGlaze input = new NewGlaze(null, null, "Test", row.input(), null, null, null, null);
                assertEquals(row.normalized(), input.code(), row.input());
                assertEquals(row.valid(), factory.getValidator().validate(input).isEmpty(), row.input());
            }
        }
    }
}
