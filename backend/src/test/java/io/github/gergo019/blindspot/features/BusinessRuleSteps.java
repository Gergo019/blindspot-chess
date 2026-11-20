package io.github.gergo019.blindspot.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import io.cucumber.java.Before;
import io.cucumber.java.ParameterType;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
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
import io.github.gergo019.blindspot.analysis.MoveEvaluationRepository;
import io.github.gergo019.blindspot.game.Game;
import io.github.gergo019.blindspot.game.GameEnums.AnalysisStatus;
import io.github.gergo019.blindspot.game.GameEnums.GameResult;
import io.github.gergo019.blindspot.game.GameEnums.PieceColor;
import io.github.gergo019.blindspot.game.GameEnums.Speed;
import io.github.gergo019.blindspot.game.GameRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Steps for the product-rule features. Data is seeded through the repositories (never through the analysis
 * service), and every check goes through the real HTTP API, as the web app uses it. Each scenario starts
 * from an empty database with only the configured player.
 */
public class BusinessRuleSteps {

    private static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");
    private static final String FACTS = """
            {"piece":"rook","square":"b6","capturedBy":"knight","materialLost":4,"movedPiece":"rook","movedFrom":"b8","movedTo":"b6"}""";

    private final HttpClient http = HttpClient.newHttpClient();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final UserRepository users;
    private final ChessAccountRepository accounts;
    private final GameRepository games;
    private final MoveEvaluationRepository evaluations;
    private final MistakeFindingRepository findings;
    private final ExplanationRepository explanations;
    private final JsonMapper json;
    private final int port;

    private ChessAccount player;
    /** The player's analysed rapid games, newest first. */
    private final List<Game> analysedGames = new ArrayList<>();
    private Game olderGame;
    private MistakeFinding mistake;
    private int minutesAgo;
    private int nextPly;
    private JsonNode report;
    private JsonNode openedMistake;

