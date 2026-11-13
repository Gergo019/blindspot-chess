package io.github.gergo019.blindspot.analysis;

/**
 * Tactical patterns the detector can recognise reliably. v1 deliberately
 * starts with two; each new motif needs a detector with tests first.
 */
public enum Motif {
    /** The move let the opponent fork two valuable pieces (or king + piece) with a knight. */
    ALLOWED_KNIGHT_FORK,
    /** The move left a piece that can be captured for free. */
    HANGING_PIECE
}
