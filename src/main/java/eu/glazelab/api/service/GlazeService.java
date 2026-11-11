package eu.glazelab.api.service;

import eu.glazelab.api.model.Text;
import eu.glazelab.api.dto.GlazeDtos.BarcodesView;
import eu.glazelab.api.dto.GlazeDtos.GlazeCorrection;
import eu.glazelab.api.dto.GlazeDtos.GlazeCreatedView;
import eu.glazelab.api.dto.GlazeDtos.GlazeView;
import eu.glazelab.api.dto.GlazeDtos.NewGlaze;
import eu.glazelab.api.dto.GlazeDtos.GlazeInUseView;
import eu.glazelab.api.dto.GlazeDtos.UsingCombinationView;
import eu.glazelab.api.dto.ImageDtos.ImageView;
import eu.glazelab.api.dto.StockDtos.StockView;
import eu.glazelab.api.exception.ApiErrorHandler;
import eu.glazelab.api.exception.GlazeInUseException;
import eu.glazelab.api.model.AppUser;
import eu.glazelab.api.model.Barcodes;
import eu.glazelab.api.model.Combination;
import eu.glazelab.api.model.Glaze;
import eu.glazelab.api.model.GlazeEdit;
import eu.glazelab.api.model.Manufacturer;
import eu.glazelab.api.model.Tags;
import eu.glazelab.api.repository.CombinationRepository;
import eu.glazelab.api.repository.CombinationStepRepository;
import eu.glazelab.api.repository.GlazeEditRepository;
import eu.glazelab.api.repository.FavoriteRepository;
import eu.glazelab.api.repository.GlazeRepository;
import eu.glazelab.api.repository.ImageRecordRepository;
import eu.glazelab.api.repository.ManufacturerRepository;
import eu.glazelab.api.repository.StockRepository;
import eu.glazelab.api.security.CurrentUser;
import eu.glazelab.api.security.CurrentUser.Viewer;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Glaze creation, corrections, barcode edits and removal, with object cleanup strictly after the removal transaction commits. */
@Service @RequiredArgsConstructor
public class GlazeService {
    public static final String MANUFACTURER_REQUIRED = "Choose a manufacturer or add a new one";
    public static final String UNKNOWN_MANUFACTURER = "Unknown manufacturer";
    public static final String SHORT_ID_TAKEN = "This manufacturer ID is already taken.";
    public static final String NAME_TAKEN = "A manufacturer with this name already exists.";
    public static final String GLAZE_EXISTS = "This manufacturer and glaze code already exist in the library.";
    public static final String UNKNOWN_GLAZE = "Unknown glaze";
    public static final String BARCODE_TAKEN = "This barcode already belongs to another glaze.";
    public static final String GLAZE_IN_USE = "This glaze is used in combinations. Remove it from them first.";
    public static final String CODE_LOCKED = "Combinations use this glaze, so only a site admin can change its code.";
    public static final String NOTES_TOO_LONG = "Notes can have at most 1000 characters";

    private final GlazeRepository glazes;
    private final ManufacturerRepository manufacturers;
    private final StockRepository stocks;
    private final FavoriteRepository favorites;
    private final CombinationRepository combinations;
    private final CombinationStepRepository steps;
    private final GlazeEditRepository edits;
    private final ImageRecordRepository images;
    private final ImageRemovalService imageRemoval;
    private final CurrentUser current;
    private final StockService stockService;
    private final ChangeRecorder changes;
    private final TransactionTemplate transactions;

