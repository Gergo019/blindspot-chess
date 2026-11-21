package io.github.gergo019.blindspot.training;

import java.time.Clock;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.gergo019.blindspot.training.DrillPreparationService.Outcome;

/**
 * Prepares "Find a safe move" drills from findings in the background (#36, ADR 0008): a bounded batch per
 * run, newest game first, one {@code POST /judge} at a time. Findings that failed in a way worth repeating
 * wait for their {@link DrillPreparation#getNextAttemptAt() retry time}; skipped ones are never picked up
 * again. The run stops as soon as the analysis service is unavailable. {@link DrillPreparationScheduler}
 * runs this at the configured interval.
 */
@Component
@EnableConfigurationProperties(DrillPreparationProperties.class)
public class DrillPreparationJob {

    private static final Logger log = LoggerFactory.getLogger(DrillPreparationJob.class);

    private final DrillPreparationRepository preparations;
    private final DrillPreparationService service;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final DrillPreparationProperties properties;

    public DrillPreparationJob(DrillPreparationRepository preparations, DrillPreparationService service,
            PlatformTransactionManager transactionManager, Clock clock, DrillPreparationProperties properties) {
        this.preparations = preparations;
        this.service = service;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.properties = properties;
    }

    /** What one run did: how often each outcome occurred, and whether it stopped at an unavailable service. */
    public record RunResult(Map<Outcome, Integer> outcomes, boolean serviceUnavailable) {
        public int count(Outcome outcome) {
            return outcomes.getOrDefault(outcome, 0);
        }
    }

    public RunResult runOnce() {
        List<Long> batch = tx.execute(status -> preparations.findFindingsNeedingASafeMoveDrill(clock.instant(),
                properties.batchSize()));
        Map<Outcome, Integer> outcomes = new EnumMap<>(Outcome.class);
        boolean unavailable = false;
        for (long findingId : batch) {
            Outcome outcome;
            try {
                outcome = service.prepare(findingId);
            } catch (RuntimeException e) {
                // Not a failed preparation (those are outcomes) but an error around it, e.g. the database.
                log.warn("Drill for finding {} skipped this run: {}", findingId, e.getMessage(), e);
                continue;
            }
            outcomes.merge(outcome, 1, Integer::sum);
            if (outcome == Outcome.SERVICE_UNAVAILABLE) {
                unavailable = true;
                break;
            }
        }
        if (!batch.isEmpty()) {
            log.info("Drill preparation run: {} findings, {}{}", batch.size(), outcomes,
                    unavailable ? "; stopped, analysis service unavailable" : "");
        }
        return new RunResult(Map.copyOf(outcomes), unavailable);
    }
}
