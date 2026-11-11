package eu.glazelab.api.exception;

import eu.glazelab.api.dto.GlazeDtos.GlazeInUseView;

/** Removing a referenced glaze must preserve its records and name the combinations that use it. */
public class GlazeInUseException extends RuntimeException {
    private final transient GlazeInUseView view;

    public GlazeInUseException(GlazeInUseView view) {
        super(view.error());
        this.view = view;
    }

    public GlazeInUseView getView() { return view; }
}
