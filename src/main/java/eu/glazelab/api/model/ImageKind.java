package eu.glazelab.api.model;

/**
 * What an image shows; the stored values are checked by {@code ck_image_kind}. PACKAGING belongs to glazes only
 * ({@code ck_image_packaging_target}), IN_PROGRESS (a piece not yet finished) to combinations only
 * ({@code ck_image_in_progress_target}).
 */
public enum ImageKind {
    TEST_TILE, FINISHED, PACKAGING, IN_PROGRESS;

    /** Whether a glaze's ({@code glaze}) or a combination's own picture may show this. */
    public boolean allowedOn(boolean glaze) {
        return switch (this) {
            case PACKAGING -> glaze;
            case IN_PROGRESS -> !glaze;
            case TEST_TILE, FINISHED -> true;
        };
    }
}
