package io.github.gergo019.blindspot.analysis.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import io.github.gergo019.blindspot.analysis.GameMovesReadService;
import io.github.gergo019.blindspot.analysis.api.FindingResponses.GameMoves;

/**
 * The analysed move list of a game (#51). It lives in the analysis context because classifications
 * are analysis results; the game context stays free of a dependency on analysis.
 */
@RestController
public class GameMovesController {

    private final GameMovesReadService moves;

    public GameMovesController(GameMovesReadService moves) {
        this.moves = moves;
    }

    @GetMapping("/api/games/{id}/moves")
    public GameMoves moves(@PathVariable long id) {
        return moves.moves(id);
    }
}
