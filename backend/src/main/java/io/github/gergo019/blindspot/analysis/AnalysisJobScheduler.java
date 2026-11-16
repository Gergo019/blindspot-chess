package io.github.gergo019.blindspot.analysis;

import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TriggerContext;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

/**
 * Runs {@link AnalysisJob}: stuck-game recovery once at startup, then a run
 * {@link AnalysisJob#nextDelay()} after the previous one ends. That is the
 * configured interval, or longer while the analysis service is unavailable,
 * so a fixed-delay {@code @Scheduled} does not fit.
 */
@Component
@ConditionalOnProperty(name = "blindspot.analysis.job.enabled", havingValue = "true")
public class AnalysisJobScheduler implements SchedulingConfigurer {

    private final AnalysisJob job;
    private final Duration initialDelay;

    public AnalysisJobScheduler(AnalysisJob job,
            @Value("${blindspot.analysis.job.initial-delay:15s}") Duration initialDelay) {
        this.job = job;
        this.initialDelay = initialDelay;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverAtStartup() {
        job.recoverStuckGames();
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addTriggerTask(job::runOnce, this::nextRun);
    }

    /** The first run after the initial delay, every later one the job's current delay after the last one ended. */
    Instant nextRun(TriggerContext context) {
        Instant lastCompletion = context.lastCompletion();
        return lastCompletion == null
                ? context.getClock().instant().plus(initialDelay)
                : lastCompletion.plus(job.nextDelay());
    }
}
