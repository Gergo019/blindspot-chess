package io.github.gergo019.blindspot.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import io.github.gergo019.blindspot.TestData;
import io.github.gergo019.blindspot.account.ChessAccount;
import io.github.gergo019.blindspot.account.ChessAccountRepository;
import io.github.gergo019.blindspot.account.User;
import io.github.gergo019.blindspot.account.UserRepository;
import io.github.gergo019.blindspot.analysis.AnalyzeApi.AnalyzeRequest;
import io.github.gergo019.blindspot.analysis.AnalyzeApi.MoveDto;
import io.github.gergo019.blindspot.game.Game;
import io.github.gergo019.blindspot.game.GameEnums.AnalysisStatus;
import io.github.gergo019.blindspot.game.GameEnums.GameResult;
import io.github.gergo019.blindspot.game.GameEnums.PieceColor;
import io.github.gergo019.blindspot.game.GameEnums.Speed;
import io.github.gergo019.blindspot.game.GameRepository;
import io.github.gergo019.blindspot.lichess.LichessImportService;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Phase 1 exit criterion: one known finished game goes through the backend,
 * the real analysis service (built from ../analysis-service/Dockerfile, the
 * runtime target, with its pinned Stockfish) and PostgreSQL, and the expected
 * motif comes back with a replayable evidence line. A second test runs the
 * same game the way production does: imported from a (recorded-format)
 * Lichess export, stored PENDING, analysed by the background job.
 */
@SpringBootTest(properties = { "blindspot.lichess.importer.enabled=false", "blindspot.analysis.job.enabled=false" })
@Testcontainers
class AnalysisEndToEndIT {

    /** 3. Nd4?? puts the knight where the e5 pawn takes it; White (the user) lost. */
    static final String KNIGHT_GAME = "1. e4 e5 2. Nf3 d6 3. Nd4 exd4 0-1";
    static final int KNIGHT_PLY = 5;

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Container
    static GenericContainer<?> analysisContainer = new GenericContainer<>(
            new ImageFromDockerfile("blindspot-analysis-service-it", false)
                    .withDockerfile(Path.of("../analysis-service/Dockerfile"))
                    .withTarget("runtime")
                    // TARGETARCH is only set automatically by BuildKit; pass it so the
                    // Dockerfile picks the Stockfish binary for the Docker host's CPU.
                    .withBuildArg("TARGETARCH", dockerHostArch()))
            .withExposedPorts(8000)
            .waitingFor(Wait.forHttp("/health").forStatusCode(200))
            .withStartupTimeout(Duration.ofMinutes(5));

    /** Stands in for Lichess: answers every game export with the knight-game fixture. */
    static HttpServer lichess;

