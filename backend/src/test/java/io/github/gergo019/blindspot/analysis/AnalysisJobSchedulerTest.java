package io.github.gergo019.blindspot.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.SimpleTriggerContext;

/** The scheduler waits the job's current delay, so a back-off reaches the schedule. */
class AnalysisJobSchedulerTest {

    static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");

    AnalysisJob job = mock(AnalysisJob.class);
    AnalysisJobScheduler scheduler = new AnalysisJobScheduler(job, Duration.ofSeconds(15));

    @Test
    void firstRunComesAfterTheInitialDelay() {
        SimpleTriggerContext context = new SimpleTriggerContext(Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(scheduler.nextRun(context)).isEqualTo(NOW.plusSeconds(15));
    }

    @Test
    void laterRunsWaitTheJobsCurrentDelayAfterTheLastOneEnded() {
        Instant ended = NOW.plusSeconds(40);
        SimpleTriggerContext context = new SimpleTriggerContext(NOW, NOW, ended);

        when(job.nextDelay()).thenReturn(Duration.ofSeconds(30));
        assertThat(scheduler.nextRun(context)).isEqualTo(ended.plusSeconds(30));

        when(job.nextDelay()).thenReturn(Duration.ofMinutes(10));
        assertThat(scheduler.nextRun(context)).isEqualTo(ended.plus(Duration.ofMinutes(10)));
    }
}
