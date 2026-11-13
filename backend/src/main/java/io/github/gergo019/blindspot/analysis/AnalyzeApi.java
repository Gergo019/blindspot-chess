package io.github.gergo019.blindspot.analysis;

import java.util.List;

/**
 * Records mirroring the analysis service's {@code POST /analyze} JSON
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

    public record EngineInfo(String name, String version, int depth) {
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
}
