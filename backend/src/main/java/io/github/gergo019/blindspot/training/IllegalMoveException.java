package io.github.gergo019.blindspot.training;

/**
 * An answer that is not a legal move in the drill's position. Every legal move has a stored verdict, so a
 * move without one is illegal (or not UCI at all). It is refused: no attempt is recorded and the schedule
 * does not change.
 */
public class IllegalMoveException extends IllegalArgumentException {

    private final String move;

    public IllegalMoveException(String move, String fen) {
        super("Not a legal move in " + fen + ": " + move);
        this.move = move;
    }

    public String getMove() {
        return move;
    }
}
