package io.github.gergo019.blindspot.training;

/**
 * A second answer to a mastery-check item. A check item is answered once (ADR 0008); the database enforces
 * the same rule with a partial unique index on {@code drill_attempts}.
 */
public class CheckAlreadyAnsweredException extends IllegalStateException {

    private final int position;

    public CheckAlreadyAnsweredException(int position) {
        super("The check item at position " + position + " has already been answered");
        this.position = position;
    }

    public int getPosition() {
        return position;
    }
}
