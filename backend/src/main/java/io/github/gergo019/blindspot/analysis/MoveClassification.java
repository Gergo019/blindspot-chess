package io.github.gergo019.blindspot.analysis;

/**
 * How good a move was compared with the engine's best move, based on the
 * evaluation the player lost by playing it.
 */
public enum MoveClassification {
    BEST,
    GOOD,
    INACCURACY,
    MISTAKE,
    BLUNDER;

    /** Mistakes and blunders are worth explaining and turning into drills. */
    public boolean isTrainable() {
        return this == MISTAKE || this == BLUNDER;
    }
}
