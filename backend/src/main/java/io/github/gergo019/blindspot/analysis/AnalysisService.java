package io.github.gergo019.blindspot.analysis;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.gergo019.blindspot.analysis.AnalyzeApi.AnalyzeRequest;
import io.github.gergo019.blindspot.analysis.AnalyzeApi.AnalyzeResponse;
import io.github.gergo019.blindspot.analysis.AnalyzeApi.FindingDto;
import io.github.gergo019.blindspot.analysis.AnalyzeApi.MoveDto;
import io.github.gergo019.blindspot.analysis.AnalyzeApi.ScoreDto;
import io.github.gergo019.blindspot.analysis.MoveEvaluation.EngineRun;
import io.github.gergo019.blindspot.analysis.MoveEvaluation.Score;
import io.github.gergo019.blindspot.game.Game;
import io.github.gergo019.blindspot.game.GameEnums.AnalysisStatus;
import io.github.gergo019.blindspot.game.GameRepository;

import tools.jackson.databind.json.JsonMapper;

/**
 * Analyses one stored game through the analysis service and stores the
 * result.
 *
 * <p>The HTTP call can take minutes, so it runs outside any transaction,
 * between two short ones: mark the game RUNNING, call the service, then store
 * the evaluations and findings and mark the game DONE. Any failure marks the
 * game FAILED, so a game is never left RUNNING.
 */
@Service
public class AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);

    private final GameRepository games;
    private final MoveEvaluationRepository evaluations;
    private final MistakeFindingRepository findings;
    private final AnalysisClient client;
    private final TransactionTemplate tx;
    private final JsonMapper json;

    public AnalysisService(GameRepository games, MoveEvaluationRepository evaluations,
            MistakeFindingRepository findings, AnalysisClient client, PlatformTransactionManager transactionManager,
            JsonMapper json) {
        this.games = games;
        this.evaluations = evaluations;
        this.findings = findings;
        this.client = client;
        this.tx = new TransactionTemplate(transactionManager);
        this.json = json;
    }

    /**
     * @return the game's analysis status afterwards: DONE or FAILED
     * @throws IllegalArgumentException if there is no game with this id
     * @throws IllegalStateException if the game is not PENDING or FAILED
     */
    public AnalysisStatus analyzeGame(long gameId) {
        AnalyzeRequest request = tx.execute(status -> {
            Game game = load(gameId);
            game.startAnalysis();
            games.save(game);
            return new AnalyzeRequest(game.getPgn(), game.getUserColor().name());
        });

        AnalyzeResponse response;
        try {
            response = client.analyze(request);
        } catch (RuntimeException e) {
            return fail(gameId, e);
        }

        try {
            tx.executeWithoutResult(status -> store(gameId, response));
            log.info("Analysed game {}: {} half-moves, {} findings ({} {}, depth {})", gameId,
                    response.moves().size(), response.findings().size(), response.engine().name(),
                    response.engine().version(), response.engine().depth());
            return AnalysisStatus.DONE;
        } catch (RuntimeException e) {
            return fail(gameId, e);
        }
    }

    private void store(long gameId, AnalyzeResponse response) {
        Game game = load(gameId);
        EngineRun engine = new EngineRun(response.engine().name() + " " + response.engine().version(),
                response.engine().depth());

        Map<Integer, MoveEvaluation> byPly = new HashMap<>();
        for (MoveDto move : response.moves()) {
            MoveEvaluation evaluation = evaluations.save(new MoveEvaluation(game, move.ply(), move.byUser(),
                    move.fenBefore(), move.moveUci(), move.moveSan(), move.bestMoveUci(),
                    score(move.scoreBefore()), score(move.scoreAfter()), engine,
                    MoveClassification.valueOf(move.classification())));
            byPly.put(move.ply(), evaluation);
        }

        for (FindingDto finding : response.findings()) {
            MoveEvaluation evaluation = byPly.get(finding.ply());
            if (evaluation == null || !evaluation.isTrainableMistake()) {
                // The analysis service only reports findings for the user's mistakes and
                // blunders; anything else would violate MistakeFinding's rule.
                log.warn("Game {}: skipping {} finding on ply {}, not a user mistake or blunder", gameId,
                        finding.motif(), finding.ply());
                continue;
            }
            findings.save(new MistakeFinding(evaluation, Motif.valueOf(finding.motif()), finding.evidenceLineUci(),
                    finding.detectorVersion(), json.writeValueAsString(finding.facts())));
        }

        game.completeAnalysis();
        games.save(game);
    }

    private AnalysisStatus fail(long gameId, RuntimeException cause) {
        log.warn("Analysis of game {} failed: {}", gameId, cause.getMessage(), cause);
        tx.executeWithoutResult(status -> {
            Game game = load(gameId);
            if (game.getAnalysisStatus() == AnalysisStatus.RUNNING) {
                game.failAnalysis();
                games.save(game);
            }
        });
        return AnalysisStatus.FAILED;
    }

    private Game load(long gameId) {
        return games.findById(gameId)
                .orElseThrow(() -> new IllegalArgumentException("No game with id " + gameId));
    }

    private static Score score(ScoreDto dto) {
        return new Score(dto.cp(), dto.mate());
    }
}