    @BeforeAll
    static void startFakeLichess() throws IOException {
        byte[] export;
        try (InputStream in = AnalysisEndToEndIT.class.getClassLoader()
                .getResourceAsStream("lichess/knight-game.ndjson")) {
            export = in.readAllBytes();
        }
        lichess = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        lichess.createContext("/api/games/user/", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson");
            exchange.sendResponseHeaders(200, export.length);
            exchange.getResponseBody().write(export);
            exchange.close();
        });
        lichess.start();
    }

    @AfterAll
    static void stopFakeLichess() {
        lichess.stop(0);
    }

    @DynamicPropertySource
    static void serviceUrls(DynamicPropertyRegistry registry) {
        registry.add("blindspot.analysis-service-url",
                () -> "http://" + analysisContainer.getHost() + ":" + analysisContainer.getMappedPort(8000));
        registry.add("blindspot.lichess.base-url", () -> "http://127.0.0.1:" + lichess.getAddress().getPort());
    }

    @Autowired AnalysisService analysisService;
    @Autowired AnalysisClient analysisClient;
    @Autowired AnalysisJob analysisJob;
    @Autowired LichessImportService importService;
    @Autowired UserRepository users;
    @Autowired ChessAccountRepository accounts;
    @Autowired GameRepository games;
    @Autowired MoveEvaluationRepository evaluations;
    @Autowired MistakeFindingRepository findings;
    @Autowired TransactionTemplate tx;
    @Autowired JsonMapper json;

    @Test
    void knownGameComesBackWithAReplayableHangingPieceFinding() {
        User user = users.save(TestData.user("e2e-player"));
        ChessAccount account = accounts.save(TestData.lichessAccount(user));
        Game game = games.save(new Game(account, "e2e-knight", Instant.parse("2026-11-13T12:00:00Z"), Speed.RAPID,
                true, PieceColor.WHITE, GameResult.LOSS, KNIGHT_GAME));

        AnalysisStatus status = analysisService.analyzeGame(game.getId());

        assertThat(status).isEqualTo(AnalysisStatus.DONE);
        assertThat(games.findById(game.getId()).orElseThrow().getAnalysisStatus()).isEqualTo(AnalysisStatus.DONE);

        // One evaluation per ply, all from the pinned engine at the fixed depth.
        List<MoveEvaluation> moves = evaluations.findByGameIdOrderByPlyAsc(game.getId());
        assertThat(moves).extracting(MoveEvaluation::getPly).containsExactly(1, 2, 3, 4, 5, 6);
        assertThat(moves).extracting(MoveEvaluation::getMoveUci)
                .containsExactly("e2e4", "e7e5", "g1f3", "d7d6", "f3d4", "e5d4");
        assertThat(moves).allSatisfy(m -> {
            assertThat(m.getEngineRun().version()).isEqualTo("Stockfish 19");
            assertThat(m.getEngineRun().depth()).isEqualTo(16);
        });
        assertThat(moves.get(KNIGHT_PLY - 1).isTrainableMistake()).isTrue();

        // The hanging-piece finding on ply 5, read inside a transaction (lazy associations).
        record Stored(int ply, String evidence, String detectorVersion, String facts) {
        }
        List<Stored> stored = tx.execute(s -> findings.findByUserAndMotif(user.getId(), Motif.HANGING_PIECE).stream()
                .map(f -> new Stored(f.getMoveEvaluation().getPly(), f.getEvidenceLineUci(), f.getDetectorVersion(),
                        f.getFacts()))
                .toList());
        assertThat(stored).hasSize(1);
        Stored finding = stored.get(0);
        assertThat(finding.ply()).isEqualTo(KNIGHT_PLY);
        assertThat(finding.detectorVersion()).isEqualTo("hanging-0.2");
        assertThat(finding.evidence()).startsWith("e5d4");
        JsonNode facts = json.readTree(finding.facts());
        assertThat(facts.get("movedPiece").asString()).isEqualTo("knight");
        assertThat(facts.get("piece").asString()).isEqualTo("knight");
        assertThat(facts.get("square").asString()).isEqualTo("d4");

        assertEvidenceLineIsLegal(moves, finding.evidence());
    }

    @Test
    void importedGameGoesFromPendingToDoneThroughTheBackgroundJob() {
        User user = users.save(TestData.user("fixture_white"));
        ChessAccount account = accounts.save(TestData.lichessAccount(user));

        var imported = importService.importRecentGames(account.getId());

        assertThat(imported.imported()).isEqualTo(1);
        Game game = games.findByAccountIdOrderByPlayedAtDesc(account.getId(), Pageable.unpaged()).get(0);
        assertThat(game.getExternalId()).isEqualTo("knightE2");
        assertThat(game.getUserColor()).isEqualTo(PieceColor.WHITE);
        assertThat(game.getResult()).isEqualTo(GameResult.LOSS);
        assertThat(game.getAnalysisStatus()).isEqualTo(AnalysisStatus.PENDING);

        AnalysisJob.RunResult run = analysisJob.runOnce();

        assertThat(run.analysed()).isEqualTo(1);
        assertThat(run.done()).isEqualTo(1);
        Game analysed = games.findById(game.getId()).orElseThrow();
        assertThat(analysed.getAnalysisStatus()).isEqualTo(AnalysisStatus.DONE);
        assertThat(analysed.getAnalysisAttempts()).isEqualTo(1);

        List<MoveEvaluation> moves = evaluations.findByGameIdOrderByPlyAsc(game.getId());
        assertThat(moves).extracting(MoveEvaluation::getMoveUci)
                .containsExactly("e2e4", "e7e5", "g1f3", "d7d6", "f3d4", "e5d4");
        record Stored(long gameId, int ply, String evidence, String detectorVersion) {
        }
        List<Stored> stored = tx.execute(s -> findings.findByUserAndMotif(user.getId(), Motif.HANGING_PIECE).stream()
                .map(f -> new Stored(f.getMoveEvaluation().getGame().getId(), f.getMoveEvaluation().getPly(),
                        f.getEvidenceLineUci(), f.getDetectorVersion()))
                .toList());
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).gameId()).isEqualTo(game.getId());
        assertThat(stored.get(0).ply()).isEqualTo(KNIGHT_PLY);
        assertThat(stored.get(0).detectorVersion()).isEqualTo("hanging-0.2");
        assertThat(stored.get(0).evidence()).startsWith("e5d4");

        // Nothing left to do: a second run analyses nothing.
        assertThat(analysisJob.runOnce().analysed()).isZero();
    }

    /**
     * No maintained Java chess library is published on Maven Central, so the
     * analysis service is the legality oracle: it rejects any PGN with an
     * illegal move (HTTP 422). The PGN is the game up to and including ply 5,
     * then the evidence line, all in UCI (python-chess accepts UCI moves in
     * movetext). The result tag only has to be a finished result; this line is
     * not a real game.
     */
    private void assertEvidenceLineIsLegal(List<MoveEvaluation> moves, String evidence) {
        List<String> line = new ArrayList<>();
        moves.subList(0, KNIGHT_PLY).forEach(m -> line.add(m.getMoveUci()));
        List<String> evidenceMoves = List.of(evidence.split(" "));
        line.addAll(evidenceMoves);

        var replay = analysisClient.analyze(new AnalyzeRequest(String.join(" ", line) + " 1/2-1/2", "WHITE"));

        assertThat(replay.moves()).extracting(MoveDto::moveUci).containsExactlyElementsOf(line);
        assertThat(replay.moves().subList(KNIGHT_PLY, line.size()))
                .extracting(MoveDto::moveUci).containsExactlyElementsOf(evidenceMoves);
    }

    private static String dockerHostArch() {
        String arch = DockerClientFactory.instance().getInfo().getArchitecture();
        return arch != null && (arch.equals("aarch64") || arch.equals("arm64")) ? "arm64" : "amd64";
    }
}