    public BusinessRuleSteps(JdbcTemplate jdbc, TransactionTemplate tx, UserRepository users,
            ChessAccountRepository accounts, GameRepository games, MoveEvaluationRepository evaluations,
            MistakeFindingRepository findings, ExplanationRepository explanations, JsonMapper json,
            @Value("${local.server.port}") int port) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.users = users;
        this.accounts = accounts;
        this.games = games;
        this.evaluations = evaluations;
        this.findings = findings;
        this.explanations = explanations;
        this.json = json;
        this.port = port;
    }

    @Before
    public void startWithOnlyTheConfiguredPlayer() {
        jdbc.execute("TRUNCATE users, chess_accounts, games, move_evaluations, mistake_findings, explanations "
                + "RESTART IDENTITY CASCADE");
        player = tx.execute(s -> accounts.save(TestData.lichessAccount(users.save(new User("player1")))));
    }

    // ---- parameter types: the product words used in the features ----

    /** How the explanation text was made: a template from checked facts, or one of the two AI modes. */
    @ParameterType("template|facts-only AI|free AI")
    public Explanation.Mode explanationKind(String kind) {
        return switch (kind) {
            case "template" -> Explanation.Mode.TEMPLATE;
            case "facts-only AI" -> Explanation.Mode.LLM_FACTS;
            default -> Explanation.Mode.LLM_FREE;
        };
    }

    /** The result of verification, or that it has not run yet. */
    @ParameterType("passed|failed|is still pending")
    public String verification(String outcome) {
        return outcome;
    }

    // ---- Given: games and mistakes ----

    @Given("I have played {int} rapid game(s) that have/has been analysed")
    public void analysedRapidGames(int count) {
        for (int i = 0; i < count; i++) {
            analysedGames.add(game(Speed.RAPID, AnalysisStatus.DONE));
        }
    }

    @Given("I have also played an older rapid game that has been analysed")
    public void anOlderAnalysedRapidGame() {
        olderGame = game(Speed.RAPID, AnalysisStatus.DONE);
    }

    @Given("I left a piece hanging in my {int} newest games")
    public void hangingInNewestGames(int count) {
        assertThat(analysedGames).as("analysed games").hasSizeGreaterThanOrEqualTo(count);
        analysedGames.subList(0, count).forEach(g -> mistake = mistakeIn(g, Motif.HANGING_PIECE));
    }

    @Given("I left a piece hanging in my newest game")
    public void hangingInNewestGame() {
        mistake = mistakeIn(analysedGames.get(0), Motif.HANGING_PIECE);
    }

    @Given("I left a piece hanging twice in my newest game")
    public void hangingTwiceInNewestGame() {
        mistakeIn(analysedGames.get(0), Motif.HANGING_PIECE);
        mistakeIn(analysedGames.get(0), Motif.HANGING_PIECE);
    }

    @Given("I left a piece hanging in my second newest game")
    public void hangingInSecondNewestGame() {
        mistakeIn(analysedGames.get(1), Motif.HANGING_PIECE);
    }

    @Given("I left a piece hanging in that older game")
    public void hangingInTheOlderGame() {
        mistakeIn(olderGame, Motif.HANGING_PIECE);
    }

    @Given("I left a piece hanging in a blitz game")
    public void hangingInABlitzGame() {
        mistakeIn(game(Speed.BLITZ, AnalysisStatus.DONE), Motif.HANGING_PIECE);
    }

    @Given("I left a piece hanging in a rapid game that has not been analysed yet")
    public void hangingInAGameNotAnalysedYet() {
        mistakeIn(game(Speed.RAPID, AnalysisStatus.PENDING), Motif.HANGING_PIECE);
    }

    @Given("I left a piece hanging in a rapid game whose analysis failed")
    public void hangingInAGameWhoseAnalysisFailed() {
        mistakeIn(game(Speed.RAPID, AnalysisStatus.FAILED), Motif.HANGING_PIECE);
    }

    @Given("allowed knight forks are on record in my {int} newest games")
    public void knightForksOnRecord(int count) {
        analysedGames.subList(0, count).forEach(g -> mistakeIn(g, Motif.ALLOWED_KNIGHT_FORK));
    }

    @Given("that mistake has a {explanationKind} explanation {string} whose verification {verification}")
    public void anExplanation(Explanation.Mode kind, String text, String verification) {
        MistakeFinding finding = mistake;
        tx.executeWithoutResult(s -> {
            Explanation explanation = kind == Explanation.Mode.TEMPLATE
                    ? Explanation.template(finding, FACTS, text)
                    : Explanation.llm(finding, kind, "model-x", "prompt-1", FACTS, text);
            switch (verification) {
                case "passed" -> explanation.recordVerification(0, "all claims supported");
                case "failed" -> explanation.recordVerification(1, "one unsupported claim");
                default -> {
                    // still pending: not verified
                }
            }
            explanations.save(explanation);
        });
    }

    // ---- When: through the HTTP API ----

    @When("I look at my blind-spot report")
    public void lookAtTheReport() throws Exception {
        report = get("/api/patterns");
    }

    @When("I open that mistake")
    public void openThatMistake() throws Exception {
        openedMistake = get("/api/findings/" + mistake.getId());
    }

    // ---- Then ----

    @Then("it says I left a piece hanging in {int} of my last {int} rapid games")
    public void reportsHangingPieces(int games, int lookedAt) {
        JsonNode hanging = motif("HANGING_PIECE");
        assertThat(hanging).as("hanging pieces in the report %s", report).isNotNull();
        assertThat(hanging.get("gamesWithFinding").asInt()).as("games with the mistake").isEqualTo(games);
        assertThat(hanging.get("sampleSize").asInt()).as("games looked at").isEqualTo(lookedAt);
        assertThat(report.get("sampleSize").asInt()).as("games looked at, for the whole report").isEqualTo(lookedAt);
    }

    @Then("it calls it a pattern")
    public void itIsAPattern() {
        assertThat(motif("HANGING_PIECE").get("pattern").asBoolean()).as("pattern").isTrue();
    }

    @Then("it does not call it a pattern")
    public void itIsNotAPattern() {
        assertThat(motif("HANGING_PIECE").get("pattern").asBoolean()).as("pattern").isFalse();
    }

    @Then("it does not mention allowed knight forks")
    public void noKnightForks() {
        assertThat(motif("ALLOWED_KNIGHT_FORK")).as("knight forks in the report %s", report).isNull();
    }

    @Then("I see the explanation {string}")
    public void seeTheExplanation(String text) {
        assertThat(openedMistake.get("explanation").get("content").asString()).isEqualTo(text);
    }

    @Then("I see no explanation")
    public void seeNoExplanation() {
        assertThat(openedMistake.get("explanation").isNull()).as("explanation %s", openedMistake.get("explanation"))
                .isTrue();
    }

    @Then("I see the moves that prove the mistake")
    public void seeTheEvidence() {
        assertThat(openedMistake.get("evidenceLineUci")).as("evidence line").isNotEmpty();
        assertThat(openedMistake.get("played").get("uci").asString()).isNotBlank();
    }

    // ---- helpers ----

    /** A finished game of the player, a minute older than the previous one. */
    private Game game(Speed speed, AnalysisStatus status) {
        minutesAgo++;
        Instant playedAt = NOW.minusSeconds(60L * minutesAgo);
        String id = "game-" + minutesAgo;
        return tx.execute(s -> {
            Game game = new Game(player, id, playedAt, speed, true, PieceColor.BLACK, GameResult.LOSS,
                    "1. e4 e5 2. Nf3 Nc6 0-1");
            if (status != AnalysisStatus.PENDING) {
                game.startAnalysis(playedAt);
                if (status == AnalysisStatus.DONE) {
                    game.completeAnalysis();
                } else {
                    game.failAnalysis();
                }
            }
            return games.save(game);
        });
    }

    /** A blunder of the player in {@code game} with a finding of {@code motif}, at a new ply each time. */
    private MistakeFinding mistakeIn(Game game, Motif motif) {
        int ply = 2 * (++nextPly);
        return tx.execute(s -> findings.save(new MistakeFinding(evaluations.save(TestData.userBlunder(game, ply)),
                motif, "c4b6 a7b6 f1e1", "hanging-0.4", FACTS)));
    }

    private JsonNode motif(String name) {
        for (JsonNode m : report.get("motifs")) {
            if (m.get("motif").asString().equals(name)) {
                return m;
            }
        }
        return null;
    }

    private JsonNode get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        var response = http.send(request, BodyHandlers.ofString());
        assertThat(response.statusCode()).as("GET %s: %s", path, response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }
}
