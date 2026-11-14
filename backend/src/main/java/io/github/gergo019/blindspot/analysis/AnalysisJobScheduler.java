package io.github.gergo019.blindspot.analysis;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link AnalysisJob}: stuck-game recovery once at startup, then a run
 * every {@code blindspot.analysis.job.interval} after the previous one ends.
 */
@Component
@ConditionalOnProperty(name = "blindspot.analysis.job.enabled", havingValue = "true")
public class AnalysisJobScheduler {

    private final AnalysisJob job;

    public AnalysisJobScheduler(AnalysisJob job) {
        this.job = job;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverAtStartup() {
        job.recoverStuckGames();
    }

    @Scheduled(initialDelayString = "${blindspot.analysis.job.initial-delay:15s}",
            fixedDelayString = "${blindspot.analysis.job.interval}")
    public void run() {
        job.runOnce();
    }
}
