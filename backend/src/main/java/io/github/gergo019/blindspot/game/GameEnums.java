package io.github.gergo019.blindspot.game;

/**
 * Small value types of the game context, kept together because each is tiny.
 */
public final class GameEnums {

    private GameEnums() {
    }

    public enum Speed {
        BULLET,
        BLITZ,
        RAPID,
        CLASSICAL,
        CORRESPONDENCE
    }

    public enum PieceColor {
        WHITE,
        BLACK
    }

    public enum GameResult {
        WIN,
        LOSS,
        DRAW
    }

    public enum AnalysisStatus {
        PENDING,
        RUNNING,
        DONE,
        FAILED
    }
}
