package io.github.gergo019.blindspot.analysis;

/**
 * Tactical patterns the detector can recognise reliably. v1 deliberately
 * starts with two; each new motif needs a detector with tests first.
 */
public enum Motif {
    /**
     * The move let the opponent fork two valuable pieces (or king + piece) with a knight. No detector yet
     * (#26): set {@code hasDetector} to true in the PR that adds it to the analysis service.
     */
    ALLOWED_KNIGHT_FORK(false),
    /** The move left a piece that can be captured for free (detector {@code hanging-*}, ADR 0004). */
    HANGING_PIECE(true);

    private final boolean hasDetector;

    Motif(boolean hasDetector) {
        this.hasDetector = hasDetector;
    }

    /**
     * Whether the analysis service has a detector for this motif. A motif without one has never been looked
     * for, so "0 games" would wrongly read as "you never do this"; the pattern report leaves it out (#24).
     */
    public boolean hasDetector() {
        return hasDetector;
    }
}
