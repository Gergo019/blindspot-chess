package io.github.gergo019.blindspot.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import io.github.gergo019.blindspot.TestData;
import io.github.gergo019.blindspot.analysis.AnalysisService.Outcome;
import io.github.gergo019.blindspot.game.Game;
import io.github.gergo019.blindspot.game.GameEnums.AnalysisStatus;
import io.github.gergo019.blindspot.game.GameRepository;

/** Batch order, the worker cap, stopping and backing off, and stuck-game recovery, with mocks: no database, no HTTP. */
class AnalysisJobTest {

    static final Instant NOW = Instant.parse("2026-11-13T15:00:00Z");
    static final Duration STUCK_AFTER = Duration.ofMinutes(30);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(180);

    GameRepository games = mock(GameRepository.class);
    AnalysisService analysisService = mock(AnalysisService.class);

    @BeforeEach
    void noStuckGamesByDefault() {
        when(games.findByAnalysisStatusAndAnalysisStartedAtBefore(eq(AnalysisStatus.RUNNING), any()))
                .thenReturn(List.of());
    }

    AnalysisJob job(int workers, int batchSize) {
        return new AnalysisJob(games, analysisService, mock(PlatformTransactionManager.class),
                Clock.fixed(NOW, ZoneOffset.UTC),
                new AnalysisJobProperties(false, workers, batchSize, Duration.ofSeconds(30), STUCK_AFTER),
                READ_TIMEOUT);
    }

    @Test
    void analysesTheNewestPendingGamesOfOneBatchInOrderOneAtATime() {
        List<Game> pending = List.of(game(11L), game(12L), game(13L));
        when(games.findByAnalysisStatusOrderByPlayedAtDesc(AnalysisStatus.PENDING, PageRequest.of(0, 3)))
                .thenReturn(pending);
        when(analysisService.analyzeGame(anyLong())).thenReturn(Outcome.DONE);
        when(analysisService.analyzeGame(12L)).thenReturn(Outcome.FAILED);

        AnalysisJob.RunResult result = job(1, 3).runOnce();

        assertThat(result.analysed()).isEqualTo(3);
        assertThat(result.done()).isEqualTo(2);
        assertThat(result.failed()).isEqualTo(1);
        InOrder order = inOrder(analysisService);
        order.verify(analysisService).analyzeGame(11L);
        order.verify(analysisService).analyzeGame(12L);
        order.verify(analysisService).analyzeGame(13L);
    }

    @Test
    void oneGameThrowingDoesNotStopTheBatch() {
        when(games.findByAnalysisStatusOrderByPlayedAtDesc(eq(AnalysisStatus.PENDING), any()))
                .thenReturn(List.of(game(1L), game(2L)));
        when(analysisService.analyzeGame(1L)).thenThrow(new IllegalStateException("Cannot start analysis from DONE"));
        when(analysisService.analyzeGame(2L)).thenReturn(Outcome.DONE);

        assertThat(job(1, 10).runOnce().done()).isEqualTo(1);
        verify(analysisService).analyzeGame(2L);
    }

    @Test
    void neverRunsMoreAnalysesAtOnceThanTheWorkers() throws Exception {
        List<Game> pending = new ArrayList<>();
        for (long id = 1; id <= 6; id++) {
            pending.add(game(id));
        }
        when(games.findByAnalysisStatusOrderByPlayedAtDesc(eq(AnalysisStatus.PENDING), any())).thenReturn(pending);
        AtomicInteger running = new AtomicInteger();
        List<Integer> seen = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch bothRunning = new CountDownLatch(2);
        when(analysisService.analyzeGame(anyLong())).thenAnswer(call -> {
            seen.add(running.incrementAndGet());
            bothRunning.countDown();
            bothRunning.await(5, TimeUnit.SECONDS);
            Thread.sleep(20);
            running.decrementAndGet();
            return Outcome.DONE;
        });

        assertThat(job(2, 10).runOnce().done()).isEqualTo(6);
        assertThat(seen).hasSize(6).allMatch(n -> n <= 2).contains(2);
    }

    @Test
    void queueIsAskedForTheNewestGamesFirst() {
        when(games.findByAnalysisStatusOrderByPlayedAtDesc(eq(AnalysisStatus.PENDING), any())).thenReturn(List.of());

        job(1, 7).runOnce();

        // Newest first is the repository method's order; the real ordering is checked on PostgreSQL in
        // AnalysisFailureHandlingIT.
        verify(games).findByAnalysisStatusOrderByPlayedAtDesc(AnalysisStatus.PENDING, PageRequest.of(0, 7));
    }

    @Test
    void serviceUnavailableStopsTheBatchAtOnce() {
        when(games.findByAnalysisStatusOrderByPlayedAtDesc(eq(AnalysisStatus.PENDING), any()))
                .thenReturn(List.of(game(1L), game(2L), game(3L)));
        when(analysisService.analyzeGame(1L)).thenReturn(Outcome.DONE);
        when(analysisService.analyzeGame(2L)).thenReturn(Outcome.SERVICE_UNAVAILABLE);

        AnalysisJob.RunResult result = job(1, 10).runOnce();

        assertThat(result.serviceUnavailable()).isTrue();
        assertThat(result.analysed()).isEqualTo(2);
        assertThat(result.done()).isEqualTo(1);
        verify(analysisService, never()).analyzeGame(3L);
    }

