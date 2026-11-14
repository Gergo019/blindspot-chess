package io.github.gergo019.blindspot.analysis;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings under {@code blindspot.analysis.job}.
 *
 * @param enabled whether the job is scheduled (integration tests switch it off and run it by hand)
 * @param workers games analysed at the same time; each one is a Stockfish
 *        process in the analysis service
 * @param batchSize at most this many PENDING games, oldest first, per run
 * @param interval pause between the end of one run and the start of the next
 * @param stuckAfter a game RUNNING for longer than this is treated as stuck;
 *        must be longer than the analysis-service read timeout, or a live
 *        analysis could be reset
 */
@ConfigurationProperties("blindspot.analysis.job")
public record AnalysisJobProperties(boolean enabled, int workers, int batchSize, Duration interval,
        Duration stuckAfter) {
}
