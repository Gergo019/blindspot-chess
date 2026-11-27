package io.github.gergo019.blindspot.training;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings under {@code blindspot.training.drill-preparation} (#36).
 *
 * @param enabled whether the job is scheduled (integration tests switch it off and run it by hand)
 * @param batchSize at most this many findings per run, newest game first; each one is a {@code POST /judge}
 *        of up to the analysis service's time budget
 * @param interval pause between the end of one run and the start of the next
 * @param retryAfter how long a finding waits after a failed attempt that is worth repeating (time budget
 *        exceeded, read timeout, a response that cannot be stored)
 */
@ConfigurationProperties("blindspot.training.drill-preparation")
public record DrillPreparationProperties(boolean enabled, int batchSize, Duration interval, Duration retryAfter) {

    public DrillPreparationProperties {
        if (batchSize < 1) {
            throw new IllegalArgumentException("blindspot.training.drill-preparation.batch-size must be at least 1");
        }
        if (interval == null || interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("blindspot.training.drill-preparation.interval must be positive");
        }
        if (retryAfter == null || retryAfter.isNegative() || retryAfter.isZero()) {
            throw new IllegalArgumentException("blindspot.training.drill-preparation.retry-after must be positive");
        }
    }
}
