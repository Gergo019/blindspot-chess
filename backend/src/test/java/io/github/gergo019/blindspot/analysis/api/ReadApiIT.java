package io.github.gergo019.blindspot.analysis.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import io.github.gergo019.blindspot.ApiProblems;
import io.github.gergo019.blindspot.TestData;
import io.github.gergo019.blindspot.account.ChessAccount;
import io.github.gergo019.blindspot.account.ChessAccountRepository;
import io.github.gergo019.blindspot.account.User;
import io.github.gergo019.blindspot.account.UserRepository;
import io.github.gergo019.blindspot.analysis.Explanation;
import io.github.gergo019.blindspot.analysis.ExplanationRepository;
import io.github.gergo019.blindspot.analysis.MistakeFinding;
import io.github.gergo019.blindspot.analysis.MistakeFindingRepository;
import io.github.gergo019.blindspot.analysis.Motif;
import io.github.gergo019.blindspot.analysis.MoveEvaluation;
import io.github.gergo019.blindspot.analysis.MoveEvaluationRepository;
import io.github.gergo019.blindspot.game.Game;
import io.github.gergo019.blindspot.game.GameEnums.AnalysisStatus;
import io.github.gergo019.blindspot.game.GameEnums.GameResult;
import io.github.gergo019.blindspot.game.GameEnums.PieceColor;
import io.github.gergo019.blindspot.game.GameEnums.Speed;
import io.github.gergo019.blindspot.game.GameRepository;
import jakarta.persistence.EntityManagerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The read API (#51) over real HTTP against PostgreSQL: the pattern report's edge cases, paging and
 * ordering, the finding detail, the move list, errors as problem details without internals, GET only,
 * no CORS, no N+1, and the OpenAPI description.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "blindspot.lichess.importer.enabled=false", "blindspot.analysis.job.enabled=false",
        "blindspot.training.drill-preparation.enabled=false",
        "blindspot.lichess.username=Player1", "spring.jpa.properties.hibernate.generate_statistics=true" })
@Testcontainers
class ReadApiIT {

    private static final String FACTS = """
            {"piece":"N","square":"e5","capturedBy":"q","materialLost":3,"movedPiece":"N","movedFrom":"f3","movedTo":"e5"}""";
    private static final Instant T0 = Instant.parse("2026-10-01T12:00:00Z");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired UserRepository users;
    @Autowired ChessAccountRepository accounts;
    @Autowired GameRepository games;
    @Autowired MoveEvaluationRepository evaluations;
    @Autowired MistakeFindingRepository findings;
    @Autowired ExplanationRepository explanations;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired JsonMapper json;

