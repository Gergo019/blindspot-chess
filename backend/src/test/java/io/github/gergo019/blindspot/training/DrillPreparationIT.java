package io.github.gergo019.blindspot.training;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.sun.net.httpserver.HttpServer;

import io.github.gergo019.blindspot.TestData;
import io.github.gergo019.blindspot.account.ChessAccount;
import io.github.gergo019.blindspot.account.ChessAccountRepository;
import io.github.gergo019.blindspot.account.UserRepository;
import io.github.gergo019.blindspot.analysis.MistakeFinding;
import io.github.gergo019.blindspot.analysis.MistakeFindingRepository;
import io.github.gergo019.blindspot.analysis.MoveEvaluationRepository;
import io.github.gergo019.blindspot.game.Game;
import io.github.gergo019.blindspot.game.GameRepository;
import io.github.gergo019.blindspot.training.DrillPreparationService.Outcome;

/**
 * The drill-preparation job (#36) on a real PostgreSQL and the real HTTP client, against a stub
 * {@code POST /judge} that can work, run over its time budget, be unavailable, reject the position, or answer
 * in ways the backend must not store.
 */
@SpringBootTest(properties = { "blindspot.lichess.importer.enabled=false", "blindspot.analysis.job.enabled=false",
        "blindspot.training.drill-preparation.enabled=false", "blindspot.training.drill-preparation.retry-after=1h" })
