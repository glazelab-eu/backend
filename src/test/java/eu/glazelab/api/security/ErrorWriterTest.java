package eu.glazelab.api.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/** The security layer's one error writer escapes whatever message it is given. */
class ErrorWriterTest {
    @Test void escapesQuotesBackslashesControlCharactersAndKeepsUtf8() throws IOException {
        String message = "He said \"stop\" \\ line\nbreak\t</script> Glasur über é";
        MockHttpServletResponse response = new MockHttpServletResponse();
        SecurityConfig.error(response, 418, message);
        assertEquals(418, response.getStatus());
        assertEquals(MediaType.APPLICATION_JSON_VALUE, response.getContentType().split(";")[0]);
        Map<?, ?> body = JsonMapper.builder().build().readValue(response.getContentAsByteArray(), Map.class);
        assertEquals(Map.of("error", message), body);
        assertEquals(StandardCharsets.UTF_8.name(), response.getCharacterEncoding());
    }

    @Test void writesTheSharedSignInMessage() throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        SecurityConfig.error(response, 401, CurrentUser.SIGN_IN_REQUIRED);
        assertEquals("{\"error\":\"Please sign in.\"}", response.getContentAsString(StandardCharsets.UTF_8));
    }
}
