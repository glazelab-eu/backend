package eu.glazelab.api.controller;

import eu.glazelab.api.dto.ReportDtos.DiagnosticsInput;
import eu.glazelab.api.dto.ReportDtos.ReportBatch;
import eu.glazelab.api.dto.ReportDtos.ReportEvent;
import eu.glazelab.api.exception.ApiErrorHandler;
import eu.glazelab.api.security.CurrentUser;
import eu.glazelab.api.service.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import java.io.IOException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Opt-in error reports (backend guide "Error reports"): the account's switch and the app's batches. Signed-in
 * accounts only (the security chain answers 401 to guests); sending needs the current terms (TermsFilter), and so does
 * turning reports on, while turning them off never does.
 */
@RestController @RequestMapping("/api") @RequiredArgsConstructor
public class ReportController {
    /** Request cap: a batch is read only up to this many bytes. */
    public static final int MAX_BATCH_BYTES = 32 * 1024;
    /** Event cap, measured as the event's JSON. */
    public static final int MAX_EVENT_BYTES = 4 * 1024;
    public static final int MAX_EVENTS = 20;
    public static final String BATCH_TOO_LARGE = "Send at most 32 KB of error reports at once.";
    public static final String TOO_MANY_EVENTS = "Send at most 20 error reports at once.";
    public static final String EVENT_TOO_LARGE = "An error report can be at most 4 KB.";

    private final CurrentUser current;
    private final ReportService reports;
    private final JsonMapper json;
    private final Validator validator;

    /** On or off for every device of the account; off deletes the account's stored reports (also with pending terms). */
    @PutMapping("/account/diagnostics") @ResponseStatus(HttpStatus.NO_CONTENT)
    void chooseDiagnostics(Authentication auth, @Valid @RequestBody DiagnosticsInput body) {
        reports.choose(current.user(auth).getId(), body.enabled());
    }

    /**
     * Stores up to 20 events of an opted-in account (409 {@code ReportService.DIAGNOSTICS_OFF} otherwise, nothing
     * stored). The body is read only up to 32 KB (413 beyond), each event may be at most 4 KB as JSON (400); messages
     * and stacks are scrubbed before they are stored, and nothing of a report is ever logged.
     */
    @Operation(requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ReportBatch.class))))
    @PostMapping(path = "/reports", consumes = MediaType.APPLICATION_JSON_VALUE) @ResponseStatus(HttpStatus.NO_CONTENT)
    void sendReports(Authentication auth, HttpServletRequest request) throws IOException {
        UUID userId = current.user(auth).getId();
        reports.store(userId, batch(request));
    }

    private ReportBatch batch(HttpServletRequest request) throws IOException {
        byte[] bytes = request.getInputStream().readNBytes(MAX_BATCH_BYTES + 1);
        if (bytes.length > MAX_BATCH_BYTES) throw tooLarge();
        ReportBatch batch;
        try { batch = json.readValue(bytes, ReportBatch.class); }
        catch (JacksonException unreadable) { throw invalid(ApiErrorHandler.INVALID_INPUT); }
        if (batch == null) throw invalid(ApiErrorHandler.INVALID_INPUT);
        if (batch.events() != null && batch.events().size() > MAX_EVENTS) throw invalid(TOO_MANY_EVENTS);
        if (!validator.validate(batch).isEmpty()) throw invalid(ApiErrorHandler.INVALID_INPUT);
        for (ReportEvent event : batch.events())
            if (json.writeValueAsBytes(event).length > MAX_EVENT_BYTES) throw invalid(EVENT_TOO_LARGE);
        return batch;
    }

    private static ResponseStatusException tooLarge() {
        return new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, BATCH_TOO_LARGE);
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
