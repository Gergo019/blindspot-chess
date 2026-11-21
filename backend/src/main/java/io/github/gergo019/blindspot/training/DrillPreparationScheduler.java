package io.github.gergo019.blindspot.training;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs {@link DrillPreparationJob} a fixed delay after the previous run ended. */
@Component
@ConditionalOnProperty(name = "blindspot.training.drill-preparation.enabled", havingValue = "true")
public class DrillPreparationScheduler {

    private final DrillPreparationJob job;

    public DrillPreparationScheduler(DrillPreparationJob job) {
        this.job = job;
    }

    @Scheduled(initialDelayString = "${blindspot.training.drill-preparation.initial-delay:45s}",
            fixedDelayString = "${blindspot.training.drill-preparation.interval}")
    public void run() {
        job.runOnce();
    }
}
