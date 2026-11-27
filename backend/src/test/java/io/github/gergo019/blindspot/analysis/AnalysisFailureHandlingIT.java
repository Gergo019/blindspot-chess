package io.github.gergo019.blindspot.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import io.github.gergo019.blindspot.TestData;
import io.github.gergo019.blindspot.account.ChessAccount;
import io.github.gergo019.blindspot.account.ChessAccountRepository;
import io.github.gergo019.blindspot.account.UserRepository;
import io.github.gergo019.blindspot.game.Game;
import io.github.gergo019.blindspot.game.GameEnums.AnalysisStatus;
import io.github.gergo019.blindspot.game.GameEnums.GameResult;
import io.github.gergo019.blindspot.game.GameEnums.PieceColor;
import io.github.gergo019.blindspot.game.GameEnums.Speed;
import io.github.gergo019.blindspot.game.GameRepository;

import com.sun.net.httpserver.HttpServer;

/**
 * Failure handling of the background analysis (ADR 0005) on a real
 * PostgreSQL and the real HTTP client, against a stub analysis service that
 * can be down, failing, slow, rejecting or working.
 */
@SpringBootTest(properties = { "blindspot.lichess.importer.enabled=false", "blindspot.analysis.job.enabled=false",
        "blindspot.training.drill-preparation.enabled=false",
        "blindspot.analysis-service-read-timeout=1s", "blindspot.analysis.job.interval=30s" })
@Import(AnalysisFailureHandlingIT.TransactionProbe.class)
@Testcontainers
class AnalysisFailureHandlingIT {

    static final Duration INTERVAL = Duration.ofSeconds(30);

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    static final StubAnalysisService stub = new StubAnalysisService();

    /**
     * Whether a transaction was active, recorded on the calling thread at the
     * moment each request to the analysis service is sent. The stub server
     * answers on its own threads, so the check has to sit in the client: an
     * interceptor added to every RestClient the application builds.
     */
    static final List<Boolean> transactionActiveDuringCall = Collections.synchronizedList(new ArrayList<>());

    @TestConfiguration
    static class TransactionProbe {
        @Bean
        RestClientCustomizer recordTransactionDuringCall() {
            return builder -> builder.requestInterceptor((request, body, execution) -> {
                transactionActiveDuringCall.add(TransactionSynchronizationManager.isActualTransactionActive());
                return execution.execute(request, body);
            });
        }
    }

    @DynamicPropertySource
    static void analysisServiceUrl(DynamicPropertyRegistry registry) {
        registry.add("blindspot.analysis-service-url", () -> "http://127.0.0.1:" + stub.port);
    }

    @Autowired AnalysisJob job;
    @Autowired AnalysisService analysisService;
    @Autowired GameRepository games;
    @Autowired UserRepository users;
    @Autowired ChessAccountRepository accounts;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    ChessAccount account;

    @BeforeEach
    void freshDatabaseAndWorkingService() {
        stub.answerWith(StubAnalysisService.Mode.OK);
        jdbc.execute("TRUNCATE users CASCADE");
        account = accounts.save(TestData.lichessAccount(users.save(TestData.user("player"))));
        // Leave the back-off of an earlier test behind: a run the service answers resets it.
        if (!job.nextDelay().equals(INTERVAL)) {
            pending("warm-up", Instant.parse("2020-01-01T00:00:00Z"));
            job.runOnce();
            jdbc.execute("TRUNCATE users CASCADE");
            account = accounts.save(TestData.lichessAccount(users.save(TestData.user("player"))));
        }
        stub.requests.clear();
        transactionActiveDuringCall.clear();
    }

    @AfterEach
    void releaseSlowRequests() {
        stub.releaseSlowRequests();
    }

    @AfterAll
    static void stopStub() {
        stub.down();
    }

