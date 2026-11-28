package io.github.gergo019.blindspot.training.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import javax.sql.DataSource;

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
import io.github.gergo019.blindspot.analysis.MistakeFinding;
import io.github.gergo019.blindspot.analysis.MistakeFindingRepository;
import io.github.gergo019.blindspot.analysis.Motif;
import io.github.gergo019.blindspot.analysis.MoveEvaluationRepository;
import io.github.gergo019.blindspot.game.Game;
import io.github.gergo019.blindspot.game.GameRepository;
import io.github.gergo019.blindspot.training.Curriculum;
import io.github.gergo019.blindspot.training.Drill;
import io.github.gergo019.blindspot.training.DrillRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The journey API (#90) over real HTTP against PostgreSQL: the map, starting a lesson and its retry,
 * answers, what an unanswered puzzle must not reveal, every error as a problem detail without internals,
 * JSON-only POSTs without CORS, and the two races of the #89 report, provoked deterministically with a
 * second database connection that holds the competing change until the request waits for it.
 *
 * <p>Every position here is a prepared drill with {@link TestData#forkJudgement()}'s verdicts: Nxd4 (f3d4)
 * and Nc3 (b1c3) are accepted, d3 and the game's Nxe5 (f3e5) are not.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "blindspot.lichess.importer.enabled=false", "blindspot.analysis.job.enabled=false",
        "blindspot.training.drill-preparation.enabled=false", "blindspot.lichess.username=Player1" })
@Testcontainers
class JourneyApiIT {

    private static final String LESSON_1 = "/api/lessons/hanging-piece/1";
    private static final String RIGHT = "f3d4";
    private static final String WRONG = "f3e5";
    private static final String FACTS = """
            {"piece":"N","square":"e5","capturedBy":"q","materialLost":3,"movedPiece":"N","movedFrom":"f3","movedTo":"e5"}""";
    private static final String JSON = "application/json";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired TransactionTemplate tx;
    @Autowired UserRepository users;
    @Autowired ChessAccountRepository accounts;
    @Autowired GameRepository games;
    @Autowired MoveEvaluationRepository evaluations;
    @Autowired MistakeFindingRepository findings;
    @Autowired DrillRepository drills;
    @Autowired JsonMapper json;

    private final HttpClient http = HttpClient.newHttpClient();
    private User user;
    private int gameCounter;

    @BeforeEach
    void onlyTheConfiguredPlayer() {
        jdbc.execute("TRUNCATE users CASCADE");
        // The configured player is "Player1", lower-cased like the account the Lichess setup creates.
        user = tx.execute(s -> {
            User player = users.save(new User("player1"));
            accounts.save(TestData.lichessAccount(player));
            return player;
        });
        gameCounter = 0;
    }

    // ---- fixtures ----

