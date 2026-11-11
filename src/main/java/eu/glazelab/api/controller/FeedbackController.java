package eu.glazelab.api.controller;

import eu.glazelab.api.model.Text;
import eu.glazelab.api.dto.CommonDtos.IdView;
import eu.glazelab.api.dto.FeedbackDtos.FeedbackPageView;
import eu.glazelab.api.dto.FeedbackDtos.FeedbackStatusInput;
import eu.glazelab.api.exception.ApiErrorHandler;
import eu.glazelab.api.model.Feedback;
import eu.glazelab.api.model.FeedbackPhoto;
import eu.glazelab.api.repository.FeedbackPhotoRepository;
import eu.glazelab.api.security.CurrentUser;
import eu.glazelab.api.service.FeedbackService;
import eu.glazelab.api.service.FeedbackService.Context;
import eu.glazelab.api.service.FeedbackService.StoredPhoto;
import eu.glazelab.api.storage.ImageStorage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * In-app feedback (backend guide "Feedback"): any signed-in account sends (the security chain answers 401 to guests,
 * the terms filter 428 with pending terms, the FEEDBACK budget 429); only site admins read, mark and delete it.
 */
@RestController @RequestMapping("/api") @RequiredArgsConstructor
public class FeedbackController {
    public static final String MESSAGE_REQUIRED = "Write your feedback first.";
    public static final String MESSAGE_TOO_LONG = "Feedback can be at most 2000 characters.";
    public static final String TOO_MANY_PHOTOS = "Add at most 3 photos.";
    private static final Pattern ROUTE = Pattern.compile("[a-z][a-z0-9-]{0,39}");
    private static final Pattern BUILD = Pattern.compile("[A-Za-z0-9._+() -]{1,64}");
    private static final Pattern LANGUAGE = Pattern.compile("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8}){0,3}");

    private final CurrentUser current;
    private final FeedbackService feedback;
    private final FeedbackPhotoRepository photos;
    private final ImageStorage storage;

    /**
     * Stores a feedback: {@code message} (required, at most 2000 characters after trimming), the context the app
     * attached unless the sender removed it ({@code route} a route name such as {@code stock}, never a link; {@code build}
     * the footer's build label; {@code language} a BCP 47 tag; {@code device}) and up to three photos ({@code files}, each
     * under 12 MB, re-encoded without metadata like every photo).
     */
    @PostMapping(path = "/feedback", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    IdView sendFeedback(@RequestPart String message, @RequestPart(required = false) String route,
            @RequestPart(required = false) String build, @RequestPart(required = false) String language,
            @RequestPart(required = false) @Schema(allowableValues = {"PHONE", "TABLET", "DESKTOP"}) String device,
            @RequestPart(name = "files", required = false) List<MultipartFile> files, Authentication auth) throws IOException {
        UUID userId = current.user(auth).getId();
        // A browser's multipart encoding sends every line break as CRLF; the app counts the textarea's LF.
        String text = Text.clean(message.replace("\r\n", "\n"), Text.Kind.MULTI_LINE);
        if (text.isEmpty()) throw badRequest(MESSAGE_REQUIRED);
        if (text.length() > Feedback.MAX_MESSAGE) throw badRequest(MESSAGE_TOO_LONG);
        Context context = new Context(checked(route, ROUTE), checked(build, BUILD), checked(language, LANGUAGE), device(device));
        List<MultipartFile> chosen = files == null ? List.of() : files.stream().filter(file -> file != null && !file.isEmpty()).toList();
        if (chosen.size() > FeedbackPhoto.MAX_PHOTOS) throw badRequest(TOO_MANY_PHOTOS);
        List<ImageEncoder.Encoded> encoded = chosen.isEmpty() ? List.of() : ImageEncoder.encodeAll(chosen);
        UUID id = ImageController.storeObjects(storage, encoded, keys -> feedback.send(userId, text, context,
                IntStream.range(0, keys.size())
                        .mapToObj(i -> new StoredPhoto(keys.get(i), encoded.get(i).width(), encoded.get(i).height())).toList()));
        return new IdView(id);
    }

    /** Null for an absent or blank part, else the value when it has the expected shape (400 otherwise). */
    private static String checked(String value, Pattern shape) {
        if (value == null || value.isBlank()) return null;
        if (!shape.matcher(value).matches()) throw badRequest(ApiErrorHandler.INVALID_INPUT);
        return value;
    }

    private static Feedback.Device device(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Feedback.Device.valueOf(value); }
        catch (IllegalArgumentException unknown) { throw badRequest(ApiErrorHandler.INVALID_INPUT); }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    /**
     * Site admins: newest first, in pages of {@link ImageDeletionController#PAGE_SIZE}; {@code status} narrows the page
     * to open or done feedback, {@code open} counts all open ones.
     */
    @GetMapping("/admin/feedback")
    FeedbackPageView feedbackList(@RequestParam(defaultValue = "0") int page, @RequestParam(required = false) Feedback.Status status,
            Authentication auth) {
        current.siteAdmin(auth);
        return feedback.page(status, PageRequest.of(ImageDeletionController.page(page), ImageDeletionController.PAGE_SIZE,
                Sort.by("createdAt", "id").descending()));
    }

    /** Marks a feedback done (deleted {@code glazelab.feedback.retention} later) or open again. */
    @PutMapping("/admin/feedback/{id}/status") @ResponseStatus(HttpStatus.NO_CONTENT)
    void markFeedback(@PathVariable UUID id, @Valid @RequestBody FeedbackStatusInput body, Authentication auth) {
        current.siteAdmin(auth);
        feedback.mark(id, body.status());
    }

    /** Deletes a feedback and its photos at once. */
    @DeleteMapping("/admin/feedback/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteFeedback(@PathVariable UUID id, Authentication auth) {
        current.siteAdmin(auth);
        feedback.delete(id);
    }

    /** A feedback photo (at most 1600 px), site admins only; {@code no-store}, so no browser or proxy keeps a copy. */
    @GetMapping("/admin/feedback/photos/{id}")
    ResponseEntity<byte[]> feedbackPhoto(@PathVariable UUID id, Authentication auth) throws IOException {
        return photo(id, auth, false);
    }

    /** The card copy (at most 480 px) of a feedback photo, for the list's thumbnails. */
    @GetMapping("/admin/feedback/photos/{id}/card")
    ResponseEntity<byte[]> feedbackPhotoCard(@PathVariable UUID id, Authentication auth) throws IOException {
        return photo(id, auth, true);
    }

    private ResponseEntity<byte[]> photo(UUID id, Authentication auth, boolean card) throws IOException {
        current.siteAdmin(auth);
        String key = photos.findById(id).map(FeedbackPhoto::getStorageKey).orElseThrow(FeedbackController::photoNotFound);
        return Pictures.answer(storage, CacheControl.noStore().cachePrivate(), false, FeedbackController::photoNotFound,
                card ? ImageStorage.cardKey(key) : key);
    }

    private static ResponseStatusException photoNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, ImageController.IMAGE_NOT_FOUND);
    }
}
