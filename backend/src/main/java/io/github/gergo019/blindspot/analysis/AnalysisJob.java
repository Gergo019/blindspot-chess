package io.github.gergo019.blindspot.analysis;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.gergo019.blindspot.game.Game;
import io.github.gergo019.blindspot.game.GameEnums.AnalysisStatus;
import io.github.gergo019.blindspot.game.GameRepository;

/**
 * Analyses PENDING games in the background: oldest game first, a bounded
 * batch per run, at most {@code workers} at a time (default one).
 *
 * <p>Each run first recovers stuck games: a game RUNNING for longer than
 * {@code stuckAfter} goes back to PENDING once and is marked FAILED if it
 * gets stuck again. FAILED games are never picked up again by the job.
 * {@link AnalysisJobScheduler} runs this on a schedule and at startup.
 */
@Component
@EnableConfigurationProperties(AnalysisJobProperties.class)
public class AnalysisJob {

    private static final Logger log = LoggerFactory.getLogger(AnalysisJob.class);

    private final GameRepository games;
    private final AnalysisService analysisService;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final AnalysisJobProperties properties;

    public AnalysisJob(GameRepository games, AnalysisService analysisService,
            PlatformTransactionManager transactionManager, Clock clock, AnalysisJobProperties properties,
            @Value("${blindspot.analysis-service-read-timeout:180s}") Duration readTimeout) {
        if (properties.workers() < 1) {
            throw new IllegalArgumentException("blindspot.analysis.job.workers must be at least 1");
        }
        if (properties.batchSize() < 1) {
            throw new IllegalArgumentException("blindspot.analysis.job.batch-size must be at least 1");
        }
        if (properties.stuckAfter().compareTo(readTimeout) <= 0) {
            throw new IllegalArgumentException("blindspot.analysis.job.stuck-after (" + properties.stuckAfter()
                    + ") must be longer than the analysis-service read timeout (" + readTimeout
                    + "), or a running analysis could be reset");
        }
        this.games = games;
        this.analysisService = analysisService;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.properties = properties;
    }

    /** What one run did. */
    public record RunResult(int recovered, int failedAsStuck, int analysed, int done) {
    }

    /** What stuck-game recovery did. */
    public record Recovery(int backToPending, int failed) {
    }

    /** One run: recover stuck games, then analyse up to a batch of PENDING games. */
    public RunResult runOnce() {
        Recovery recovery = recoverStuckGames();
        List<Long> batch = tx.execute(status -> games
                .findByAnalysisStatusOrderByPlayedAtAsc(AnalysisStatus.PENDING,
                        PageRequest.of(0, properties.batchSize()))
                .stream().map(Game::getId).toList());

        int done = analyse(batch);
        if (!batch.isEmpty() || recovery.backToPending() + recovery.failed() > 0) {
            log.info("Analysis run: {} recovered, {} failed as stuck, {} analysed, {} done", recovery.backToPending(),
                    recovery.failed(), batch.size(), done);
        }
        return new RunResult(recovery.backToPending(), recovery.failed(), batch.size(), done);
    }

    /**
     * Puts games stuck in RUNNING back to PENDING, or marks them FAILED when
     * they have used their attempts.
     */
    public Recovery recoverStuckGames() {
        Instant now = clock.instant();
        Duration stuckAfter = properties.stuckAfter();
        return tx.execute(status -> {
            int pending = 0;
            int failed = 0;
            for (Game game : games.findByAnalysisStatusAndAnalysisStartedAtBefore(AnalysisStatus.RUNNING,
                    now.minus(stuckAfter))) {
                if (!game.isAnalysisStuck(now, stuckAfter)) {
                    continue;
                }
                AnalysisStatus next = game.recoverStuckAnalysis();
                games.save(game);
                if (next == AnalysisStatus.PENDING) {
                    pending++;
                    log.warn("Game {} was RUNNING since {} (attempt {}); back to PENDING", game.getId(),
                            game.getAnalysisStartedAt(), game.getAnalysisAttempts());
                } else {
                    failed++;
                    log.warn("Game {} was RUNNING since {} and got stuck on attempt {}; marked FAILED", game.getId(),
                            game.getAnalysisStartedAt(), game.getAnalysisAttempts());
                }
            }
            return new Recovery(pending, failed);
        });
    }

    /** @return how many games ended DONE */
    private int analyse(List<Long> gameIds) {
        if (properties.workers() == 1) {
            return (int) gameIds.stream().filter(this::analyseOne).count();
        }
        try (ExecutorService workers = Executors.newFixedThreadPool(properties.workers())) {
            List<Future<Boolean>> results = gameIds.stream()
                    .map(id -> workers.submit(() -> analyseOne(id))).toList();
            int done = 0;
            for (var result : results) {
                done += result.get() ? 1 : 0;
            }
            return done;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while analysing", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Analysis worker failed", e.getCause());
        }
    }

    private boolean analyseOne(long gameId) {
        try {
            return analysisService.analyzeGame(gameId) == AnalysisStatus.DONE;
        } catch (RuntimeException e) {
            // For example: the game is no longer PENDING because something else started it.
            log.warn("Game {} skipped: {}", gameId, e.getMessage());
            return false;
        }
    }
}