    /**
     * {@code count} positions, each from its own analysed rapid game (as the demo's 5 hanging-piece
     * findings), with a prepared drill. Returns the drill ids.
     */
    private List<Long> positions(int count) {
        return tx.execute(s -> {
            ChessAccount account = accounts.findByUserId(user.getId()).get(0);
            List<Long> ids = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                Game game = TestData.game(account, "g" + ++gameCounter);
                game.startAnalysis(Instant.parse("2026-11-01T18:30:00Z"));
                game.completeAnalysis();
                game = games.save(game);
                MistakeFinding finding = findings.save(new MistakeFinding(
                        evaluations.save(TestData.userBlunder(game, 7)), Motif.HANGING_PIECE, "d8g5 e5f7 g5g2",
                        "hanging-0.4", FACTS));
                ids.add(drills.save(Drill.fromFinding(user, finding, TestData.forkJudgement(), Instant.now())).getId());
            }
            return ids;
        });
    }

    /** As a re-analysis does: the finding goes, and with it its drill, lesson item and attempts. */
    private void reanalysisDeletes(int position) {
        jdbc.update("DELETE FROM mistake_findings WHERE id = (SELECT d.finding_id FROM drills d "
                + "JOIN lesson_items i ON i.drill_id = d.id WHERE i.position = ?)", position);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    // ---- HTTP helpers ----

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, BodyHandlers.ofString());
    }

    private HttpResponse<String> send(String method, String path, String contentType, String body) throws Exception {
        HttpRequest.Builder builder = request(path).method(method,
                body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body));
        if (contentType != null) {
            builder.header("Content-Type", contentType);
        }
        return send(builder.build());
    }

    private JsonNode ok(HttpResponse<String> response) {
        assertThat(response.statusCode()).as("%s: %s", response.request().uri(), response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private JsonNode get(String path) throws Exception {
        return ok(send("GET", path, null, null));
    }

    /** Starts a lesson as the web app will: a JSON POST without a body. */
    private JsonNode start(String path) throws Exception {
        return ok(send("POST", path, JSON, null));
    }

    private HttpResponse<String> answer(int position, String move) throws Exception {
        return send("POST", LESSON_1 + "/items/" + position + "/answer", JSON, "{\"moveUci\":\"" + move + "\"}");
    }

    private JsonNode problem(HttpResponse<String> response, int status) {
        return ApiProblems.assertProblem(json, response, status);
    }

    private static List<Integer> positionsOf(JsonNode lesson) {
        List<Integer> positions = new ArrayList<>();
        lesson.get("items").forEach(item -> positions.add(item.get("position").asInt()));
        return positions;
    }

    private static List<Integer> currentCheckOf(JsonNode lesson) {
        List<Integer> positions = new ArrayList<>();
        lesson.get("items").forEach(item -> {
            if (item.get("currentCheck").asBoolean()) {
                positions.add(item.get("position").asInt());
            }
        });
        return positions;
    }

    private static JsonNode item(JsonNode lesson, int position) {
        for (JsonNode item : lesson.get("items")) {
            if (item.get("position").asInt() == position) {
                return item;
            }
        }
        throw new AssertionError("No item at " + position + " in " + lesson);
    }

    // ---- the map ----

    @Nested
    class Map {

        @Test
        void withTheDemoDataOnlyLessonOneFillsAndTheOthersSayWhatTheyNeed() throws Exception {
            positions(5);   // the demo: 5 hanging-piece findings in 20 games, 5 drills

            JsonNode journey = get("/api/journey");

            JsonNode unit = journey.get("unit");
            assertThat(unit.get("key").asString()).isEqualTo("hanging-piece");
            assertThat(unit.get("motif").asString()).isEqualTo("HANGING_PIECE");
            // The motif's line, as GET /api/patterns gives it: "in 5 of your last 5 rapid games".
            assertThat(unit.get("report")).isEqualTo(get("/api/patterns").get("motifs").get(0));
            assertThat(unit.get("report").get("gamesWithFinding").asInt()).isEqualTo(5);
            assertThat(unit.get("report").get("sampleSize").asInt()).isEqualTo(5);

            JsonNode lessons = journey.get("lessons");
            assertThat(lessons).hasSize(5);
            JsonNode first = lessons.get(0);
            assertThat(first.get("key").asString()).isEqualTo("hanging-piece/1");
            assertThat(first.get("unit").asString()).isEqualTo("hanging-piece");
            assertThat(first.get("number").asInt()).isEqualTo(1);
            assertThat(first.get("state").asString()).isEqualTo("AVAILABLE");
            assertThat(first.get("recommended").asBoolean()).isTrue();
            assertThat(first.get("size").asInt()).isEqualTo(5);
            assertThat(first.get("solvedFirstTry").asInt()).isZero();
            assertThat(first.get("retryAvailable").asBoolean()).isFalse();
            for (int i = 1; i < 5; i++) {
                JsonNode later = lessons.get(i);
                assertThat(later.get("number").asInt()).isEqualTo(i + 1);
                assertThat(later.get("state").asString()).isEqualTo("NEEDS_POSITIONS");
                assertThat(later.get("positionsNeeded").asInt()).isEqualTo(5);
                assertThat(later.get("recommended").asBoolean()).isFalse();
            }
        }

        @Test
        void anUnassignedLessonCanBeReadWithoutItems() throws Exception {
            positions(5);

            JsonNode lesson = get(LESSON_1);

            assertThat(lesson.get("lesson").get("state").asString()).isEqualTo("AVAILABLE");
            assertThat(lesson.get("explanation").asString()).isEqualTo(Curriculum.Unit.HANGING_PIECE.explanation());
            assertThat(lesson.get("items")).isEmpty();
            assertThat(count("lesson_assignments")).isZero();   // reading changes nothing
        }
    }

    // ---- starting ----

    @Nested
    class Starting {

        @Test
        void startingAssignsTheLessonAndIsSafeToRepeat() throws Exception {
            positions(5);

            JsonNode started = start(LESSON_1);
            JsonNode again = start(LESSON_1);

            assertThat(started.get("lesson").get("state").asString()).isEqualTo("IN_PROGRESS");
            assertThat(positionsOf(started)).containsExactly(1, 2, 3, 4, 5);
            assertThat(started.get("items")).extracting(i -> i.get("role").asString())
                    .containsExactly("PRACTICE", "PRACTICE", "PRACTICE", "CHECK", "CHECK");
            assertThat(currentCheckOf(started)).containsExactly(4, 5);
            JsonNode first = started.get("items").get(0);
            assertThat(first.get("task").asString()).isEqualTo("FIND_SAFE_MOVE");
            assertThat(first.get("prompt").asString()).isEqualTo("Find a safe move");
            assertThat(first.get("fen").asString()).isEqualTo(TestData.FORK_FEN);
            assertThat(first.get("orientation").asString()).isEqualTo("WHITE");
            assertThat(again).isEqualTo(started);
            assertThat(count("lesson_assignments")).isEqualTo(1);
            assertThat(count("lesson_items")).isEqualTo(5);
        }

        @Test
        void withTooFewPositionsStartingChangesNothingAndSaysWhatIsNeeded() throws Exception {
            positions(4);

            JsonNode lesson = start(LESSON_1);

            assertThat(lesson.get("lesson").get("state").asString()).isEqualTo("NEEDS_POSITIONS");
            assertThat(lesson.get("lesson").get("positionsNeeded").asInt()).isEqualTo(1);
            assertThat(lesson.get("items")).isEmpty();
            assertThat(count("lesson_assignments")).isZero();
        }

        @Test
        void anUnansweredPuzzleRevealsNothingAboutTheGameOrTheSolution() throws Exception {
            positions(5);
            start(LESSON_1);

            JsonNode lesson = get(LESSON_1);

            for (JsonNode item : lesson.get("items")) {
                assertThat(item.get("answered").asBoolean()).isFalse();
                assertThat(item.get("solution").isNull()).as(item.toString()).isTrue();
                // No move of the position, no game: the engine's move, the other accepted move, the game's
                // move and its evidence line, the game's Lichess id, and not even the finding (#100).
                assertThat(item.toString()).doesNotContain("f3d4", "b1c3", "f3e5", "d8g5", "lichess", "\"g1\"",
                        "acceptedMoves", "bestMove", "gameMove", "evidenceLine", "findingId", "finding");
            }
        }
    }

    // ---- answering ----

    @Nested
    class Answering {

        @BeforeEach
        void lessonOneStarted() throws Exception {
            positions(5);
            start(LESSON_1);
        }

        @Test
        void aCorrectAnswerComesWithTheSolutionAndOnlyThatItemRevealsIt() throws Exception {
            JsonNode answer = ok(answer(1, RIGHT));

            assertThat(answer.get("position").asInt()).isEqualTo(1);
            assertThat(answer.get("correct").asBoolean()).isTrue();
            assertThat(answer.get("firstTry").asBoolean()).isTrue();
            assertThat(answer.get("verdict").get("moveUci").asString()).isEqualTo(RIGHT);
            assertThat(answer.get("verdict").get("san").asString()).isEqualTo("Nxd4");
            assertThat(answer.get("verdict").get("drop").decimalValue()).isZero();
            assertThat(answer.get("bestMove").asString()).isEqualTo("f3d4");
            assertThat(answer.get("acceptedMoves")).extracting(JsonNode::asString).containsExactly("b1c3", "f3d4");
            assertThat(answer.get("gameMove").get("uci").asString()).isEqualTo(WRONG);
            assertThat(answer.get("gameMove").get("san").asString()).isEqualTo("Nxe5");
            assertThat(answer.get("evidenceLineUci")).extracting(JsonNode::asString)
                    .containsExactly("d8g5", "e5f7", "g5g2");
            assertThat(answer.get("lesson").get("solvedFirstTry").asInt()).isEqualTo(1);
            assertThat(answer.get("lesson").get("state").asString()).isEqualTo("IN_PROGRESS");

            JsonNode lesson = get(LESSON_1);
            JsonNode solution = item(lesson, 1).get("solution");
            assertThat(item(lesson, 1).get("solvedFirstTry").asBoolean()).isTrue();
            assertThat(solution.get("bestMove").asString()).isEqualTo("f3d4");
            assertThat(solution.get("acceptedMoves")).hasSize(2);
            assertThat(solution.get("gameMove").get("uci").asString()).isEqualTo(WRONG);
            assertThat(solution.get("game").get("lichessUrl").asString()).startsWith("https://lichess.org/g");
            assertThat(item(lesson, 2).get("solution").isNull()).isTrue();
        }

        @Test
        void anAnsweredPuzzleNamesTheFindingItCameFromAndAnUnansweredOneDoesNot() throws Exception {
            long finding = jdbc.queryForObject("SELECT d.finding_id FROM drills d JOIN lesson_items i "
                    + "ON i.drill_id = d.id WHERE i.position = 1", Long.class);
            ok(answer(1, WRONG));

            JsonNode lesson = get(LESSON_1);

            assertThat(item(lesson, 1).get("solution").get("findingId").asLong()).isEqualTo(finding);
            // The id is the one the findings API knows: the link from the result opens that finding (#100).
            assertThat(get("/api/findings/" + finding).get("id").asLong()).isEqualTo(finding);
            for (int position = 2; position <= 5; position++) {
                JsonNode open = item(lesson, position);
                assertThat(open.get("solution").isNull()).isTrue();
                assertThat(open.toString()).doesNotContain("findingId");
            }
        }

        @Test
        void aWrongAnswerComesWithTheMovesVerdictAndRefutation() throws Exception {
            JsonNode answer = ok(answer(2, WRONG));

            assertThat(answer.get("correct").asBoolean()).isFalse();
            assertThat(answer.get("firstTry").asBoolean()).isTrue();
            assertThat(answer.get("verdict").get("drop").decimalValue()).isEqualByComparingTo("0.6");
            assertThat(answer.get("verdict").get("refutationUci").asString()).isEqualTo("d8g5 e5f7 g5g2");
            assertThat(answer.get("lesson").get("solvedFirstTry").asInt()).isZero();

            JsonNode again = ok(answer(2, RIGHT));   // a practice puzzle can be tried again
            assertThat(again.get("correct").asBoolean()).isTrue();
            assertThat(again.get("firstTry").asBoolean()).isFalse();
            assertThat(again.get("lesson").get("solvedFirstTry").asInt()).isZero();
            assertThat(count("drill_attempts")).isEqualTo(2);
        }

        @Test
        void bothCheckPuzzlesRightFirstTryMasterTheLesson() throws Exception {
            ok(answer(4, RIGHT));
            JsonNode last = ok(answer(5, RIGHT));

            assertThat(last.get("lesson").get("state").asString()).isEqualTo("MASTERED");
            assertThat(get(LESSON_1).get("masteredAt").isNull()).isFalse();
            assertThat(get("/api/journey").get("lessons").get(0).get("recommended").asBoolean()).isFalse();
        }

        @Test
        void aSecondAnswerToACheckPuzzleIs409() throws Exception {
            ok(answer(4, WRONG));

            JsonNode body = problem(answer(4, RIGHT), 409);

            assertThat(body.get("detail").asString()).isEqualTo("This check puzzle has already been answered.");
            assertThat(count("drill_attempts")).isEqualTo(1);
        }

        @Test
        void aHalfAnsweredCheckCannotBeRetriedYet() throws Exception {
            positions(2);   // unused positions exist, so only the half-answered check holds the retry back
            ok(answer(4, WRONG));

            JsonNode lesson = start(LESSON_1);

            assertThat(positionsOf(lesson)).containsExactly(1, 2, 3, 4, 5);
            assertThat(lesson.get("lesson").get("retryAvailable").asBoolean()).isFalse();
            assertThat(lesson.get("lesson").get("positionsNeeded").asInt()).isZero();
            assertThat(lesson.get("lesson").get("state").asString()).isEqualTo("IN_PROGRESS");

            JsonNode failed = ok(answer(5, RIGHT));   // the second check puzzle can still be answered
            assertThat(failed.get("lesson").get("retryAvailable").asBoolean()).isTrue();
        }

        @Test
        void aFailedCheckIsRetriedOnTwoNewPositions() throws Exception {
            ok(answer(4, WRONG));
            ok(answer(5, RIGHT));
            JsonNode failed = get("/api/journey").get("lessons").get(0);
            assertThat(failed.get("state").asString()).isEqualTo("IN_PROGRESS");
            assertThat(failed.get("retryAvailable").asBoolean()).isFalse();
            assertThat(failed.get("positionsNeeded").asInt()).isEqualTo(2);
            assertThat(positionsOf(start(LESSON_1))).hasSize(5);   // nothing to retry on yet: unchanged

            positions(2);
            assertThat(get("/api/journey").get("lessons").get(0).get("retryAvailable").asBoolean()).isTrue();
            JsonNode retried = start(LESSON_1);

            assertThat(positionsOf(retried)).containsExactly(1, 2, 3, 4, 5, 7, 8);   // a gap, never 1 to n
            assertThat(currentCheckOf(retried)).containsExactly(7, 8);
            assertThat(retried.get("lesson").get("retryAvailable").asBoolean()).isFalse();
            ok(answer(7, RIGHT));
            assertThat(ok(answer(8, RIGHT)).get("lesson").get("state").asString()).isEqualTo("MASTERED");
        }

        @Test
        void aReanalysisThatDeletesItemsLeavesGapsAndVoidsTheCheck() throws Exception {
            reanalysisDeletes(2);   // a practice puzzle
            reanalysisDeletes(5);   // a check puzzle

            JsonNode lesson = get(LESSON_1);
            assertThat(positionsOf(lesson)).containsExactly(1, 3, 4);
            assertThat(currentCheckOf(lesson)).containsExactly(4);
            assertThat(lesson.get("lesson").get("state").asString()).isEqualTo("IN_PROGRESS");
            assertThat(lesson.get("lesson").get("positionsNeeded").asInt()).isEqualTo(2);
            assertThat(problem(answer(5, RIGHT), 404).get("detail").asString()).isEqualTo("No such lesson item");
            ok(answer(4, RIGHT));   // the item that is left cannot pass the check
            assertThat(get(LESSON_1).get("lesson").get("state").asString()).isEqualTo("IN_PROGRESS");

            positions(2);
            JsonNode retried = start(LESSON_1);
            assertThat(positionsOf(retried)).containsExactly(1, 3, 4, 6, 7);
            assertThat(currentCheckOf(retried)).containsExactly(6, 7);
        }

        @Test
        void aPassedLessonStaysPassedWhenAReanalysisDeletesItsItems() throws Exception {
            ok(answer(4, RIGHT));
            ok(answer(5, RIGHT));
            for (int position = 1; position <= 5; position++) {
                reanalysisDeletes(position);
            }

            JsonNode lesson = get(LESSON_1);
            assertThat(lesson.get("lesson").get("state").asString()).isEqualTo("MASTERED");
            assertThat(lesson.get("items")).isEmpty();
        }
    }

    // ---- errors ----

    @Nested
    class Errors {

        @ParameterizedTest
        @ValueSource(strings = { "/api/lessons/hanging-piece/6", "/api/lessons/hanging-piece/0",
                "/api/lessons/hanging-piece/01", "/api/lessons/hanging-piece/-1", "/api/lessons/hanging-piece/abc",
                "/api/lessons/hanging-piece/99999999999", "/api/lessons/knight-fork/1",
                "/api/lessons/Hanging-Piece/1" })
        void anUnknownLessonIsAFixed404(String path) throws Exception {
            for (HttpResponse<String> response : List.of(send("GET", path, null, null),
                    send("POST", path, JSON, null), send("POST", path + "/items/1/answer", JSON, "{\"moveUci\":\"f3d4\"}"))) {
                JsonNode body = problem(response, 404);
                assertThat(body.get("detail").asString()).isEqualTo("No such lesson");
                String[] parts = path.split("/");
                // Nothing from the URL in the text (instance is the request path, as on every error).
                assertThat(body.get("detail").asString() + body.get("title").asString())
                        .doesNotContain(parts[3], parts[4]);
            }
        }

        @Test
        void answeringALessonThatIsNotStartedOrAnUnknownPositionIs404() throws Exception {
            positions(5);
            assertThat(problem(answer(1, RIGHT), 404).get("detail").asString()).isEqualTo("No such lesson item");

            start(LESSON_1);
            assertThat(problem(answer(6, RIGHT), 404).get("detail").asString()).isEqualTo("No such lesson item");
            assertThat(problem(answer(0, RIGHT), 404).get("detail").asString()).isEqualTo("No such lesson item");
            assertThat(problem(send("POST", LESSON_1 + "/items/x/answer", JSON, "{\"moveUci\":\"f3d4\"}"), 400)
                    .get("detail").asString()).isEqualTo("Invalid value for 'position'.");
        }

        @Test
        void anIllegalMoveIs400AndNothingIsRecorded() throws Exception {
            positions(5);
            start(LESSON_1);

            JsonNode body = problem(answer(4, "e2e5"), 400);

            assertThat(body.get("detail").asString()).isEqualTo("Not a legal move in this position.");
            assertThat(body.toString()).doesNotContain("e2e5", TestData.FORK_FEN);
            assertThat(count("drill_attempts")).isZero();
            ok(answer(4, RIGHT));   // the check puzzle is still open
        }

        @ParameterizedTest
        @ValueSource(strings = { "{}", "{\"moveUci\":\"\"}", "{\"moveUci\":\"   \"}", "{\"moveUci\":\"f3d4f3d4f3d4\"}",
                "{\"moveUci\":", "not json", "" })
        void aMissingOrUnreadableBodyIs400(String body) throws Exception {
            positions(5);
            start(LESSON_1);

            problem(send("POST", LESSON_1 + "/items/1/answer", JSON, body), 400);

            assertThat(count("drill_attempts")).isZero();
        }

        @Test
        void anUnexpectedErrorIsAGeneric500() throws Exception {
            positions(5);
            start(LESSON_1);
            jdbc.execute("ALTER TABLE drill_attempts RENAME TO drill_attempts_away");
            try {
                JsonNode body = problem(answer(1, RIGHT), 500);
                assertThat(body.get("detail").asString()).isEqualTo("The request could not be processed.");
                assertThat(body.toString()).doesNotContain("drill_attempts", "SQL", "relation");
            } finally {
                jdbc.execute("ALTER TABLE drill_attempts_away RENAME TO drill_attempts");
            }
        }
    }

    // ---- only JSON, no CORS, no other methods ----

    @Nested
    class Boundaries {

        @ParameterizedTest
        @CsvSource({ "application/x-www-form-urlencoded, moveUci=f3d4", "multipart/form-data; boundary=x, --x--",
                "text/plain, '{\"moveUci\":\"f3d4\"}'" })
        void aFormOrPlainTextPostIs415(String contentType, String body) throws Exception {
            positions(5);
            start(LESSON_1);

            for (HttpResponse<String> response : List.of(send("POST", LESSON_1, contentType, body),
                    send("POST", LESSON_1 + "/items/1/answer", contentType, body))) {
                JsonNode problem = problem(response, 415);
                assertThat(problem.get("detail").asString()).isEqualTo("Content-Type must be application/json.");
                assertThat(response.body()).doesNotContain(contentType.split(";")[0]);
            }
            assertThat(count("drill_attempts")).isZero();
        }

        @Test
        void aStartWithoutContentTypeIs415AndChangesNothing() throws Exception {
            positions(5);

            problem(send("POST", LESSON_1, null, null), 415);

            assertThat(count("lesson_assignments")).isZero();
        }

        @ParameterizedTest
        @CsvSource({ "POST, /api/journey", "DELETE, /api/journey", "PUT, /api/lessons/hanging-piece/1",
                "DELETE, /api/lessons/hanging-piece/1", "PATCH, /api/lessons/hanging-piece/1",
                "GET, /api/lessons/hanging-piece/1/items/1/answer", "PUT, /api/lessons/hanging-piece/1/items/1/answer" })
        void otherMethodsAre405(String method, String path) throws Exception {
            problem(send(method, path, JSON, method.equals("GET") ? null : "{}"), 405);
        }

        @Test
        void aCrossOriginPreflightForAPostIsRefused() throws Exception {
            HttpResponse<String> response = send(request(LESSON_1).method("OPTIONS", BodyPublishers.noBody())
                    .header("Origin", "https://evil.example").header("Access-Control-Request-Method", "POST")
                    .header("Access-Control-Request-Headers", "content-type").build());

            // No CORS configuration: whatever the status, the answer grants nothing, so a browser never sends
            // the cross-origin JSON POST. The frontend calls /api on its own origin through nginx.
            assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
            assertThat(response.headers().firstValue("Access-Control-Allow-Methods")).isEmpty();
            assertThat(response.headers().firstValue("Access-Control-Allow-Headers")).isEmpty();
        }

        @Test
        void crossOriginRequestsGetNoCorsHeaders() throws Exception {
            positions(5);
            HttpResponse<String> map = send(request("/api/journey").header("Origin", "https://evil.example").build());
            HttpResponse<String> started = send(request(LESSON_1).header("Origin", "https://evil.example")
                    .header("Content-Type", JSON).POST(BodyPublishers.noBody()).build());

            for (HttpResponse<String> response : List.of(map, started)) {
                assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
                assertThat(response.headers().firstValue("Access-Control-Allow-Credentials")).isEmpty();
            }
        }
    }

    // ---- the two races of the #89 report ----

    /**
     * The two races, provoked without sleeps: a second connection makes the competing change and holds it
     * uncommitted, the request is sent, and only when PostgreSQL reports the request waiting for that lock is
     * the competing change committed. The request then meets exactly the conflict the race would give.
     */
    @Nested
    class Races {

        private void awaitARequestWaitingForALock() {
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' "
                    + "AND datname = current_database()", Long.class) == 0) {
                assertThat(System.nanoTime()).as("the request waits for the competing change").isLessThan(deadline);
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
            }
        }

        @Test
        void anAnswerWhileAReanalysisDeletesItsItemIs409() throws Exception {
            positions(5);
            start(LESSON_1);
            long drill = jdbc.queryForObject("SELECT drill_id FROM lesson_items WHERE position = 1", Long.class);

            CompletableFuture<HttpResponse<String>> answer;
            try (Connection reanalysis = dataSource.getConnection()) {
                reanalysis.setAutoCommit(false);
                try (PreparedStatement delete = reanalysis.prepareStatement(
                        "DELETE FROM mistake_findings WHERE id = (SELECT finding_id FROM drills WHERE id = ?)")) {
                    delete.setLong(1, drill);
                    assertThat(delete.executeUpdate()).isEqualTo(1);
                }
                // The request still sees the item; storing its attempt waits for the deleted rows' lock.
                answer = http.sendAsync(request(LESSON_1 + "/items/1/answer").header("Content-Type", JSON)
                        .POST(BodyPublishers.ofString("{\"moveUci\":\"f3d4\"}")).build(), BodyHandlers.ofString());
                awaitARequestWaitingForALock();
                reanalysis.commit();
            }

            JsonNode body = problem(answer.get(30, TimeUnit.SECONDS), 409);

            assertThat(body.get("detail").asString()).startsWith("The lesson changed while the answer was stored");
            assertThat(count("drill_attempts")).isZero();
            assertThat(positionsOf(get(LESSON_1))).containsExactly(2, 3, 4, 5);
        }

        @Test
        void twoFirstStartsAtOnceBothReturnTheLesson() throws Exception {
            List<Long> ids = positions(5);

            CompletableFuture<HttpResponse<String>> second;
            try (Connection first = dataSource.getConnection()) {
                first.setAutoCommit(false);
                // The first start, as the service stores it: the assignment and its 5 items.
                long assignment;
                try (PreparedStatement insert = first.prepareStatement("INSERT INTO lesson_assignments "
                        + "(user_id, lesson_key, curriculum_version) VALUES (?, 'hanging-piece/1', ?) RETURNING id")) {
                    insert.setLong(1, user.getId());
                    insert.setString(2, Curriculum.VERSION);
                    try (ResultSet rows = insert.executeQuery()) {
                        rows.next();
                        assignment = rows.getLong(1);
                    }
                }
                try (PreparedStatement item = first.prepareStatement("INSERT INTO lesson_items "
                        + "(assignment_id, drill_id, position, role) VALUES (?, ?, ?, ?)")) {
                    for (int i = 0; i < ids.size(); i++) {
                        item.setLong(1, assignment);
                        item.setLong(2, ids.get(i));
                        item.setInt(3, i + 1);
                        item.setString(4, i < 3 ? "PRACTICE" : "CHECK");
                        item.executeUpdate();
                    }
                }
                // The second start does not see the first yet; its insert waits on the unique constraint.
                second = http.sendAsync(request(LESSON_1).header("Content-Type", JSON).POST(BodyPublishers.noBody())
                        .build(), BodyHandlers.ofString());
                awaitARequestWaitingForALock();
                first.commit();
            }

            JsonNode lesson = ok(second.get(30, TimeUnit.SECONDS));

            assertThat(lesson.get("lesson").get("state").asString()).isEqualTo("IN_PROGRESS");
            assertThat(positionsOf(lesson)).containsExactly(1, 2, 3, 4, 5);
            assertThat(count("lesson_assignments")).isEqualTo(1);
            assertThat(count("lesson_items")).isEqualTo(5);
        }

        @Test
        void twoRetriesAtOnceAddOnePair() throws Exception {
            positions(5);
            start(LESSON_1);
            ok(answer(4, WRONG));
            ok(answer(5, RIGHT));
            List<Long> fresh = positions(2);
            long assignment = jdbc.queryForObject("SELECT id FROM lesson_assignments", Long.class);

            CompletableFuture<HttpResponse<String>> second;
            try (Connection first = dataSource.getConnection()) {
                first.setAutoCommit(false);
                // The first retry, as the service stores it: the new pair at 7 and 8.
                try (PreparedStatement item = first.prepareStatement("INSERT INTO lesson_items "
                        + "(assignment_id, drill_id, position, role) VALUES (?, ?, ?, 'CHECK')")) {
                    for (int i = 0; i < 2; i++) {
                        item.setLong(1, assignment);
                        item.setLong(2, fresh.get(i));
                        item.setInt(3, 7 + i);
                        item.executeUpdate();
                    }
                }
                second = http.sendAsync(request(LESSON_1).header("Content-Type", JSON).POST(BodyPublishers.noBody())
                        .build(), BodyHandlers.ofString());
                awaitARequestWaitingForALock();
                first.commit();
            }

            JsonNode lesson = ok(second.get(30, TimeUnit.SECONDS));

            assertThat(positionsOf(lesson)).containsExactly(1, 2, 3, 4, 5, 7, 8);
            assertThat(currentCheckOf(lesson)).containsExactly(7, 8);
            assertThat(count("lesson_items")).isEqualTo(7);
        }
    }
}
