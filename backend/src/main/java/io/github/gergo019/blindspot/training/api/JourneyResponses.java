package io.github.gergo019.blindspot.training.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import io.github.gergo019.blindspot.analysis.Motif;
import io.github.gergo019.blindspot.analysis.api.FindingResponses.GameSummary;
import io.github.gergo019.blindspot.analysis.api.FindingResponses.Move;
import io.github.gergo019.blindspot.analysis.api.FindingResponses.MotifPattern;
import io.github.gergo019.blindspot.game.GameEnums.PieceColor;
import io.github.gergo019.blindspot.training.Drill;
import io.github.gergo019.blindspot.training.LessonItem;
import io.github.gergo019.blindspot.training.LessonService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request and response bodies of the journey API (#90, ADR 0008). Records only; entities never leave the
 * service layer. Moves are UCI, played from the item's FEN, as in the findings API (#51).
 */
public final class JourneyResponses {

    private JourneyResponses() {
    }

    /** The map: the unit with its lessons in the recommended order. */
    public record Journey(Unit unit, List<LessonSummary> lessons) {
    }

    /**
     * A unit and its motif's line from the blind-spot report ("in 5 of your last 20 rapid games"), as
     * {@code GET /api/patterns} gives it.
     */
    public record Unit(String key, String title, Motif motif, MotifPattern report) {
    }

    /**
     * A lesson on the map. {@code unit} and {@code number} make its URL ({@code /api/lessons/{unit}/{number}}).
     * {@code positionsNeeded} is what a lesson that cannot be assigned yet still needs, or what a retry of a
     * failed or void check still needs.
     */
    public record LessonSummary(String key, String unit, int number, String title, LessonService.State state,
            boolean recommended, int solvedFirstTry, int size, int positionsNeeded, boolean retryAvailable) {
    }

    /** A lesson with its explanation and its items in position order. Positions can have gaps. */
    public record Lesson(LessonSummary lesson, String explanation, Instant masteredAt, List<Item> items) {
    }

    /**
     * One puzzle. {@code solution} is null until the item is answered: before that, nothing about the game,
     * the engine's move or the accepted moves is sent.
     */
    public record Item(int position, LessonItem.Role role, boolean currentCheck, Drill.Task task, String prompt,
            String fen, PieceColor orientation, boolean answered, boolean solvedFirstTry, Solution solution) {
    }

    /** What an answered puzzle reveals. */
    public record Solution(GameSummary game, Move gameMove, List<String> evidenceLineUci, String bestMove,
            List<String> acceptedMoves) {
    }

    /** The body of an answer. */
    public record AnswerRequest(@NotBlank @Size(max = 10) String moveUci) {
    }

    /**
     * The result of an answer: whether it was correct and the item's first try, the move's verdict with its
     * refutation line, the engine's move, every accepted move, the game's move with its evidence line, and
     * the lesson afterwards.
     */
    public record Answer(int position, boolean correct, boolean firstTry, Verdict verdict, String bestMove,
            List<String> acceptedMoves, Move gameMove, List<String> evidenceLineUci, LessonSummary lesson) {
    }

    /** The stored verdict of the answered move: its loss in winning chances and the line that refutes it. */
    public record Verdict(String moveUci, String san, BigDecimal drop, String refutationUci) {
    }
}
