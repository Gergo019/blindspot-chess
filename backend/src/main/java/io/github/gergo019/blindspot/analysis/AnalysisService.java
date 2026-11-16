package io.github.gergo019.blindspot.analysis;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

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
 * between two short ones: claim the game (PENDING -> RUNNING, atomically),
 * call the service, then store the evaluations and findings and mark the game
 * DONE. A failed call is handled by its kind (ADR 0005), and a game is never
 * left RUNNING:
 * <ul>
 * <li>rejected (422) or an unreadable response: FAILED at once;</li>
 * <li>service unavailable: back to PENDING, the attempt given back;</li>
 * <li>read timeout: back to PENDING while attempts remain, then FAILED.</li>
 * </ul>
 */
@Service
public class AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);

    /** What one call of {@link #analyzeGame} did. */
    public enum Outcome {
        /** Analysed and stored; the game is DONE. */
        DONE,
        /** The game is FAILED: rejected by the service, an unusable response, or timed out on its last attempt. */
        FAILED,
        /** Timed out with attempts left; the game is PENDING again and the attempt counted. */
        RETRY,
        /** The service was unavailable; the game is PENDING again and the attempt did not count. */
        SERVICE_UNAVAILABLE,
        /** The game was not PENDING (or does not exist), so this call did nothing. */
        NOT_CLAIMED
    }

    private final GameRepository games;
    private final MoveEvaluationRepository evaluations;
    private final MistakeFindingRepository findings;
    private final AnalysisClient client;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final Clock clock;

    public AnalysisService(GameRepository games, MoveEvaluationRepository evaluations,
            MistakeFindingRepository findings, AnalysisClient client, PlatformTransactionManager transactionManager,
            JsonMapper json, Clock clock) {
        this.games = games;
        this.evaluations = evaluations;
        this.findings = findings;
        this.client = client;
        this.tx = new TransactionTemplate(transactionManager);
        this.json = json;
        this.clock = clock;
    }

    /**
     * Claims the game if it is PENDING and analyses it.
     *
     * @return what happened; {@link Outcome#NOT_CLAIMED} if the game was not
     *         PENDING, for example because another worker claimed it first
     */
    public Outcome analyzeGame(long gameId) {
        AnalyzeRequest request = tx.execute(status -> {
            if (!games.claimForAnalysis(gameId, clock.instant())) {
                return null;
            }
            Game game = load(gameId);
            return new AnalyzeRequest(game.getPgn(), game.getUserColor().name());
        });
        if (request == null) {
            log.info("Game {} not analysed: it is no longer PENDING", gameId);
            return Outcome.NOT_CLAIMED;
        }

        AnalyzeResponse response;
        try {
            response = client.analyze(request);
        } catch (AnalysisFailedException e) {
            return switch (e.getKind()) {
                case REJECTED, INVALID_RESPONSE -> fail(gameId, e);
                case UNAVAILABLE -> release(gameId, e);
                case TIMEOUT -> timeOut(gameId, e);
            };
        } catch (RuntimeException e) {
            return fail(gameId, e);
        }

        try {
            tx.executeWithoutResult(status -> store(gameId, response));
            log.info("Analysed game {}: {} half-moves, {} findings ({} {}, depth {})", gameId,
                    response.moves().size(), response.findings().size(), response.engine().name(),
                    response.engine().version(), response.engine().depth());
            return Outcome.DONE;
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

    private Outcome fail(long gameId, RuntimeException cause) {
        String kind = cause instanceof AnalysisFailedException e ? e.getKind().name() : "ERROR";
        log.warn("Analysis of game {} failed ({}); marked FAILED: {}", gameId, kind, cause.getMessage(), cause);
        updateIfRunning(gameId, game -> {
            game.failAnalysis();
            return null;
        });
        return Outcome.FAILED;
    }

    private Outcome release(long gameId, AnalysisFailedException cause) {
        log.warn("Analysis service unavailable for game {} (UNAVAILABLE); back to PENDING, attempt not counted: {}",
                gameId, cause.getMessage());
        updateIfRunning(gameId, game -> {
            game.releaseAnalysis();
            return null;
        });
        return Outcome.SERVICE_UNAVAILABLE;
    }

    private Outcome timeOut(long gameId, AnalysisFailedException cause) {
        AnalysisStatus next = updateIfRunning(gameId, Game::timeOutAnalysis);
        if (next == AnalysisStatus.PENDING) {
            log.warn("Analysis of game {} timed out (TIMEOUT); back to PENDING, attempt counted: {}", gameId,
                    cause.getMessage());
            return Outcome.RETRY;
        }
        log.warn("Analysis of game {} timed out (TIMEOUT) on its last attempt; marked FAILED: {}", gameId,
                cause.getMessage());
        return Outcome.FAILED;
    }

    /**
     * Applies a lifecycle change if the game is still RUNNING. It may not be:
     * stuck-game recovery can have reset it meanwhile.
     */
    private AnalysisStatus updateIfRunning(long gameId, Function<Game, AnalysisStatus> change) {
        return tx.execute(status -> {
            Game game = load(gameId);
            if (game.getAnalysisStatus() != AnalysisStatus.RUNNING) {
                return game.getAnalysisStatus();
            }
            change.apply(game);
            games.save(game);
            return game.getAnalysisStatus();
        });
    }

    private Game load(long gameId) {
        return games.findById(gameId)
                .orElseThrow(() -> new IllegalArgumentException("No game with id " + gameId));
    }

    private static Score score(ScoreDto dto) {
        return new Score(dto.cp(), dto.mate());
    }
}