    @Test
    void anOutageNeitherFailsNorChargesTheGameAndTheJobBacksOff() {
        Game older = pending("older", Instant.parse("2026-11-01T10:00:00Z"));
        Game newer = pending("newer", Instant.parse("2026-11-02T10:00:00Z"));
        Game newest = pending("newest", Instant.parse("2026-11-03T10:00:00Z"));

        // Run 1: nothing listens on the port (connection refused). The run stops at the first game.
        stub.down();
        AnalysisJob.RunResult refused = job.runOnce();

        assertThat(refused.serviceUnavailable()).isTrue();
        assertThat(refused.analysed()).isEqualTo(1);
        assertThat(transactionActiveDuringCall).hasSize(1);
        assertThat(reload(older, newer, newest)).allSatisfy(g -> {
            assertThat(g.getAnalysisStatus()).isEqualTo(AnalysisStatus.PENDING);
            assertThat(g.getAnalysisAttempts()).isZero();
        });
        assertThat(job.nextDelay()).isEqualTo(INTERVAL.multipliedBy(2));

        // Run 2: the service is up but failing (HTTP 503): the same.
        stub.answerWith(StubAnalysisService.Mode.UNAVAILABLE_503);
        AnalysisJob.RunResult failing = job.runOnce();

        assertThat(failing.serviceUnavailable()).isTrue();
        assertThat(failing.analysed()).isEqualTo(1);
        assertThat(stub.requests).hasSize(1);
        assertThat(reload(older, newer, newest)).allSatisfy(g -> {
            assertThat(g.getAnalysisStatus()).isEqualTo(AnalysisStatus.PENDING);
            assertThat(g.getAnalysisAttempts()).isZero();
        });
        assertThat(job.nextDelay()).isEqualTo(INTERVAL.multipliedBy(4));

        // Run 3: the service works. Every game ends DONE on its first counted attempt.
        stub.answerWith(StubAnalysisService.Mode.OK);
        AnalysisJob.RunResult working = job.runOnce();

        assertThat(working.serviceUnavailable()).isFalse();
        assertThat(working.done()).isEqualTo(3);
        assertThat(reload(older, newer, newest)).allSatisfy(g -> {
            assertThat(g.getAnalysisStatus()).isEqualTo(AnalysisStatus.DONE);
            assertThat(g.getAnalysisAttempts()).isEqualTo(1);
        });
        assertThat(job.nextDelay()).isEqualTo(INTERVAL);
    }

    @Test
    void newestPendingGamesAreAnalysedFirst() {
        pending("pgn-old", Instant.parse("2026-11-01T10:00:00Z"));
        pending("pgn-newest", Instant.parse("2026-11-03T10:00:00Z"));
        pending("pgn-middle", Instant.parse("2026-11-02T10:00:00Z"));

        job.runOnce();

        assertThat(stub.requests).hasSize(3);
        assertThat(stub.requests.get(0)).contains("pgn-newest");
        assertThat(stub.requests.get(1)).contains("pgn-middle");
        assertThat(stub.requests.get(2)).contains("pgn-old");
    }

