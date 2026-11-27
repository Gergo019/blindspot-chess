package io.github.gergo019.blindspot.analysis;

import java.math.BigDecimal;
import java.util.List;

/**
 * Records mirroring the analysis service's {@code POST /analyze} and {@code POST /judge} JSON
 * (see analysis-service/README.md). Field names match the JSON keys.
 */
public final class AnalyzeApi {

    private AnalyzeApi() {
    }

    /** {@code userColor} is {@code "WHITE"} or {@code "BLACK"}. */
    public record AnalyzeRequest(String pgn, String userColor) {
    }

    public record AnalyzeResponse(EngineInfo engine, List<MoveDto> moves, List<FindingDto> findings) {

        public AnalyzeResponse {
            moves = moves == null ? List.of() : List.copyOf(moves);
            findings = findings == null ? List.of() : List.copyOf(findings);
        }
    }

    /** The engine and its settings; threads and hash size are required (a missing one becomes 0 and is rejected). */
    public record EngineInfo(String name, String version, int depth, int threads, int hashMb) {
    }

    /** One half-move; scores are from White's point of view. */
    public record MoveDto(int ply, boolean byUser, String fenBefore, String moveUci, String moveSan,
            String bestMoveUci, ScoreDto scoreBefore, ScoreDto scoreAfter, String classification) {
    }

    /**
     * Exactly one of {@code cp} / {@code mate} is set. {@code mate == 0}
     * after the final move means the side to move is checkmated.
     */
    public record ScoreDto(Integer cp, Integer mate) {
    }

    public record FindingDto(int ply, String motif, String evidenceLineUci, String detectorVersion,
            FindingFacts facts) {
    }

    /** The checked facts of a HANGING_PIECE finding (detector hanging-0.2). */
    public record FindingFacts(String piece, String square, String capturedBy, int materialLost,
            String movedPiece, String movedFrom, String movedTo) {
    }

    /** {@code POST /judge} (#78): a drill position; the side to move is judged. */
    public record JudgeRequest(String fen) {
    }

    /**
     * Every legal move of the position with its verdict, best first. {@code fen} is the position as the
     * service parsed it (normalised); {@code acceptBelow} is the drop threshold the verdicts were judged
     * against.
     */
    public record JudgeResponse(EngineInfo engine, String fen, String sideToMove, BigDecimal acceptBelow,
            List<VerdictDto> verdicts) {
        public JudgeResponse {
            verdicts = verdicts == null ? List.of() : List.copyOf(verdicts);
        }
    }

    /**
     * One legal move: the score after it (White's point of view), the drop in the mover's winning chances
     * against the best move, whether it is accepted, and the engine's line after it (space-separated UCI,
     * empty when the move ends the game).
     */
    public record VerdictDto(String uci, String san, ScoreDto score, BigDecimal drop, boolean accepted,
            String refutationUci) {
    }
}
