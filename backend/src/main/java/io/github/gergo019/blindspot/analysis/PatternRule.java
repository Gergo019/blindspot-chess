package io.github.gergo019.blindspot.analysis;

/**
 * When a motif counts as a recurring pattern (README, "The coaching loop", step 4): it occurred in at
 * least {@value #THRESHOLD} distinct games among the player's last {@value #WINDOW} eligible rapid games,
 * counted at most once per game. Below the threshold the examples are shown without calling them a
 * pattern, and the sample size is always shown. Both numbers are a starting heuristic, to be tuned.
 */
public final class PatternRule {

    /** How many of the newest eligible games the report looks at. */
    public static final int WINDOW = 20;

    /** Distinct games with the motif needed to call it a pattern. */
    public static final int THRESHOLD = 3;

    private PatternRule() {
    }

    public static boolean isPattern(long gamesWithMotif) {
        return gamesWithMotif >= THRESHOLD;
    }
}