    private final HttpClient http = HttpClient.newHttpClient();
    private ChessAccount player;
    private ChessAccount other;
    private int gameCounter;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE users, chess_accounts, games, move_evaluations, mistake_findings, explanations "
                + "RESTART IDENTITY CASCADE");
        // The configured player is "Player1", lower-cased like the account the Lichess setup creates.
        player = tx.execute(s -> accounts.save(TestData.lichessAccount(users.save(new User("player1")))));
        other = tx.execute(s -> accounts.save(TestData.lichessAccount(users.save(new User("someone-else")))));
        gameCounter = 0;
    }

    // ---- fixtures ----

    /** A game of {@code account}, {@code minutesAgo} before T0, with the given speed and analysis status. */
    private Game game(ChessAccount account, int minutesAgo, Speed speed, AnalysisStatus status, PieceColor color) {
        return tx.execute(s -> {
            Game game = new Game(account, "g" + (++gameCounter), T0.minusSeconds(60L * minutesAgo), speed, true,
                    color, GameResult.LOSS, "1. e4 e5 2. Nf3 Nc6 3. Bc4 Nd4 4. Nxe5 Qg5 0-1");
            game.describeOpponent("rival", 1500);
            game.setUserRating(1480);
            game.describeOpening("C50", "Italian Game");
            game.setTimeControl("600+0");
            if (status != AnalysisStatus.PENDING) {
                game.startAnalysis(T0);
                if (status == AnalysisStatus.DONE) {
                    game.completeAnalysis();
                } else if (status == AnalysisStatus.FAILED) {
                    game.failAnalysis();
                }
            }
            return games.save(game);
        });
    }

    private Game doneRapid(ChessAccount account, int minutesAgo) {
        return game(account, minutesAgo, Speed.RAPID, AnalysisStatus.DONE, PieceColor.WHITE);
    }

    /** A user blunder at {@code ply} with a finding of {@code motif}. */
    private MistakeFinding finding(Game game, int ply, Motif motif) {
        return tx.execute(s -> {
            MoveEvaluation blunder = evaluations.save(TestData.userBlunder(game, ply));
            return findings.save(new MistakeFinding(blunder, motif, "d8g5 e5f7 g5g2", "hanging-0.4", FACTS));
        });
    }

    // ---- HTTP helpers ----

    private HttpResponse<String> request(String method, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, BodyPublishers.noBody()).build();
        return http.send(request, BodyHandlers.ofString());
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> response = request("GET", path);
        assertThat(response.statusCode()).as("GET %s: %s", path, response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private JsonNode pattern(JsonNode report, Motif motif) {
        for (JsonNode m : report.get("motifs")) {
            if (m.get("motif").asString().equals(motif.name())) {
                return m;
            }
        }
        throw new AssertionError("No " + motif + " in " + report);
    }

    /** A problem detail with {@code instance} and without internals ({@link ApiProblems#assertProblem}). */
    private JsonNode problem(HttpResponse<String> response, int status) {
        return ApiProblems.assertProblem(json, response, status);
    }

    // ---- GET /api/patterns ----

    @Nested
    class Patterns {

        @Test
        void twoGamesAreNotAPatternButTheSampleSizeIsShown() throws Exception {
            finding(doneRapid(player, 1), 7, Motif.HANGING_PIECE);
            finding(doneRapid(player, 2), 7, Motif.HANGING_PIECE);
            doneRapid(player, 3);

            JsonNode report = get("/api/patterns");
            assertThat(report.get("window").asInt()).isEqualTo(20);
            assertThat(report.get("threshold").asInt()).isEqualTo(3);
            assertThat(report.get("sampleSize").asInt()).isEqualTo(3);
            JsonNode hanging = pattern(report, Motif.HANGING_PIECE);
            assertThat(hanging.get("gamesWithFinding").asInt()).isEqualTo(2);
            assertThat(hanging.get("sampleSize").asInt()).isEqualTo(3);
            assertThat(hanging.get("pattern").asBoolean()).isFalse();
        }

        @Test
        void threeGamesAreAPattern() throws Exception {
            IntStream.rangeClosed(1, 3).forEach(i -> finding(doneRapid(player, i), 7, Motif.HANGING_PIECE));

            JsonNode hanging = pattern(get("/api/patterns"), Motif.HANGING_PIECE);
            assertThat(hanging.get("gamesWithFinding").asInt()).isEqualTo(3);
            assertThat(hanging.get("pattern").asBoolean()).isTrue();
        }

        @Test
        void twoFindingsInOneGameCountOnce() throws Exception {
            Game twice = doneRapid(player, 1);
            finding(twice, 7, Motif.HANGING_PIECE);
            finding(twice, 9, Motif.HANGING_PIECE);
            finding(doneRapid(player, 2), 7, Motif.HANGING_PIECE);

            JsonNode hanging = pattern(get("/api/patterns"), Motif.HANGING_PIECE);
            assertThat(hanging.get("gamesWithFinding").asInt()).isEqualTo(2);
            assertThat(hanging.get("pattern").asBoolean()).isFalse();
        }

        @Test
        void fewerThanTwentyEligibleGamesGiveASmallerSample() throws Exception {
            IntStream.rangeClosed(1, 3).forEach(i -> finding(doneRapid(player, i), 7, Motif.HANGING_PIECE));
            IntStream.rangeClosed(4, 7).forEach(i -> doneRapid(player, i));

            JsonNode report = get("/api/patterns");
            assertThat(report.get("sampleSize").asInt()).isEqualTo(7);
            assertThat(pattern(report, Motif.HANGING_PIECE).get("pattern").asBoolean()).isTrue();
        }

        @Test
        void onlyTheTwentyNewestEligibleGamesCount() throws Exception {
            // 21 eligible games; findings in the 2 newest and in the 21st newest, which is outside the window.
            List<Game> newestFirst = new ArrayList<>();
            IntStream.rangeClosed(1, 21).forEach(i -> newestFirst.add(doneRapid(player, i)));
            finding(newestFirst.get(0), 7, Motif.HANGING_PIECE);
            finding(newestFirst.get(1), 7, Motif.HANGING_PIECE);
            finding(newestFirst.get(20), 7, Motif.HANGING_PIECE);

            JsonNode report = get("/api/patterns");
            assertThat(report.get("sampleSize").asInt()).isEqualTo(20);
            JsonNode hanging = pattern(report, Motif.HANGING_PIECE);
            assertThat(hanging.get("gamesWithFinding").asInt()).isEqualTo(2);
            assertThat(hanging.get("pattern").asBoolean()).isFalse();
        }

        @Test
        void ineligibleGamesAndOtherPlayersAreIgnored() throws Exception {
            finding(game(player, 1, Speed.BLITZ, AnalysisStatus.DONE, PieceColor.WHITE), 7, Motif.HANGING_PIECE);
            finding(game(player, 2, Speed.RAPID, AnalysisStatus.PENDING, PieceColor.WHITE), 7, Motif.HANGING_PIECE);
            finding(game(player, 3, Speed.RAPID, AnalysisStatus.FAILED, PieceColor.WHITE), 7, Motif.HANGING_PIECE);
            IntStream.rangeClosed(1, 3).forEach(i -> finding(doneRapid(other, i), 7, Motif.HANGING_PIECE));
            finding(doneRapid(player, 4), 7, Motif.HANGING_PIECE);

            JsonNode report = get("/api/patterns");
            assertThat(report.get("sampleSize").asInt()).isEqualTo(1);
            assertThat(pattern(report, Motif.HANGING_PIECE).get("gamesWithFinding").asInt()).isEqualTo(1);
        }

        @Test
        void everyMotifWithADetectorIsListedEvenWithoutFindings() throws Exception {
            doneRapid(player, 1);

            JsonNode report = get("/api/patterns");
            JsonNode hanging = pattern(report, Motif.HANGING_PIECE);
            assertThat(hanging.get("gamesWithFinding").asInt()).isZero();
            assertThat(hanging.get("sampleSize").asInt()).isEqualTo(1);
            assertThat(hanging.get("pattern").asBoolean()).isFalse();
        }

        @Test
        void motifsWithoutADetectorAreLeftOutEvenWithFindingsInTheData() throws Exception {
            // No knight-fork detector exists yet (#26), so "0 of 20" would read as "you never allow forks".
            IntStream.rangeClosed(1, 3).forEach(i -> finding(doneRapid(player, i), 7, Motif.ALLOWED_KNIGHT_FORK));

            JsonNode report = get("/api/patterns");
            List<String> listed = new ArrayList<>();
            report.get("motifs").forEach(m -> listed.add(m.get("motif").asString()));
            assertThat(listed).containsExactly("HANGING_PIECE");
            assertThat(Motif.ALLOWED_KNIGHT_FORK.hasDetector()).isFalse();
            assertThat(Motif.HANGING_PIECE.hasDetector()).isTrue();
        }

        @Test
        void withoutThePlayersDataTheReportIsEmptyNotAnError() throws Exception {
            jdbc.execute("TRUNCATE users RESTART IDENTITY CASCADE");

            JsonNode report = get("/api/patterns");
            assertThat(report.get("sampleSize").asInt()).isZero();
            assertThat(get("/api/findings").get("totalItems").asInt()).isZero();
        }
    }

    // ---- GET /api/findings ----

    @Nested
    class Findings {

        @Test
        void newestGameFirstWithPagingAndOnlyThePlayersFindings() throws Exception {
            MistakeFinding oldest = finding(doneRapid(player, 30), 7, Motif.HANGING_PIECE);
            MistakeFinding middle = finding(doneRapid(player, 20), 7, Motif.HANGING_PIECE);
            MistakeFinding newest = finding(doneRapid(player, 10), 7, Motif.HANGING_PIECE);
            finding(doneRapid(other, 1), 7, Motif.HANGING_PIECE);

            JsonNode first = get("/api/findings?size=2");
            assertThat(first.get("totalItems").asInt()).isEqualTo(3);
            assertThat(first.get("totalPages").asInt()).isEqualTo(2);
            assertThat(ids(first)).containsExactly(newest.getId(), middle.getId());
            assertThat(ids(get("/api/findings?size=2&page=1"))).containsExactly(oldest.getId());
        }

        @Test
        void filtersByMotif() throws Exception {
            Game game = doneRapid(player, 1);
            finding(game, 7, Motif.HANGING_PIECE);
            MistakeFinding fork = finding(game, 9, Motif.ALLOWED_KNIGHT_FORK);

            JsonNode page = get("/api/findings?motif=ALLOWED_KNIGHT_FORK");
            assertThat(ids(page)).containsExactly(fork.getId());
        }

        @Test
        void aSummaryCarriesTheMoveTheFactsAndTheGame() throws Exception {
            finding(game(player, 1, Speed.RAPID, AnalysisStatus.DONE, PieceColor.BLACK), 8, Motif.HANGING_PIECE);

            JsonNode item = get("/api/findings").get("items").get(0);
            assertThat(item.get("moveNumber").asInt()).isEqualTo(4);
            assertThat(item.get("side").asString()).isEqualTo("BLACK");
            assertThat(item.get("played").get("uci").asString()).isEqualTo("f3e5");
            assertThat(item.get("played").get("san").asString()).isEqualTo("Nxe5");
            assertThat(item.get("facts").get("piece").asString()).isEqualTo("N");
            assertThat(item.get("facts").get("materialLost").asInt()).isEqualTo(3);
            assertThat(item.get("game").get("lichessUrl").asString()).isEqualTo("https://lichess.org/g1/black");
        }

        @ParameterizedTest
        @CsvSource({ "size=51, size", "size=0, size", "page=-1, page" })
        void boundsOnPagingAre400(String query, String parameter) throws Exception {
            JsonNode body = problem(request("GET", "/api/findings?" + query), 400);
            assertThat(body.get("detail").asString()).startsWith(parameter + " ");
        }

        @ParameterizedTest
        @ValueSource(strings = { "motif=FORK", "page=one", "size=1e9" })
        void unreadableParametersAre400(String query) throws Exception {
            JsonNode body = problem(request("GET", "/api/findings?" + query), 400);
            assertThat(body.get("detail").asString()).startsWith("Invalid value for");
        }

        @Test
        void anUnknownMotifNamesTheAllowedValues() throws Exception {
            JsonNode body = problem(request("GET", "/api/findings?motif=FORK"), 400);
            assertThat(body.get("detail").asString()).contains("ALLOWED_KNIGHT_FORK", "HANGING_PIECE");
        }

        @Test
        void aPageCostsTheSameNumberOfQueriesWhateverItsSize() throws Exception {
            finding(doneRapid(player, 1), 7, Motif.HANGING_PIECE);
            long withOne = statementsFor("/api/findings?size=50");

            IntStream.rangeClosed(2, 11).forEach(i -> finding(doneRapid(player, i), 7, Motif.HANGING_PIECE));
            long withEleven = statementsFor("/api/findings?size=50");

            // The user lookup, the page with its moves and games (fetch join), and the count.
            assertThat(withEleven).isEqualTo(withOne).isLessThanOrEqualTo(3);
        }

        private long statementsFor(String path) throws Exception {
            Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
            statistics.clear();
            get(path);
            return statistics.getPrepareStatementCount();
        }

        private List<Long> ids(JsonNode page) {
            List<Long> ids = new ArrayList<>();
            page.get("items").forEach(item -> ids.add(item.get("id").asLong()));
            return ids;
        }
    }

    // ---- GET /api/findings/{id} ----

    @Nested
    class Detail {

        @Test
        void hasEverythingTheBoardNeeds() throws Exception {
            MistakeFinding finding = finding(doneRapid(player, 1), 7, Motif.HANGING_PIECE);

            JsonNode detail = get("/api/findings/" + finding.getId());
            assertThat(detail.get("fenBefore").asString()).isEqualTo(TestData.FORK_FEN);
            assertThat(detail.get("played").get("uci").asString()).isEqualTo("f3e5");
            assertThat(detail.get("played").get("san").asString()).isEqualTo("Nxe5");
            assertThat(detail.get("best").get("uci").asString()).isEqualTo("f3d4");
            assertThat(detail.get("best").get("san").isNull()).isTrue();
            assertThat(detail.get("evidenceLineUci")).extracting(JsonNode::asString)
                    .containsExactly("d8g5", "e5f7", "g5g2");
            assertThat(detail.get("facts").get("movedFrom").asString()).isEqualTo("f3");
            assertThat(detail.get("classification").asString()).isEqualTo("BLUNDER");
            assertThat(detail.get("detectorVersion").asString()).isEqualTo("hanging-0.4");
            assertThat(detail.get("engine").get("hashMb").asInt()).isEqualTo(64);
            JsonNode game = detail.get("game");
            assertThat(game.get("lichessId").asString()).isEqualTo("g1");
            assertThat(game.get("lichessUrl").asString()).isEqualTo("https://lichess.org/g1");
            assertThat(game.get("opponentName").asString()).isEqualTo("rival");
            assertThat(game.get("userColor").asString()).isEqualTo("WHITE");
            assertThat(game.get("playedAt").asString()).isEqualTo("2026-10-01T11:59:00Z");
            assertThat(detail.get("explanation").isNull()).isTrue();
        }

        @Test
        void showsOnlyATemplateExplanationThatPassed() throws Exception {
            MistakeFinding finding = finding(doneRapid(player, 1), 7, Motif.HANGING_PIECE);
            tx.executeWithoutResult(s -> {
                Explanation llm = Explanation.llm(finding, Explanation.Mode.LLM_FACTS, "model", "p1", FACTS,
                        "LLM text that passed");
                llm.recordVerification(0, "ok");
                explanations.save(llm);
                Explanation failed = Explanation.template(finding, FACTS, "Template that failed");
                failed.recordVerification(1, "unsupported claim");
                explanations.save(failed);
            });
            assertThat(get("/api/findings/" + finding.getId()).get("explanation").isNull()).isTrue();

            tx.executeWithoutResult(s -> {
                Explanation passed = Explanation.template(finding, FACTS, "Your knight on e5 was left undefended.");
                passed.recordVerification(0, "ok");
                explanations.save(passed);
            });
            assertThat(get("/api/findings/" + finding.getId()).get("explanation").get("content").asString())
                    .isEqualTo("Your knight on e5 was left undefended.");
        }

        @Test
        void unknownAndOtherPlayersFindingsAre404() throws Exception {
            MistakeFinding foreign = finding(doneRapid(other, 1), 7, Motif.HANGING_PIECE);

            assertThat(problem(request("GET", "/api/findings/999999"), 404).get("detail").asString())
                    .isEqualTo("No finding with id 999999");
            problem(request("GET", "/api/findings/" + foreign.getId()), 404);
            problem(request("GET", "/api/findings/abc"), 400);
        }

        @Test
        void anUnexpectedErrorIsAGeneric500WithoutInternals() throws Exception {
            MistakeFinding finding = finding(doneRapid(player, 1), 7, Motif.HANGING_PIECE);
            // Facts that are valid JSON but not an object: reading them fails inside the service.
            jdbc.update("UPDATE mistake_findings SET facts = '\"not an object\"'::jsonb WHERE id = ?", finding.getId());

            JsonNode body = problem(request("GET", "/api/findings/" + finding.getId()), 500);
            assertThat(body.get("detail").asString()).isEqualTo("The request could not be processed.");
            assertThat(body.toString()).doesNotContain("not an object", "Jackson", "Mismatched");
        }
    }

    // ---- GET /api/games/{id}/moves ----

    @Nested
    class Moves {

        @Test
        void listsEveryHalfMoveWithItsClassification() throws Exception {
            Game game = doneRapid(player, 1);
            finding(game, 7, Motif.HANGING_PIECE);
            tx.executeWithoutResult(s -> evaluations.save(TestData.opponentBestMove(game, 8)));

            JsonNode moves = get("/api/games/" + game.getId() + "/moves");
            assertThat(moves.get("analysisStatus").asString()).isEqualTo("DONE");
            assertThat(moves.get("moves")).hasSize(2);
            JsonNode first = moves.get("moves").get(0);
            assertThat(first.get("ply").asInt()).isEqualTo(7);
            assertThat(first.get("side").asString()).isEqualTo("WHITE");
            assertThat(first.get("byUser").asBoolean()).isTrue();
            assertThat(first.get("classification").asString()).isEqualTo("BLUNDER");
            assertThat(moves.get("moves").get(1).get("played").get("san").asString()).isEqualTo("Qg5");
        }

        @Test
        void anUnanalysedGameHasNoMovesYet() throws Exception {
            Game pending = game(player, 1, Speed.RAPID, AnalysisStatus.PENDING, PieceColor.WHITE);

            JsonNode moves = get("/api/games/" + pending.getId() + "/moves");
            assertThat(moves.get("analysisStatus").asString()).isEqualTo("PENDING");
            assertThat(moves.get("moves")).isEmpty();
        }

        @Test
        void unknownAndOtherPlayersGamesAre404() throws Exception {
            Game foreign = doneRapid(other, 1);
            problem(request("GET", "/api/games/999999/moves"), 404);
            problem(request("GET", "/api/games/" + foreign.getId() + "/moves"), 404);
        }
    }

    // ---- read-only, no CORS, OpenAPI ----

    @Nested
    class Boundaries {

        @ParameterizedTest
        @CsvSource({ "POST, /api/findings", "PUT, /api/findings/1", "DELETE, /api/findings/1",
                "PATCH, /api/findings/1", "POST, /api/patterns", "DELETE, /api/patterns",
                "PUT, /api/games/1/moves", "POST, /api/games/1/moves" })
        void writesAre405(String method, String path) throws Exception {
            problem(request(method, path), 405);
        }

        @Test
        void aCrossOriginPreflightIsRefused() throws Exception {
            HttpRequest preflight = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/findings"))
                    .method("OPTIONS", BodyPublishers.noBody())
                    .header("Origin", "https://evil.example")
                    .header("Access-Control-Request-Method", "GET").build();
            HttpResponse<String> response = http.send(preflight, BodyHandlers.ofString());
            // No CORS configuration: whatever the status, the answer grants nothing, so browsers refuse
            // the cross-origin call. The frontend calls /api on its own origin through nginx.
            assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
            assertThat(response.headers().firstValue("Access-Control-Allow-Methods")).isEmpty();
        }

        @Test
        void aCrossOriginGetGetsNoCorsHeaders() throws Exception {
            HttpRequest get = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/patterns"))
                    .header("Origin", "https://evil.example").build();
            HttpResponse<String> response = http.send(get, BodyHandlers.ofString());
            assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        }

        @Test
        void unknownApiPathsAre404ProblemDetails() throws Exception {
            JsonNode body = problem(request("GET", "/api/proxy-check"), 404);
            assertThat(body.get("instance").asString()).isEqualTo("/api/proxy-check");
        }

        /**
         * The read endpoints stay GET only, and the only writes are the journey's two POSTs (#90), both
         * JSON only. The list of paths is exact, so a new endpoint, read or write, has to be added here
         * on purpose.
         */
        @Test
        void openApiDescribesTheReadEndpointsAsReadOnlyAndOnlyTheJourneyWrites() throws Exception {
            String lesson = "/api/lessons/{unit}/{number}";
            String answer = "/api/lessons/{unit}/{number}/items/{position}/answer";
            List<String> reads = List.of("/api/findings", "/api/findings/{id}", "/api/patterns",
                    "/api/games/{id}/moves", "/api/journey");
            JsonNode paths = get("/api/docs").get("paths");

            List<String> all = new ArrayList<>(reads);
            all.addAll(List.of(lesson, answer));
            assertThat(paths.propertyNames()).containsExactlyInAnyOrderElementsOf(all);
            reads.forEach(path -> assertThat(paths.get(path).propertyNames()).as(path).containsOnly("get"));
            assertThat(paths.get(lesson).propertyNames()).as(lesson).containsExactlyInAnyOrder("get", "post");
            assertThat(paths.get(answer).propertyNames()).as(answer).containsOnly("post");
            assertThat(paths.get(answer).get("post").get("requestBody").get("content").propertyNames())
                    .containsOnly("application/json");
        }
    }
}
