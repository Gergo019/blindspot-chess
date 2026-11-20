package io.github.gergo019.blindspot.analysis.api;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.github.gergo019.blindspot.analysis.Motif;
import io.github.gergo019.blindspot.game.GameEnums.GameResult;
import io.github.gergo019.blindspot.game.GameEnums.PieceColor;

/**
 * Response bodies of the findings API (#51). Records only; entities never leave the service layer.
 *
 * <p>Moves are given as UCI with the FEN they are played from; SAN is included only where the analysis
 * service stored it (the played move). Notation for display is derived in the frontend (chess.js,
 * #24): the analysis service owns the facts, notation is presentation (devlog, #51).
 */
public final class FindingResponses {

    private FindingResponses() {
    }

    /** One row of the findings list. */
    public record FindingSummary(long id, Motif motif, int ply, int moveNumber, PieceColor side, Move played,
            Facts facts, String detectorVersion, GameSummary game) {
    }

    /**
     * Everything the board needs for one finding. {@code fenBefore} is the position before the user's
     * move; {@code played} and {@code best} are played from it. {@code evidenceLineUci} continues from
     * the position after {@code played}. {@code explanation} is null unless a TEMPLATE explanation passed
     * verification (product rule 3).
     */
    public record FindingDetail(long id, Motif motif, int ply, int moveNumber, PieceColor side, String fenBefore,
            Move played, Move best, List<String> evidenceLineUci, Facts facts, String classification,
            String detectorVersion, Engine engine, GameSummary game, Explanation explanation) {
    }

    /** A move as UCI, with SAN where it is stored (null otherwise). */
    public record Move(String uci, String san) {
    }

    /**
     * The checked facts of a finding as the detector reported them (HANGING_PIECE: the lost piece and
     * its square, the capturing piece, the material lost, and the moved piece with its squares). Fields
     * a detector does not report are null.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Facts(String piece, String square, String capturedBy, Integer materialLost, String movedPiece,
            String movedFrom, String movedTo) {
    }

    /** The game a finding comes from, from the player's side. */
    public record GameSummary(long id, String lichessId, String lichessUrl, Instant playedAt, PieceColor userColor,
            GameResult result, String timeControl, String opponentName, Integer opponentRating, Integer userRating,
            String openingEco, String openingName) {
    }

    /** How the evaluation was produced (product rule 4); threads and hash size are null before V4. */
    public record Engine(String version, int depth, Integer threads, Integer hashMb) {
    }

    /** A verified, player-facing explanation. */
    public record Explanation(String content) {
    }

    /**
     * The blind-spot report: per motif, distinct games with a finding among the last eligible games. Only
     * motifs with a detector are listed; a motif nothing looked for is omitted rather than shown as zero.
     */
    public record PatternReport(int window, int threshold, int sampleSize, List<MotifPattern> motifs) {
    }

    /**
     * One motif in the report. {@code sampleSize} is always given, also below the threshold, so the
     * client can say "in 2 of your last 14 rapid games" without calling it a pattern.
     */
    public record MotifPattern(Motif motif, long gamesWithFinding, int sampleSize, boolean pattern) {
    }

    /** The move list of one game with the engine's classification of every half-move. */
    public record GameMoves(long gameId, String analysisStatus, PieceColor userColor, List<GameMove> moves) {
    }

    public record GameMove(int ply, int moveNumber, PieceColor side, boolean byUser, Move played, String bestMoveUci,
            String classification) {
    }
}