@Testcontainers
class DrillPreparationIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    static final StubJudge stub = new StubJudge();

    @DynamicPropertySource
    static void analysisServiceUrl(DynamicPropertyRegistry registry) {
        registry.add("blindspot.analysis-service-url", () -> "http://127.0.0.1:" + stub.port);
    }

    @Autowired DrillPreparationJob job;
    @Autowired DrillRepository drills;
    @Autowired DrillPreparationRepository preparations;
    @Autowired UserRepository users;
    @Autowired ChessAccountRepository accounts;
    @Autowired GameRepository games;
    @Autowired MoveEvaluationRepository evaluations;
    @Autowired MistakeFindingRepository findings;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    ChessAccount account;

    @BeforeEach
    void freshDatabaseAndWorkingService() {
        stub.answerWith(StubJudge.Mode.OK);
        jdbc.execute("TRUNCATE users CASCADE");
        account = accounts.save(TestData.lichessAccount(users.save(TestData.user("player"))));
        stub.requests.clear();
    }

    @AfterAll
    static void stopStub() {
        stub.down();
    }

    @Test
    void aFindingBecomesADrillWithEveryVerdict() {
        MistakeFinding finding = finding("g1", Instant.parse("2026-11-20T10:00:00Z"));

        DrillPreparationJob.RunResult run = job.runOnce();

        assertThat(run.count(Outcome.PREPARED)).isEqualTo(1);
        assertThat(stub.requests).singleElement().asString().contains(TestData.FORK_FEN);
        Drill drill = tx.execute(s -> {
            Drill d = drills.findAll().get(0);
            d.getVerdicts().size();   // load the verdicts inside the transaction
            return d;
        });
        assertThat(drill.getFinding().getId()).isEqualTo(finding.getId());
        assertThat(drill.getTask()).isEqualTo(Drill.Task.FIND_SAFE_MOVE);
        assertThat(drill.getVerdicts()).containsOnlyKeys("f3d4", "b1c3", "d2d3", "f3e5");
        assertThat(drill.getSolutionUci()).isEqualTo("f3d4");
        assertThat(drill.getVerdictEngine().version()).isEqualTo("Stockfish 19");
        assertThat(preparations.count()).isZero();
        // Nothing left to do: the next run sends no request.
        assertThat(job.runOnce().outcomes()).isEmpty();
        assertThat(stub.requests).hasSize(1);
    }

    @Test
    void newestGamesArePreparedFirst() {
        MistakeFinding old = finding("old", Instant.parse("2026-11-01T10:00:00Z"));
        MistakeFinding newest = finding("new", Instant.parse("2026-11-20T10:00:00Z"));
        MistakeFinding middle = finding("mid", Instant.parse("2026-11-10T10:00:00Z"));

        assertThat(job.runOnce().count(Outcome.PREPARED)).isEqualTo(3);

        // Drills get increasing ids in the order they are stored.
        List<Long> findingsInStoreOrder = jdbc.queryForList("SELECT finding_id FROM drills ORDER BY id", Long.class);
        assertThat(findingsInStoreOrder).containsExactly(newest.getId(), middle.getId(), old.getId());
    }

    @Test
    void overTheTimeBudgetNoVerdictsAreStoredAndTheFindingIsRetriedLater() {
        MistakeFinding finding = finding("g1", Instant.parse("2026-11-20T10:00:00Z"));
        stub.answerWith(StubJudge.Mode.BUDGET_EXCEEDED_503);

        DrillPreparationJob.RunResult run = job.runOnce();

        assertThat(run.count(Outcome.RETRY_LATER)).isEqualTo(1);
        assertThat(run.serviceUnavailable()).isFalse();
        assertThat(drills.count()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM drill_move_verdicts", Long.class)).isZero();
        DrillPreparation preparation = preparations.findByFindingIdAndTask(finding.getId(), Drill.Task.FIND_SAFE_MOVE)
                .orElseThrow();
        assertThat(preparation.getStatus()).isEqualTo(DrillPreparation.Status.PENDING);
        assertThat(preparation.getAttempts()).isEqualTo(1);
        assertThat(preparation.getLastError()).contains("BUDGET_EXCEEDED");
        assertThat(preparation.getNextAttemptAt()).isCloseTo(Instant.now().plus(Duration.ofHours(1)),
                within(1, ChronoUnit.MINUTES));

        // Not retried before its time, even though the service would now answer.
        stub.answerWith(StubJudge.Mode.OK);
        assertThat(job.runOnce().outcomes()).isEmpty();
        assertThat(stub.requests).hasSize(1);

        // Once the retry time has passed, it is prepared, and the preparation record is gone.
        jdbc.update("UPDATE drill_preparations SET next_attempt_at = ?", Timestamp.from(Instant.now().minusSeconds(1)));
        assertThat(job.runOnce().count(Outcome.PREPARED)).isEqualTo(1);
        assertThat(drills.count()).isEqualTo(1);
        assertThat(preparations.count()).isZero();
    }

    @Test
    void aSecondBudgetFailureCountsAnotherAttempt() {
        MistakeFinding finding = finding("g1", Instant.parse("2026-11-20T10:00:00Z"));
        stub.answerWith(StubJudge.Mode.BUDGET_EXCEEDED_503);
        job.runOnce();
        jdbc.update("UPDATE drill_preparations SET next_attempt_at = ?", Timestamp.from(Instant.now().minusSeconds(1)));

        job.runOnce();

        DrillPreparation preparation = preparations.findByFindingIdAndTask(finding.getId(), Drill.Task.FIND_SAFE_MOVE)
                .orElseThrow();
        assertThat(preparation.getAttempts()).isEqualTo(2);
        assertThat(preparation.getStatus()).isEqualTo(DrillPreparation.Status.PENDING);
        assertThat(drills.count()).isZero();
    }

    @Test
    void anUnavailableServiceStopsTheRunAndRecordsNothing() {
        finding("g1", Instant.parse("2026-11-20T10:00:00Z"));
        finding("g2", Instant.parse("2026-11-19T10:00:00Z"));
        stub.answerWith(StubJudge.Mode.UNAVAILABLE_503);

        DrillPreparationJob.RunResult run = job.runOnce();

        assertThat(run.serviceUnavailable()).isTrue();
        assertThat(stub.requests).hasSize(1);
        assertThat(preparations.count()).isZero();
        assertThat(drills.count()).isZero();

        stub.answerWith(StubJudge.Mode.OK);
        assertThat(job.runOnce().count(Outcome.PREPARED)).isEqualTo(2);
    }

    @Test
    void aRejectedPositionIsSkippedForGood() {
        MistakeFinding finding = finding("g1", Instant.parse("2026-11-20T10:00:00Z"));
        stub.answerWith(StubJudge.Mode.REJECTED_422);

        assertThat(job.runOnce().count(Outcome.SKIPPED)).isEqualTo(1);
        assertSkipped(finding, "rejected");
    }

    @Test
    void verdictsThatAcceptTheGamesMistakeGiveNoDrill() {
        // The sanity rule (ADR 0008): the engine no longer supports the drill's premise.
        MistakeFinding finding = finding("g1", Instant.parse("2026-11-20T10:00:00Z"));
        stub.answerWith(StubJudge.Mode.ACCEPTS_THE_MISTAKE);

        assertThat(job.runOnce().count(Outcome.SKIPPED)).isEqualTo(1);
        assertSkipped(finding, "accept the game's move f3e5");
    }

    @Test
    void verdictsForAnotherPositionAreNotStored() {
        MistakeFinding finding = finding("g1", Instant.parse("2026-11-20T10:00:00Z"));
        stub.answerWith(StubJudge.Mode.OTHER_POSITION);

        assertThat(job.runOnce().count(Outcome.RETRY_LATER)).isEqualTo(1);
        assertThat(drills.count()).isZero();
        assertThat(preparations.findByFindingIdAndTask(finding.getId(), Drill.Task.FIND_SAFE_MOVE).orElseThrow()
                .getLastError()).contains("INVALID_RESPONSE");
    }

    @Test
    void anInconsistentVerdictIsNotStored() {
        finding("g1", Instant.parse("2026-11-20T10:00:00Z"));
        stub.answerWith(StubJudge.Mode.INCONSISTENT);

        assertThat(job.runOnce().count(Outcome.RETRY_LATER)).isEqualTo(1);
        assertThat(drills.count()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM drill_move_verdicts", Long.class)).isZero();
    }

    private void assertSkipped(MistakeFinding finding, String reason) {
        DrillPreparation preparation = preparations.findByFindingIdAndTask(finding.getId(), Drill.Task.FIND_SAFE_MOVE)
                .orElseThrow();
        assertThat(preparation.getStatus()).isEqualTo(DrillPreparation.Status.SKIPPED);
        assertThat(preparation.getNextAttemptAt()).isNull();
        assertThat(preparation.getLastError()).contains(reason);
        assertThat(drills.count()).isZero();
        // Never tried again, even once the service would answer.
        stub.answerWith(StubJudge.Mode.OK);
        stub.requests.clear();
        assertThat(job.runOnce().outcomes()).isEmpty();
        assertThat(stub.requests).isEmpty();
    }

    private MistakeFinding finding(String gameId, Instant playedAt) {
        return tx.execute(s -> {
            Game game = new Game(account, gameId, playedAt, io.github.gergo019.blindspot.game.GameEnums.Speed.RAPID,
                    true, io.github.gergo019.blindspot.game.GameEnums.PieceColor.WHITE,
                    io.github.gergo019.blindspot.game.GameEnums.GameResult.LOSS, "1. e4 e5 0-1");
            games.save(game);
            return findings.save(TestData.hangingPiece(evaluations.save(TestData.userBlunder(game, 7))));
        });
    }

    /** A stand-in for the analysis service's POST /judge on a fixed local port. */
    static final class StubJudge {

        enum Mode {
            OK, BUDGET_EXCEEDED_503, UNAVAILABLE_503, REJECTED_422, ACCEPTS_THE_MISTAKE, OTHER_POSITION, INCONSISTENT
        }

        /** Verdicts for TestData.FORK_FEN in the service's JSON (see analysis-service/README.md). */
        static final String VERDICTS = """
                {
                  "engine": { "name": "Stockfish", "version": "19", "depth": 16, "threads": 1, "hashMb": 64 },
                  "fen": "%s",
                  "sideToMove": "WHITE",
                  "acceptBelow": 0.1,
                  "verdicts": [
                    { "uci": "f3d4", "san": "Nxd4", "score": { "cp": 40 }, "drop": 0.0, "accepted": true,
                      "refutationUci": "c6d4 c2c3" },
                    { "uci": "b1c3", "san": "Nc3", "score": { "cp": 12 }, "drop": 0.0999, "accepted": true,
                      "refutationUci": "d4f3 d1f3" },
                    { "uci": "d2d3", "san": "d3", "score": { "cp": 8 }, "drop": 0.1, "accepted": false,
                      "refutationUci": "d4f3 d1f3" },
                    { "uci": "f3e5", "san": "Nxe5", "score": { "cp": -210 }, "drop": %s, "accepted": %s,
                      "refutationUci": "d8g5 e5f7 g5g2" }
                  ]
                }
                """;

        final int port = freePort();
        final List<String> requests = Collections.synchronizedList(new ArrayList<>());
        private volatile Mode mode = Mode.OK;
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

        private HttpServer start() {
            try {
                HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
                http.setExecutor(Executors.newCachedThreadPool());
                http.createContext("/judge", exchange -> {
                    requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    Mode current = mode;
                    int status = switch (current) {
                        case BUDGET_EXCEEDED_503, UNAVAILABLE_503 -> 503;
                        case REJECTED_422 -> 422;
                        default -> 200;
                    };
                    String text = switch (current) {
                        case OK -> VERDICTS.formatted(TestData.FORK_FEN, "0.6", "false");
                        case ACCEPTS_THE_MISTAKE -> VERDICTS.formatted(TestData.FORK_FEN, "0.05", "true");
                        case OTHER_POSITION -> VERDICTS.formatted("8/8/8/8/8/8/8/K6k w - - 0 1", "0.6", "false");
                        // accepted although the drop is above the threshold
                        case INCONSISTENT -> VERDICTS.formatted(TestData.FORK_FEN, "0.6", "true");
                        case BUDGET_EXCEEDED_503 -> """
                                {"detail":{"reason":"JUDGE_BUDGET_EXCEEDED","message":"Judging took longer than the budget",\
                                "budgetSeconds":60.0,"elapsedSeconds":61.2,"movesJudged":30,"legalMoves":33}}""";
                        case UNAVAILABLE_503 -> "{\"detail\":\"Could not start Stockfish\"}";
                        case REJECTED_422 -> "{\"detail\":\"Not a legal position: opposite check\"}";
                    };
                    byte[] body = text.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, body.length);
                    exchange.getResponseBody().write(body);
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