    /**
     * Creates the glaze (and its new manufacturer), and the stock entry when the request has one, in one
     * transaction and answers with their views, mapped before the commit. The stock owner is authorized and
     * locked first, as {@code POST /api/stock} does, so a refusal creates nothing; a later failure rolls
     * everything back.
     */
    public GlazeCreatedView add(NewGlaze body, AppUser actor) {
        if ((body.manufacturerId() == null) == (body.newManufacturer() == null))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, MANUFACTURER_REQUIRED);
        Set<String> tags = Tags.of(body.tags());
        String barcode = body.barcode() == null || body.barcode().isBlank() ? null : Barcodes.valid(body.barcode());
        try {
            return transactions.execute(status -> {
                StockService.Owner owner = body.stock() == null ? null
                        : stockService.lockOwner(actor, body.stock().clubId());
                String conflict = conflict(body, barcode);
                if (conflict != null) throw new ResponseStatusException(HttpStatus.CONFLICT, conflict);
                Manufacturer manufacturer = body.newManufacturer() == null
                        ? manufacturers.lockById(body.manufacturerId()).orElseThrow(() ->
                                new ResponseStatusException(HttpStatus.BAD_REQUEST, UNKNOWN_MANUFACTURER))
                        : manufacturers.save(new Manufacturer(body.newManufacturer().name(), body.newManufacturer().shortId()));
                Glaze glaze = new Glaze(manufacturer, Text.clean(body.name(), Text.Kind.LINE), body.code(), Text.check(body.notes(), Text.Kind.MULTI_LINE), actor);
                glaze.setTags(tags);
                if (barcode != null) glaze.setBarcodes(Set.of(barcode));
                glazes.saveAndFlush(glaze);
                changes.glaze(glaze.getId());
                List<StockView> stock = owner == null ? List.of()
                        : List.of(StockView.of(stockService.save(owner, glaze, body.stock().quantityG(), body.stock().jarSizeG()),
                                actor.getId(), Set.of()));
                return new GlazeCreatedView(glaze.getId(), GlazeView.of(glaze, List.of(), null), stock);
            });
        } catch (DataIntegrityViolationException ex) {
            // A concurrent request took the name, short ID, code or barcode after the checks above.
            String conflict = conflict(body, barcode);
            throw new ResponseStatusException(HttpStatus.CONFLICT, conflict == null ? ApiErrorHandler.DUPLICATE : conflict);
        }
    }

    /**
     * Any signed-in user corrects a glaze's name and notes; its code only a site admin, or anyone while no glaze
     * step of any combination (drafts included: they may be published later) uses the glaze. Locks the actor,
     * then the glaze row; the usage check and the change happen under that lock, and combination writes lock
     * their step glazes before inserting steps, so a step added concurrently is either seen here or waits.
     * Every changed field writes one {@link GlazeEdit} row; an unchanged value writes none. A code another glaze
     * of the manufacturer has is 409 like on creation; a refused code change changes nothing.
     */
    public GlazeView correct(UUID id, GlazeCorrection body, UUID actorId, Viewer viewer) {
        Optional<String> notes = body.notes() == null ? null : body.notes().map(text -> Text.optional(text, Text.Kind.MULTI_LINE));
        if (notes != null && notes.map(String::length).orElse(0) > Glaze.MAX_NOTES)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, NOTES_TOO_LONG);
        try {
            return transactions.execute(status -> {
                AppUser actor = current.lockUser(actorId);
                Glaze glaze = glazes.lockById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, UNKNOWN_GLAZE));
                String name = body.name() == null ? glaze.getName() : body.name();
                String code = body.code() == null ? glaze.getCode() : body.code();
                String nextNotes = notes == null ? glaze.getNotes() : notes.orElse(null);
                Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
                List<GlazeEdit> changed = new ArrayList<>();
                if (!code.equals(glaze.getCode())) {
                    if (!actor.isSiteAdmin() && steps.existsByGlaze_Id(id))
                        throw new ResponseStatusException(HttpStatus.FORBIDDEN, CODE_LOCKED);
                    if (glazes.existsByManufacturer_IdAndCode(glaze.getManufacturer().getId(), code))
                        throw new ResponseStatusException(HttpStatus.CONFLICT, GLAZE_EXISTS);
                    changed.add(new GlazeEdit(glaze, actor, now, GlazeEdit.Field.CODE, glaze.getCode(), code));
                }
                if (!name.equals(glaze.getName()))
                    changed.add(new GlazeEdit(glaze, actor, now, GlazeEdit.Field.NAME, glaze.getName(), name));
                if (!Objects.equals(nextNotes, glaze.getNotes()))
                    changed.add(new GlazeEdit(glaze, actor, now, GlazeEdit.Field.NOTES, glaze.getNotes(), nextNotes));
                if (!changed.isEmpty()) {
                    glaze.correct(name, code, nextNotes);
                    edits.saveAll(changed);
                    glazes.flush();
                    // A new name also shows in the steps of combinations using the glaze: clients copy it there.
                    changes.glaze(id);
                }
                return GlazeView.of(glaze, ImageView.visibleViews(images.findByGlaze_IdOrderByCreatedAtDesc(id), viewer),
                        GlazeEditRepository.byGlaze(edits.latestOf(id)).get(id));
            });
        } catch (DataIntegrityViolationException ex) {
            // Another glaze of the manufacturer took the code after the check above.
            throw new ResponseStatusException(HttpStatus.CONFLICT, GLAZE_EXISTS);
        }
    }

    /**
     * Applies barcode removals, then additions, under the glaze row lock, so concurrent edits of one glaze
     * apply in turn and the limit holds. A barcode of another glaze is refused with 409.
     */
    public BarcodesView editBarcodes(UUID id, List<String> adding, List<String> removing) {
        try {
            return transactions.execute(status -> {
                Glaze glaze = glazes.lockById(id)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, UNKNOWN_GLAZE));
                Set<String> next = Barcodes.edited(glaze.getBarcodes(), adding, removing);
                for (String barcode : next) {
                    if (!glaze.getBarcodes().contains(barcode) && glazes.findIdByBarcode(barcode).isPresent())
                        throw new ResponseStatusException(HttpStatus.CONFLICT, BARCODE_TAKEN);
                }
                if (!next.equals(glaze.getBarcodes())) changes.glaze(id);
                glaze.setBarcodes(next);
                glazes.flush();
                return new BarcodesView(List.copyOf(new TreeSet<>(next)));
            });
        } catch (DataIntegrityViolationException ex) {
            // Another glaze took the barcode after the check above (the primary key is the barcode).
            throw new ResponseStatusException(HttpStatus.CONFLICT, BARCODE_TAKEN);
        }
    }

    public void remove(UUID id, UUID actor) {
        transactions.executeWithoutResult(status -> removeGlazeRecords(id, actor));
    }

    /** Deletes the glaze and related records in the caller's transaction; object cleanup follows commit. */
    private void removeGlazeRecords(UUID id, UUID actor) {
        current.lockUser(actor);
        Glaze glaze = glazes.lockById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, UNKNOWN_GLAZE));
        List<Combination> using = combinations.findUsingGlaze(id);
        // Other people's drafts block the removal too, but are not named.
        if (!using.isEmpty()) throw new GlazeInUseException(new GlazeInUseView(GLAZE_IN_USE,
                using.stream().filter(c -> c.visibleTo(actor))
                        .map(c -> new UsingCombinationView(c.getId(), c.getTitle())).toList()));
        imageRemoval.remove(images.lockByGlazeId(id), actor);
        changes.glaze(id);
        stocks.deleteByGlazeId(id);
        favorites.deleteByGlazeId(id);
        UUID manufacturerId = glaze.getManufacturer().getId();
        glazes.delete(glaze);
        glazes.flush();
        // Under the manufacturer lock: a concurrent removal of its other last glaze, or a creation under it, has committed.
        manufacturers.lockById(manufacturerId).filter(m -> !glazes.existsByManufacturer_Id(manufacturerId)).ifPresent(manufacturers::delete);
    }

    /** Which unique manufacturer, glaze or barcode value the request repeats, or null. */
    private String conflict(NewGlaze body, String barcode) {
        if (body.newManufacturer() != null) {
            if (manufacturers.existsByShortId(Manufacturer.shortId(body.newManufacturer().shortId()))) return SHORT_ID_TAKEN;
            if (manufacturers.existsByNameKey(Manufacturer.nameKey(body.newManufacturer().name()))) return NAME_TAKEN;
        } else if (glazes.existsByManufacturer_IdAndCode(body.manufacturerId(), Glaze.code(body.code()))) {
            return GLAZE_EXISTS;
        }
        return barcode != null && glazes.findIdByBarcode(barcode).isPresent() ? BARCODE_TAKEN : null;
    }
}
