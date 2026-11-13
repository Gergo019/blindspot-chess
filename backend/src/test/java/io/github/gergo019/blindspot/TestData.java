package io.github.gergo019.blindspot;

import java.time.Instant;

import io.github.gergo019.blindspot.account.ChessAccount;
import io.github.gergo019.blindspot.account.ChessPlatform;
import io.github.gergo019.blindspot.account.User;
import io.github.gergo019.blindspot.analysis.MistakeFinding;
import io.github.gergo019.blindspot.analysis.Motif;
import io.github.gergo019.blindspot.analysis.MoveClassification;
import io.github.gergo019.blindspot.analysis.MoveEvaluation;
import io.github.gergo019.blindspot.analysis.MoveEvaluation.EngineRun;
import io.github.gergo019.blindspot.analysis.MoveEvaluation.Score;
import io.github.gergo019.blindspot.game.Game;
import io.github.gergo019.blindspot.game.GameEnums.GameResult;
import io.github.gergo019.blindspot.game.GameEnums.PieceColor;
import io.github.gergo019.blindspot.game.GameEnums.Speed;

/**
 * Builders for test objects, so tests only spell out what they care about.
 */
public final class TestData {

    /** After 1. e4 e5 2. Nf3 Nc6 3. Bc4 Nd4: White to move. */
    public static final String FORK_FEN = "r1bqkbnr/pppp1ppp/8/4p3/2BnP3/5N2/PPPP1PPP/RNBQK2R w KQkq - 4 4";
    public static final EngineRun ENGINE = new EngineRun("Stockfish 17.1", 20);

    private TestData() {
    }

    public static User user(String username) {
        return new User(username);
    }

    public static ChessAccount lichessAccount(User user) {
        return new ChessAccount(user, ChessPlatform.LICHESS, user.getUsername());
    }

    public static Game game(ChessAccount account, String externalId) {
        return new Game(account, externalId, Instant.parse("2026-11-01T18:00:00Z"), Speed.RAPID, true,
                PieceColor.WHITE, GameResult.LOSS, "1. e4 e5 2. Nf3 Nc6 3. Bc4 Nd4 4. Nxe5 Qg5 0-1");
    }

    /** The user's blunder 4. Nxe5?, after which ...Qg5 attacks both the knight and g2. */
    public static MoveEvaluation userBlunder(Game game, int ply) {
        return new MoveEvaluation(game, ply, true, FORK_FEN, "f3e5", "Nxe5", "f3d4",
                Score.cp(40), Score.cp(-210), ENGINE, MoveClassification.BLUNDER);
    }

    public static MoveEvaluation opponentBestMove(Game game, int ply) {
        return new MoveEvaluation(game, ply, false, FORK_FEN, "d8g5", "Qg5", "d8g5",
                Score.cp(-210), Score.cp(-210), ENGINE, MoveClassification.BEST);
    }

    /** A finding for persistence tests; the motif label is test data, not real detector output. */
    public static MistakeFinding hangingPiece(MoveEvaluation blunder) {
        return new MistakeFinding(blunder, Motif.HANGING_PIECE, "d8g5 e5f7 g5g2", "detector-0.1");
    }
}