    @Test
    void serviceUnavailableStopsStartingGamesWithSeveralWorkers() throws Exception {
        List<Game> pending = new ArrayList<>();
        for (long id = 1; id <= 20; id++) {
            pending.add(game(id));
        }
        when(games.findByAnalysisStatusOrderByPlayedAtDesc(eq(AnalysisStatus.PENDING), any())).thenReturn(pending);
        AtomicInteger calls = new AtomicInteger();
        when(analysisService.analyzeGame(anyLong())).thenAnswer(call -> {
            calls.incrementAndGet();
            return Outcome.SERVICE_UNAVAILABLE;
        });

        AnalysisJob.RunResult result = job(2, 20).runOnce();

        assertThat(result.serviceUnavailable()).isTrue();
        // At most one game per worker can have started before the first failure was seen.
        assertThat(calls.get()).isLessThanOrEqualTo(2);
        assertThat(result.analysed()).isEqualTo(calls.get());
    }

    @Test
    void anInvalidResponseStopsTheBatchWithoutBackingOff() {
        when(games.findByAnalysisStatusOrderByPlayedAtDesc(eq(AnalysisStatus.PENDING), any()))
                .thenReturn(List.of(game(1L), game(2L), game(3L)));
        when(analysisService.analyzeGame(1L)).thenReturn(Outcome.DONE);
        when(analysisService.analyzeGame(2L)).thenReturn(Outcome.INVALID_RESPONSE_RETRY);
        AnalysisJob job = job(1, 10);

        AnalysisJob.RunResult result = job.runOnce();

        assertThat(result.invalidResponse()).isTrue();
        assertThat(result.serviceUnavailable()).isFalse();
        assertThat(result.analysed()).isEqualTo(2);
        assertThat(result.retried()).isEqualTo(1);
        verify(analysisService, never()).analyzeGame(3L);
        assertThat(job.nextDelay()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void anInvalidResponseOnTheLastAttemptCountsAsFailedAndStopsTheBatch() {
        when(games.findByAnalysisStatusOrderByPlayedAtDesc(eq(AnalysisStatus.PENDING), any()))
                .thenReturn(List.of(game(1L), game(2L)));
        when(analysisService.analyzeGame(1L)).thenReturn(Outcome.INVALID_RESPONSE_FAILED);

        AnalysisJob.RunResult result = job(1, 10).runOnce();

        assertThat(result.invalidResponse()).isTrue();
        assertThat(result.failed()).isEqualTo(1);
        verify(analysisService, never()).analyzeGame(2L);
    }

    @Test
    void anInvalidResponseStopsStartingGamesWithSeveralWorkers() {
        List<Game> pending = new ArrayList<>();
        for (long id = 1; id <= 20; id++) {
            pending.add(game(id));
        }
        when(games.findByAnalysisStatusOrderByPlayedAtDesc(eq(AnalysisStatus.PENDING), any())).thenReturn(pending);
        AtomicInteger calls = new AtomicInteger();
        when(analysisService.analyzeGame(anyLong())).thenAnswer(call -> {
            calls.incrementAndGet();
            return Outcome.INVALID_RESPONSE_RETRY;
        });

        AnalysisJob.RunResult result = job(2, 20).runOnce();

        assertThat(result.invalidResponse()).isTrue();
        assertThat(calls.get()).isLessThanOrEqualTo(2);
    }

    @Test
    void anInvalidResponseShowsTheServiceIsBack() {
        when(games.findByAnalysisStatusOrderByPlayedAtDesc(eq(AnalysisStatus.PENDING), any()))
                .thenReturn(List.of(game(1L)));
        when(analysisService.analyzeGame(1L)).thenReturn(Outcome.SERVICE_UNAVAILABLE, Outcome.INVALID_RESPONSE_RETRY);
        AnalysisJob job = job(1, 10);

        job.runOnce();
        assertThat(job.nextDelay()).isEqualTo(Duration.ofSeconds(60));
        job.runOnce();
        assertThat(job.nextDelay()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void timeoutsDoNotStopTheBatch() {
        when(games.findByAnalysisStatusOrderByPlayedAtDesc(eq(AnalysisStatus.PENDING), any()))
                .thenReturn(List.of(game(1L), game(2L), game(3L)));
        when(analysisService.analyzeGame(1L)).thenReturn(Outcome.RETRY);
        when(analysisService.analyzeGame(2L)).thenReturn(Outcome.FAILED);
        when(analysisService.analyzeGame(3L)).thenReturn(Outcome.DONE);

        AnalysisJob.RunResult result = job(1, 10).runOnce();

        assertThat(result.serviceUnavailable()).isFalse();
        assertThat(result).extracting(AnalysisJob.RunResult::retried, AnalysisJob.RunResult::failed,
                AnalysisJob.RunResult::done).containsExactly(1, 1, 1);
    }

    @Test
    void backsOffWhileTheServiceIsUnavailableAndResetsWhenItAnswers() {
        when(games.findByAnalysisStatusOrderByPlayedAtDesc(eq(AnalysisStatus.PENDING), any()))
                .thenReturn(List.of(game(1L)));
        when(analysisService.analyzeGame(1L)).thenReturn(Outcome.SERVICE_UNAVAILABLE);
        AnalysisJob job = job(1, 10); // interval 30 s

        assertThat(job.nextDelay()).isEqualTo(Duration.ofSeconds(30));
        List<Duration> delays = new ArrayList<>();
        for (int run = 0; run < 7; run++) {
            job.runOnce();
            delays.add(job.nextDelay());
        }
        assertThat(delays).extracting(Duration::toSeconds).containsExactly(60L, 120L, 240L, 480L, 600L, 600L, 600L);

        when(analysisService.analyzeGame(1L)).thenReturn(Outcome.DONE);
        job.runOnce();
        assertThat(job.nextDelay()).isEqualTo(Duration.ofSeconds(30));

        when(analysisService.analyzeGame(1L)).thenReturn(Outcome.SERVICE_UNAVAILABLE);
        job.runOnce();
        assertThat(job.nextDelay()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void aRejectedGameAlsoShowsTheServiceIsBack() {
        when(games.findByAnalysisStatusOrderByPlayedAtDesc(eq(AnalysisStatus.PENDING), any()))
                .thenReturn(List.of(game(1L)));
        when(analysisService.analyzeGame(1L)).thenReturn(Outcome.SERVICE_UNAVAILABLE, Outcome.RETRY, Outcome.FAILED);
        AnalysisJob job = job(1, 10);

        job.runOnce();
        assertThat(job.nextDelay()).isEqualTo(Duration.ofSeconds(60));
        job.runOnce(); // a timeout says nothing about availability: the back-off stays
        assertThat(job.nextDelay()).isEqualTo(Duration.ofSeconds(60));
        job.runOnce(); // a 422 (FAILED) is an answer
        assertThat(job.nextDelay()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void backOffNeverDropsBelowALongInterval() {
        when(games.findByAnalysisStatusOrderByPlayedAtDesc(eq(AnalysisStatus.PENDING), any()))
                .thenReturn(List.of(game(1L)));
        when(analysisService.analyzeGame(1L)).thenReturn(Outcome.SERVICE_UNAVAILABLE);
        AnalysisJob job = new AnalysisJob(games, analysisService, mock(PlatformTransactionManager.class),
                Clock.fixed(NOW, ZoneOffset.UTC),
                new AnalysisJobProperties(false, 1, 10, Duration.ofMinutes(15), Duration.ofHours(1)), READ_TIMEOUT);

        job.runOnce();

        assertThat(job.nextDelay()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void stuckGameGoesBackToPendingOnceAndFailsTheSecondTime() {
        Game firstTime = game(21L);
        firstTime.startAnalysis(NOW.minus(Duration.ofHours(1)));
        Game secondTime = game(22L);
        secondTime.startAnalysis(NOW.minus(Duration.ofHours(3)));
        secondTime.recoverStuckAnalysis();
        secondTime.startAnalysis(NOW.minus(Duration.ofHours(1)));
        when(games.findByAnalysisStatusAndAnalysisStartedAtBefore(AnalysisStatus.RUNNING, NOW.minus(STUCK_AFTER)))
                .thenReturn(List.of(firstTime, secondTime));
        when(games.findByAnalysisStatusOrderByPlayedAtDesc(eq(AnalysisStatus.PENDING), any())).thenReturn(List.of());

        AnalysisJob.RunResult result = job(1, 10).runOnce();

        assertThat(result.recovered()).isEqualTo(1);
        assertThat(result.failedAsStuck()).isEqualTo(1);
        assertThat(firstTime.getAnalysisStatus()).isEqualTo(AnalysisStatus.PENDING);
        assertThat(secondTime.getAnalysisStatus()).isEqualTo(AnalysisStatus.FAILED);
        verify(games).save(firstTime);
        verify(games).save(secondTime);
    }

    @Test
    void failedGamesAreNeverPickedUp() {
        when(games.findByAnalysisStatusOrderByPlayedAtDesc(eq(AnalysisStatus.PENDING), any())).thenReturn(List.of());

        job(1, 10).runOnce();

        verify(games, never()).findByAnalysisStatusOrderByPlayedAtDesc(eq(AnalysisStatus.FAILED), any());
        verify(analysisService, never()).analyzeGame(anyLong());
    }

    @Test
    void settingsAreChecked() {
        assertThatThrownBy(() -> job(0, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> job(1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AnalysisJob(games, analysisService, mock(PlatformTransactionManager.class),
                Clock.systemUTC(), new AnalysisJobProperties(false, 1, 10, Duration.ofSeconds(30),
                        Duration.ofMinutes(2)), READ_TIMEOUT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stuck-after");
    }

    /** A game with an id, as if loaded from the database. */
    private static Game game(long id) {
        Game game = TestData.game(TestData.lichessAccount(TestData.user("player")), "g" + id);
        ReflectionTestUtils.setField(game, "id", id);
        return game;
    }
}
