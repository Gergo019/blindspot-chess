package io.github.gergo019.blindspot.analysis;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.gergo019.blindspot.analysis.AnalysisFailedException.Kind;
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
 * <li>rejected (422): FAILED at once;</li>
 * <li>service unavailable: back to PENDING, the attempt given back;</li>
 * <li>read timeout: back to PENDING while attempts remain, then FAILED;</li>
 * <li>a response that cannot be read or stored: the same, logged at ERROR,
 * and the run stops (most likely a contract mismatch, which would hit every
 * game). Nothing of it is stored: one transaction, rolled back.</li>
 * </ul>
 */
@Service
public class AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);

    /** What one call of {@link #analyzeGame} did. */
    public enum Outcome {
        /** Analysed and stored; the game is DONE. */
        DONE,
        /** The game is FAILED: rejected by the service, an unexpected error, or a timeout on its last attempt. */
        FAILED,
        /** Timed out with attempts left; PENDING again, the attempt counted. */
        RETRY,
        /**
         * The response could not be read or stored, with attempts left; PENDING again, the attempt counted.
         * The run stops.
         */
        INVALID_RESPONSE_RETRY,
        /** The response could not be read or stored on the game's last attempt; the game is FAILED. The run stops. */
        INVALID_RESPONSE_FAILED,
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
                case REJECTED -> fail(gameId, e);
                // BUDGET_EXCEEDED belongs to POST /judge; /analyze has no budget. Treated like an outage.
                case UNAVAILABLE, BUDGET_EXCEEDED -> release(gameId, e);
                case TIMEOUT -> retryOrFail(gameId, e);
                case INVALID_RESPONSE -> invalidResponse(gameId, e);
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
        } catch (AnalysisFailedException e) {
            // Thrown by store(): the response cannot be stored. The transaction rolled back.
            return invalidResponse(gameId, e);
        } catch (RuntimeException e) {
            return fail(gameId, e);
        }
    }

    /**
     * Stores the evaluations and findings and marks the game DONE, all in the
     * caller's transaction. The response is first turned into entities in
     * memory, so content the backend does not accept (an unknown
     * classification or motif, a score or finding the domain rules reject,
     * missing fields) fails before anything is written. Content the database
     * rejects (a duplicate ply) fails on the flush, and the transaction rolls
     * back. Both become an {@link AnalysisFailedException} of kind
     * {@link Kind#INVALID_RESPONSE}.
     */
    private void store(long gameId, AnalyzeResponse response) {
        Game game = load(gameId);
        List<MoveEvaluation> moves = new ArrayList<>();
        List<MistakeFinding> found = new ArrayList<>();
        try {
            EngineRun engine = new EngineRun(response.engine().name() + " " + response.engine().version(),
                    response.engine().depth(), response.engine().threads(), response.engine().hashMb());
            Map<Integer, MoveEvaluation> byPly = new HashMap<>();
            for (MoveDto move : response.moves()) {
                MoveEvaluation evaluation = new MoveEvaluation(game, move.ply(), move.byUser(), move.fenBefore(),
                        move.moveUci(), move.moveSan(), move.bestMoveUci(), score(move.scoreBefore()),
                        score(move.scoreAfter()), engine, MoveClassification.valueOf(move.classification()));
                moves.add(evaluation);
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
                found.add(new MistakeFinding(evaluation, Motif.valueOf(finding.motif()), finding.evidenceLineUci(),
                        finding.detectorVersion(), json.writeValueAsString(finding.facts())));
            }
        } catch (RuntimeException e) {
            throw unstorable(gameId, e);
        }

        try {
            evaluations.saveAll(moves);
            findings.saveAll(found);
            evaluations.flush();
        } catch (DataIntegrityViolationException e) {
            throw unstorable(gameId, e);
        }
        game.completeAnalysis();
        games.save(game);
    }

    private static AnalysisFailedException unstorable(long gameId, RuntimeException cause) {
        return new AnalysisFailedException(Kind.INVALID_RESPONSE,
                "Analysis response for game " + gameId + " cannot be stored: " + cause.getMessage(), 0, cause);
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

    /** A timeout: retried while attempts remain, then FAILED. */
    private Outcome retryOrFail(long gameId, AnalysisFailedException cause) {
        boolean retry = updateIfRunning(gameId, Game::retryOrFailAnalysis) == AnalysisStatus.PENDING;
        log.warn("Analysis of game {} timed out (TIMEOUT); {}: {}", gameId,
                retry ? "back to PENDING, attempt counted" : "marked FAILED after its last attempt",
                cause.getMessage());
        return retry ? Outcome.RETRY : Outcome.FAILED;
    }

    /**
     * A response that cannot be read or stored: retried like a timeout, but
     * logged at ERROR, and the job stops the run. It most likely means the
     * backend and the service disagree on the contract, which needs a person
     * and would hit every game; failing at once would fail the whole run.
     */
    private Outcome invalidResponse(long gameId, AnalysisFailedException cause) {
        boolean retry = updateIfRunning(gameId, Game::retryOrFailAnalysis) == AnalysisStatus.PENDING;
        log.error("Analysis of game {}: the response cannot be read or stored (INVALID_RESPONSE), likely a contract "
                + "mismatch with the analysis service; {}: {}", gameId,
                retry ? "back to PENDING, attempt counted" : "marked FAILED after its last attempt",
                cause.getMessage(), cause);
        return retry ? Outcome.INVALID_RESPONSE_RETRY : Outcome.INVALID_RESPONSE_FAILED;
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
