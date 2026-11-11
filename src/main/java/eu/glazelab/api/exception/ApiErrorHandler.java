package eu.glazelab.api.exception;

import eu.glazelab.api.model.Barcodes;
import eu.glazelab.api.model.FinalFiring;
import eu.glazelab.api.model.PhotoDetails;
import eu.glazelab.api.model.Tags;
import eu.glazelab.api.security.BodySizeFilter;
import eu.glazelab.api.model.Text;
import eu.glazelab.api.dto.CommonDtos.ErrorView;
import eu.glazelab.api.dto.GlazeDtos.GlazeInUseView;
import java.sql.SQLException;
import java.util.Set;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiErrorHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);
    public static final String DUPLICATE = "This record already exists";
    public static final String CHANGED_MEANWHILE = "This changed meanwhile. Please try again.";
    private static final String UNIQUE_VIOLATION = "23505";
    /** PostgreSQL 23514; H2 answers a check violation with 23513. */
    private static final Set<String> CHECK_VIOLATION = Set.of("23514", "23513");
    private static final String DATA_EXCEPTION_CLASS = "22";
    private static final String PLAIN = Text.Plain.class.getSimpleName();
    public static final String INVALID_INPUT = "Please check the form fields and try again.";
    private static final String REQUEST_FAILED = "Request failed.";
    @ExceptionHandler(GlazeInUseException.class)
    ResponseEntity<GlazeInUseView> glazeInUse(GlazeInUseException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ex.getView());
    }
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ErrorView> status(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(new ErrorView(ex.getReason() == null ? REQUEST_FAILED : ex.getReason()));
    }
    /** Cover Spring MVC binding, parsing, multipart, routing and content-negotiation failures uniformly. */
    @Override protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String message = switch (status.value()) {
            case 400 -> INVALID_INPUT;
            case 413 -> "Choose an image under 12 MB";
            default -> REQUEST_FAILED;
        };
        // A character rule (model.Text) answers its public message alone; other field errors name their field.
        if (ex instanceof MethodArgumentNotValidException validation)
            message = validation.getBindingResult().getFieldErrors().stream().findFirst()
                    .map(e -> PLAIN.equals(e.getCode()) ? e.getDefaultMessage() : e.getField() + ": " + e.getDefaultMessage())
                    .orElse(INVALID_INPUT);
        // A chunked body read past the cap (security.BodySizeFilter) arrives wrapped as an unreadable message.
        for (Throwable cause = ex.getCause(); cause != null; cause = cause.getCause() == cause ? null : cause.getCause())
            if (cause instanceof BodySizeFilter.TooLarge) {
                status = HttpStatus.CONTENT_TOO_LARGE;
                message = BodySizeFilter.TOO_LARGE;
                break;
            }
        HttpHeaders safeHeaders = new HttpHeaders();
        safeHeaders.putAll(headers); // Keep Allow, Accept and other protocol headers from Spring.
        safeHeaders.setContentType(MediaType.APPLICATION_JSON);
        return super.handleExceptionInternal(ex, new ErrorView(message), safeHeaders, status, request);
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorView> unexpected(Exception ex) throws Exception {
        // Let the security chain retain its 401/403 handling if a controller raises a security exception.
        if (ex instanceof AuthenticationException || ex instanceof AccessDeniedException) throw ex;
        log.error("Unhandled API request failure", ex);
        return ResponseEntity.internalServerError().body(new ErrorView(REQUEST_FAILED));
    }
    @ExceptionHandler(Tags.InvalidTag.class)
    ResponseEntity<ErrorView> invalidTag(Tags.InvalidTag ex) {
        return ResponseEntity.badRequest().body(new ErrorView(ex.getMessage()));
    }
    @ExceptionHandler(Barcodes.InvalidBarcode.class)
    ResponseEntity<ErrorView> invalidBarcode(Barcodes.InvalidBarcode ex) {
        return ResponseEntity.badRequest().body(new ErrorView(ex.getMessage()));
    }
    @ExceptionHandler(FinalFiring.InvalidFiring.class)
    ResponseEntity<ErrorView> invalidFiring(FinalFiring.InvalidFiring ex) {
        return ResponseEntity.badRequest().body(new ErrorView(ex.getMessage()));
    }
    @ExceptionHandler(PhotoDetails.InvalidDetails.class)
    ResponseEntity<ErrorView> invalidPhotoDetails(PhotoDetails.InvalidDetails ex) {
        return ResponseEntity.badRequest().body(new ErrorView(ex.getMessage()));
    }
    @ExceptionHandler(Text.InvalidText.class)
    ResponseEntity<ErrorView> invalidText(Text.InvalidText ex) {
        return ResponseEntity.badRequest().body(new ErrorView(ex.getMessage()));
    }
    /**
     * By SQLState: a unique violation (23505) is a duplicate (409); a value the database refuses (data exception,
     * class 22, such as NUL in text, or a check violation, 23514 and H2's 23513) is invalid input (400), which a retry cannot fix;
     * anything else, a foreign key hit by a concurrent delete (23503) included, is a concurrent change (409).
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorView> conflict(DataIntegrityViolationException ex) {
        if (hasState(ex, state -> state.startsWith(DATA_EXCEPTION_CLASS) || CHECK_VIOLATION.contains(state)))
            return ResponseEntity.badRequest().body(new ErrorView(INVALID_INPUT));
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorView(isUniqueViolation(ex) ? DUPLICATE : CHANGED_MEANWHILE));
    }
    public static boolean isUniqueViolation(Throwable error) {
        return hasState(error, UNIQUE_VIOLATION::equals);
    }
    private static boolean hasState(Throwable error, Predicate<String> state) {
        for (Throwable cause = error; cause != null; cause = cause.getCause() == cause ? null : cause.getCause())
            if (cause instanceof SQLException sql && sql.getSQLState() != null && state.test(sql.getSQLState())) return true;
        return false;
    }
}
