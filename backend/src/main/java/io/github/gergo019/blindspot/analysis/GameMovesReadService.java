package io.github.gergo019.blindspot.analysis;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.gergo019.blindspot.account.CurrentPlayer;
import io.github.gergo019.blindspot.analysis.api.FindingResponses.GameMove;
import io.github.gergo019.blindspot.analysis.api.FindingResponses.GameMoves;
import io.github.gergo019.blindspot.analysis.api.FindingResponses.Move;
import io.github.gergo019.blindspot.common.api.NotFoundException;
import io.github.gergo019.blindspot.game.Game;
import io.github.gergo019.blindspot.game.GameRepository;

/**
 * The move list of one of the player's games with the engine's classification of every half-move, for
 * the side panel (#51). Empty until the game is analysed; {@code analysisStatus} says why.
 */
@Service
@Transactional(readOnly = true)
public class GameMovesReadService {

    private final CurrentPlayer player;
    private final GameRepository games;
    private final MoveEvaluationRepository evaluations;

    public GameMovesReadService(CurrentPlayer player, GameRepository games, MoveEvaluationRepository evaluations) {
        this.player = player;
        this.games = games;
        this.evaluations = evaluations;
    }

    public GameMoves moves(long gameId) {
        Game game = player.userId()
                .flatMap(userId -> games.findForUser(gameId, userId))
                .orElseThrow(() -> new NotFoundException("game", gameId));
        List<GameMove> moves = evaluations.findByGameIdOrderByPlyAsc(game.getId()).stream()
                .map(m -> new GameMove(m.getPly(), FindingReadService.moveNumber(m.getPly()),
                        FindingReadService.side(m.getPly()), m.isByUser(), new Move(m.getMoveUci(), m.getMoveSan()),
                        m.getBestMoveUci(), m.getClassification().name()))
                .toList();
        return new GameMoves(game.getId(), game.getAnalysisStatus().name(), game.getUserColor(), moves);
    }
}
