package io.github.gergo019.blindspot.analysis;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.gergo019.blindspot.analysis.AnalysisService.Outcome;
import io.github.gergo019.blindspot.game.Game;
import io.github.gergo019.blindspot.game.GameEnums.AnalysisStatus;
import io.github.gergo019.blindspot.game.GameRepository;

/**
 * Analyses PENDING games in the background: newest game first (the pattern
 * report reads the last 20 games), a bounded batch per run, at most
 * {@code workers} at a time (default one).
 *
 * <p>Each run first recovers stuck games: a game RUNNING for longer than
 * {@code stuckAfter} goes back to PENDING once and is marked FAILED if it
 * gets stuck again. FAILED games are never picked up again by the job.
 *
 * <p>A response that cannot be read or stored also stops the run (without a
 * back-off). When the analysis service is unavailable, the run stops at once and the
 * job backs off: {@link #nextDelay()} doubles, from twice the interval up to
 * {@link #MAX_BACKOFF}, and returns to the interval as soon as the service
 * answers again (ADR 0005). {@link AnalysisJobScheduler} runs this at that
 * pace, and recovers stuck games at startup.
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

    /** The longest pause between runs while the analysis service is unavailable. */
    public static final Duration MAX_BACKOFF = Duration.ofMinutes(10);

    /** The current back-off; zero while the service is answering. */
    private Duration backoff = Duration.ZERO;

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

    /**
     * What one run did.
     *
     * @param analysed games claimed and sent to the analysis service
     * @param failed games marked FAILED, including one whose response was invalid on its last attempt
     * @param retried games that timed out or got an invalid response and went back to PENDING (attempt counted)
     * @param serviceUnavailable whether the run stopped because the service was unavailable
     * @param invalidResponse whether the run stopped because a response could not be read or stored
     */
    public record RunResult(int recovered, int failedAsStuck, int analysed, int done, int failed, int retried,
            boolean serviceUnavailable, boolean invalidResponse) {
    }

    /** What stuck-game recovery did. */
    public record Recovery(int backToPending, int failed) {
    }

    /**
     * One run: recover stuck games, then analyse up to a batch of PENDING
     * games, newest first. Stops at the first game the service was unavailable
     * for, or whose response could not be read or stored, and adjusts the
     * back-off (only unavailability backs off).
     */
    public RunResult runOnce() {
        Recovery recovery = recoverStuckGames();
        List<Long> batch = tx.execute(status -> games
                .findByAnalysisStatusOrderByPlayedAtDesc(AnalysisStatus.PENDING,
                        PageRequest.of(0, properties.batchSize()))
                .stream().map(Game::getId).toList());

        List<Outcome> outcomes = analyse(batch);
        int analysed = (int) outcomes.stream().filter(o -> o != Outcome.NOT_CLAIMED).count();
        int done = count(outcomes, Outcome.DONE);
        int failed = count(outcomes, Outcome.FAILED) + count(outcomes, Outcome.INVALID_RESPONSE_FAILED);
        int retried = count(outcomes, Outcome.RETRY) + count(outcomes, Outcome.INVALID_RESPONSE_RETRY);
        boolean unavailable = outcomes.contains(Outcome.SERVICE_UNAVAILABLE);
        boolean invalid = outcomes.contains(Outcome.INVALID_RESPONSE_RETRY)
                || outcomes.contains(Outcome.INVALID_RESPONSE_FAILED);
        // An invalid response is still an answer: the service is up.
        adjustBackoff(unavailable, done + failed > 0 || invalid);

        if (!batch.isEmpty() || recovery.backToPending() + recovery.failed() > 0) {
            String stop = unavailable ? "; analysis service unavailable, next run in " + nextDelay()
                    : invalid ? "; stopped at a response that cannot be read or stored" : "";
            log.info("Analysis run: {} recovered, {} failed as stuck, {} analysed, {} done, {} failed, {} retried{}",
                    recovery.backToPending(), recovery.failed(), analysed, done, failed, retried, stop);
        }
        return new RunResult(recovery.backToPending(), recovery.failed(), analysed, done, failed, retried,
                unavailable, invalid);
    }

    /**
     * The pause before the next run: the configured interval, or the back-off
     * while the analysis service is unavailable.
     */
    public synchronized Duration nextDelay() {
        return backoff.isZero() ? properties.interval() : backoff;
    }

    /**
     * Unavailable: double the back-off (the first one is twice the interval),
     * capped at {@link #MAX_BACKOFF} but never below the interval. Answered
     * (a game ended DONE or FAILED, so the service responded): reset. A run
     * with only timeouts, or with nothing to do, leaves it as it is.
     */
    private synchronized void adjustBackoff(boolean unavailable, boolean answered) {
        if (unavailable) {
            Duration doubled = (backoff.isZero() ? properties.interval() : backoff).multipliedBy(2);
            backoff = max(properties.interval(), min(doubled, MAX_BACKOFF));
        } else if (answered) {
            backoff = Duration.ZERO;
        }
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

    /**
     * Analyses the games in order, or with several workers at once. Once one
     * game stops the run (service unavailable, or a response that cannot be
     * read or stored), the remaining games are not started (games already
     * running on other workers finish).
     */
    private List<Outcome> analyse(List<Long> gameIds) {
        AtomicBoolean stop = new AtomicBoolean();
        if (properties.workers() == 1) {
            List<Outcome> outcomes = new ArrayList<>();
            for (long id : gameIds) {
                Outcome outcome = analyseOne(id);
                outcomes.add(outcome);
                if (stopsTheRun(outcome)) {
                    break;
                }
            }
            return outcomes;
        }
        try (ExecutorService workers = Executors.newFixedThreadPool(properties.workers())) {
            List<Future<Outcome>> results = gameIds.stream()
                    .map(id -> workers.submit(() -> {
                        if (stop.get()) {
                            return null;
                        }
                        Outcome outcome = analyseOne(id);
                        if (stopsTheRun(outcome)) {
                            stop.set(true);
                        }
                        return outcome;
                    })).toList();
            List<Outcome> outcomes = new ArrayList<>();
            for (var result : results) {
                Outcome outcome = result.get();
                if (outcome != null) {
                    outcomes.add(outcome);
                }
            }
            return outcomes;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while analysing", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Analysis worker failed", e.getCause());
        }
    }

    private Outcome analyseOne(long gameId) {
        try {
            return analysisService.analyzeGame(gameId);
        } catch (RuntimeException e) {
            // Not a failed analysis (those are outcomes) but an error around it, e.g. the database.
            log.warn("Game {} skipped: {}", gameId, e.getMessage(), e);
            return Outcome.NOT_CLAIMED;
        }
    }

    private static boolean stopsTheRun(Outcome outcome) {
        return outcome == Outcome.SERVICE_UNAVAILABLE || outcome == Outcome.INVALID_RESPONSE_RETRY
                || outcome == Outcome.INVALID_RESPONSE_FAILED;
    }

    private static int count(List<Outcome> outcomes, Outcome outcome) {
        return (int) outcomes.stream().filter(o -> o == outcome).count();
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    private static Duration max(Duration a, Duration b) {
        return a.compareTo(b) >= 0 ? a : b;
    }
}