    @Test
    void aGameThatTimesOutTwiceEndsFailed() {
        Game slow = pending("slow", Instant.parse("2026-11-01T10:00:00Z"));
        stub.answerWith(StubAnalysisService.Mode.SLOW);

        AnalysisJob.RunResult first = job.runOnce();

        assertThat(first.retried()).isEqualTo(1);
        assertThat(first.serviceUnavailable()).isFalse();
        Game afterFirst = reload(slow).get(0);
        assertThat(afterFirst.getAnalysisStatus()).isEqualTo(AnalysisStatus.PENDING);
        assertThat(afterFirst.getAnalysisAttempts()).isEqualTo(1);
        assertThat(job.nextDelay()).as("a timeout is not an outage").isEqualTo(INTERVAL);

        AnalysisJob.RunResult second = job.runOnce();

        assertThat(second.failed()).isEqualTo(1);
        Game afterSecond = reload(slow).get(0);
        assertThat(afterSecond.getAnalysisStatus()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(afterSecond.getAnalysisAttempts()).isEqualTo(Game.MAX_ANALYSIS_ATTEMPTS);
    }

    @Test
    void anUnreadableResponseIsRetriedOnceThenFails() {
        Game game = pending("unreadable", Instant.parse("2026-11-01T10:00:00Z"));
        stub.answerWith(StubAnalysisService.Mode.UNREADABLE_200);

        AnalysisJob.RunResult first = job.runOnce();

        assertThat(first.retried()).isEqualTo(1);
        assertThat(first.invalidResponse()).isTrue();
        Game afterFirst = reload(game).get(0);
        assertThat(afterFirst.getAnalysisStatus()).isEqualTo(AnalysisStatus.PENDING);
        assertThat(afterFirst.getAnalysisAttempts()).isEqualTo(1);

        AnalysisJob.RunResult second = job.runOnce();

        assertThat(second.failed()).isEqualTo(1);
        Game afterSecond = reload(game).get(0);
        assertThat(afterSecond.getAnalysisStatus()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(afterSecond.getAnalysisAttempts()).isEqualTo(Game.MAX_ANALYSIS_ATTEMPTS);
        assertThat(jdbc.queryForObject("select count(*) from move_evaluations", Integer.class)).isZero();
    }

    @Test
    void anUnstorableResponseStoresNothingCountsAnAttemptAndStopsTheRun() {
        Game newest = pending("pgn-newest", Instant.parse("2026-11-03T10:00:00Z"));
        Game older = pending("pgn-older", Instant.parse("2026-11-02T10:00:00Z"));
        stub.answerWith(StubAnalysisService.Mode.UNKNOWN_MOTIF_200);

        AnalysisJob.RunResult run = job.runOnce();

        assertThat(run.invalidResponse()).isTrue();
        assertThat(run.analysed()).isEqualTo(1);
        assertThat(run.retried()).isEqualTo(1);
        assertThat(stub.requests).hasSize(1).allSatisfy(body -> assertThat(body).contains("pgn-newest"));
        assertThat(reload(newest).get(0)).extracting(Game::getAnalysisStatus, Game::getAnalysisAttempts)
                .containsExactly(AnalysisStatus.PENDING, 1);
        assertThat(reload(older).get(0)).extracting(Game::getAnalysisStatus, Game::getAnalysisAttempts)
                .containsExactly(AnalysisStatus.PENDING, 0);
        assertThat(jdbc.queryForObject("select count(*) from move_evaluations", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from mistake_findings", Integer.class)).isZero();
        assertThat(job.nextDelay()).as("no back-off: the service answered").isEqualTo(INTERVAL);
    }

    @Test
    void contentTheDatabaseRejectsIsRolledBackCompletely() {
        Game game = pending("duplicate-ply", Instant.parse("2026-11-01T10:00:00Z"));
        stub.answerWith(StubAnalysisService.Mode.DUPLICATE_PLY_200);

        AnalysisJob.RunResult first = job.runOnce();

        assertThat(first.invalidResponse()).isTrue();
        assertThat(reload(game).get(0)).extracting(Game::getAnalysisStatus, Game::getAnalysisAttempts)
                .containsExactly(AnalysisStatus.PENDING, 1);
        // The first ply 3 was accepted before the second one hit the unique key: nothing of it may remain.
        assertThat(jdbc.queryForObject("select count(*) from move_evaluations", Integer.class)).isZero();

        AnalysisJob.RunResult second = job.runOnce();

        assertThat(second.failed()).isEqualTo(1);
        assertThat(reload(game).get(0)).extracting(Game::getAnalysisStatus, Game::getAnalysisAttempts)
                .containsExactly(AnalysisStatus.FAILED, Game.MAX_ANALYSIS_ATTEMPTS);
        assertThat(jdbc.queryForObject("select count(*) from move_evaluations", Integer.class)).isZero();
    }

    @Test
    void aRejectedGameEndsFailedAtOnce() {
        Game bad = pending("bad", Instant.parse("2026-11-01T10:00:00Z"));
        stub.answerWith(StubAnalysisService.Mode.REJECTED_422);

        AnalysisJob.RunResult run = job.runOnce();

        assertThat(run.failed()).isEqualTo(1);
        Game failed = reload(bad).get(0);
        assertThat(failed.getAnalysisStatus()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(failed.getAnalysisAttempts()).isEqualTo(1);
    }

    @Test
    void noTransactionIsActiveDuringTheHttpCall() {
        pending("tx", Instant.parse("2026-11-01T10:00:00Z"));

        assertThat(job.runOnce().done()).isEqualTo(1);

        assertThat(transactionActiveDuringCall).containsExactly(false);
        // The probe can see a transaction: inside one, the same check is true.
        Boolean activeInsideTransaction = tx.execute(status -> TransactionSynchronizationManager.isActualTransactionActive());
        assertThat(activeInsideTransaction).isTrue();
    }

    @Test
    void aFailedGameIsNeverPickedUpAgain() {
        Instant longAgo = Instant.parse("2026-01-01T00:00:00Z");
        // FAILED on its first attempt, which started long before the stuck timeout.
        Game rejected = new Game(account, "rejected", Instant.parse("2026-11-05T10:00:00Z"), Speed.RAPID, true,
                PieceColor.WHITE, GameResult.LOSS, "pgn-rejected");
        rejected.startAnalysis(longAgo);
        rejected.failAnalysis();
        rejected = games.save(rejected);
        Game ok = pending("pgn-ok", Instant.parse("2026-11-01T10:00:00Z"));

        for (int run = 0; run < 3; run++) {
            job.runOnce();
            job.recoverStuckGames();
        }
        analysisService.analyzeGame(rejected.getId());

        Game stillFailed = reload(rejected).get(0);
        assertThat(stillFailed.getAnalysisStatus()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(stillFailed.getAnalysisAttempts()).isEqualTo(1);
        assertThat(stillFailed.getAnalysisStartedAt()).isEqualTo(longAgo);
        assertThat(stub.requests).hasSize(1).allSatisfy(body -> assertThat(body).contains("pgn-ok"));
        assertThat(reload(ok).get(0).getAnalysisStatus()).isEqualTo(AnalysisStatus.DONE);
    }

    @Test
    void ofTwoConcurrentClaimsExactlyOneWins() throws Exception {
        Game game = pending("contested", Instant.parse("2026-11-01T10:00:00Z"));
        Instant now = Instant.parse("2026-11-16T12:00:00Z");
        // Both workers load the game as PENDING inside their own open transaction before either claims
        // it: the race that an in-memory status check cannot win.
        CyclicBarrier bothReadPending = new CyclicBarrier(2);
        List<Boolean> claims;
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int worker = 0; worker < 2; worker++) {
                results.add(workers.submit(() -> tx.execute(status -> {
                    assertThat(games.findById(game.getId()).orElseThrow().getAnalysisStatus())
                            .isEqualTo(AnalysisStatus.PENDING);
                    await(bothReadPending);
                    return games.claimForAnalysis(game.getId(), now);
                })));
            }
            claims = new ArrayList<>();
            for (Future<Boolean> result : results) {
                claims.add(result.get(30, TimeUnit.SECONDS));
            }
        }

        assertThat(claims).containsExactlyInAnyOrder(true, false);
        Game claimed = reload(game).get(0);
        assertThat(claimed.getAnalysisStatus()).isEqualTo(AnalysisStatus.RUNNING);
        assertThat(claimed.getAnalysisAttempts()).isEqualTo(1);
        assertThat(claimed.getAnalysisStartedAt()).isEqualTo(now);
    }

    @Test
    void anUnclaimedGameIsNotSentToTheService() {
        Game game = pending("taken", Instant.parse("2026-11-01T10:00:00Z"));
        Boolean claimedElsewhere = tx.execute(status -> games.claimForAnalysis(game.getId(), Instant.now()));
        assertThat(claimedElsewhere).isTrue();

        assertThat(analysisService.analyzeGame(game.getId())).isEqualTo(AnalysisService.Outcome.NOT_CLAIMED);

        assertThat(stub.requests).isEmpty();
        assertThat(reload(game).get(0).getAnalysisAttempts()).isEqualTo(1);
    }

    private Game pending(String pgn, Instant playedAt) {
        return games.save(new Game(account, pgn, playedAt, Speed.RAPID, true, PieceColor.WHITE, GameResult.LOSS,
                pgn));
    }

    private List<Game> reload(Game... toReload) {
        List<Game> reloaded = new ArrayList<>();
        for (Game game : toReload) {
            reloaded.add(games.findById(game.getId()).orElseThrow());
        }
        return reloaded;
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A stand-in for the analysis service on a fixed local port. "Down" means
     * nothing listens on the port, so connections are refused.
     */
    static final class StubAnalysisService {

        enum Mode { OK, UNAVAILABLE_503, REJECTED_422, SLOW, UNREADABLE_200, UNKNOWN_MOTIF_200, DUPLICATE_PLY_200 }

        final int port = freePort();
        final List<String> requests = Collections.synchronizedList(new ArrayList<>());
        private volatile Mode mode = Mode.OK;
        private volatile CountDownLatch slowRequests = new CountDownLatch(1);
        private HttpServer server;

        synchronized void answerWith(Mode newMode) {
            mode = newMode;
            if (server == null) {
                server = start();
            }
        }

        synchronized void down() {
            if (server != null) {
                server.stop(0);
                server = null;
            }
        }

        void releaseSlowRequests() {
            slowRequests.countDown();
            slowRequests = new CountDownLatch(1);
        }

        private HttpServer start() {
            try {
                HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
                http.setExecutor(Executors.newCachedThreadPool());
                http.createContext("/analyze", exchange -> {
                    requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    Mode current = mode;
                    if (current == Mode.SLOW) {
                        try {
                            slowRequests.await(30, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    int status = switch (current) {
                        case OK, UNREADABLE_200, UNKNOWN_MOTIF_200, DUPLICATE_PLY_200 -> 200;
                        case UNAVAILABLE_503, SLOW -> 503;
                        case REJECTED_422 -> 422;
                    };
                    String text = switch (current) {
                        case OK -> AnalysisClientTest.RESPONSE;
                        case UNREADABLE_200 -> "{\"engine\": [";
                        case UNKNOWN_MOTIF_200 -> AnalysisClientTest.RESPONSE.replace("\"HANGING_PIECE\"", "\"PAWN_STORM\"");
                        // Both moves claim ply 3: readable, accepted in memory, rejected by the unique key.
                        case DUPLICATE_PLY_200 -> AnalysisClientTest.RESPONSE.replace("\"ply\": 4", "\"ply\": 3");
                        default -> "{\"detail\":\"stub " + current + "\"}";
                    };
                    byte[] body = text.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    try {
                        exchange.sendResponseHeaders(status, body.length);
                        exchange.getResponseBody().write(body);
                    } catch (IOException e) {
                        // The client gave up (read timeout) and closed the connection.
                    }
                    exchange.close();
                });
                http.start();
                return http;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private static int freePort() {
            try (ServerSocket socket = new ServerSocket(0)) {
                return socket.getLocalPort();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
