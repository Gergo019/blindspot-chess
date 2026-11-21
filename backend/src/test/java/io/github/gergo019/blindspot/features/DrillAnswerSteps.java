package io.github.gergo019.blindspot.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.transaction.support.TransactionTemplate;

import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.github.gergo019.blindspot.TestData;
import io.github.gergo019.blindspot.account.ChessAccountRepository;
import io.github.gergo019.blindspot.account.User;
import io.github.gergo019.blindspot.account.UserRepository;
import io.github.gergo019.blindspot.analysis.MistakeFinding;
import io.github.gergo019.blindspot.analysis.MistakeFindingRepository;
import io.github.gergo019.blindspot.analysis.MoveEvaluationRepository;
import io.github.gergo019.blindspot.game.Game;
import io.github.gergo019.blindspot.game.GameRepository;
import io.github.gergo019.blindspot.training.Drill;
import io.github.gergo019.blindspot.training.DrillAttemptRepository;
import io.github.gergo019.blindspot.training.DrillRepository;
import io.github.gergo019.blindspot.training.DrillService;
import io.github.gergo019.blindspot.training.IllegalMoveException;
import io.github.gergo019.blindspot.training.Judgement;

/**
 * Steps for drill_answers.feature (#36). Data is seeded through the repositories; answers go through
 * {@link DrillService}, because there is no drill API yet (it comes with the lesson screens, ADR 0008). The
 * player and the empty database come from {@link BusinessRuleSteps}' hook.
 */
public class DrillAnswerSteps {

    private final TransactionTemplate tx;
    private final UserRepository users;
    private final ChessAccountRepository accounts;
    private final GameRepository games;
    private final MoveEvaluationRepository evaluations;
    private final MistakeFindingRepository findings;
    private final DrillRepository drills;
    private final DrillAttemptRepository attempts;
    private final DrillService drillService;

    private MistakeFinding mistake;
    private long drillId;
    /** SAN as written in the feature -> UCI, from the verdict table. */
    private final Map<String, String> uciBySan = new HashMap<>();
    private DrillService.Answer answer;
    private IllegalMoveException refused;
    private Instant dueBefore;

    public DrillAnswerSteps(TransactionTemplate tx, UserRepository users, ChessAccountRepository accounts,
            GameRepository games, MoveEvaluationRepository evaluations, MistakeFindingRepository findings,
            DrillRepository drills, DrillAttemptRepository attempts, DrillService drillService) {
        this.tx = tx;
        this.users = users;
        this.accounts = accounts;
        this.games = games;
        this.evaluations = evaluations;
        this.findings = findings;
        this.drills = drills;
        this.attempts = attempts;
        this.drillService = drillService;
    }

    // ---- Given ----

    @Given("I left a piece hanging with Nxe5 in one of my games")
    public void aHangingPieceWithNxe5() {
        // TestData's blunder: 4. Nxe5? in TestData.FORK_FEN, where ...Qg5 wins material.
        mistake = tx.execute(s -> {
            User player = users.findByUsername("player1").orElseThrow();
            Game game = games.save(TestData.game(accounts.findByUserId(player.getId()).get(0), "drill-game"));
            return findings.save(TestData.hangingPiece(evaluations.save(TestData.userBlunder(game, 7))));
        });
    }

    @Given("a drill was prepared from that mistake, with the engine's verdicts:")
    public void aPreparedDrill(DataTable table) {
        List<Judgement.Verdict> verdicts = new ArrayList<>();
        BigDecimal acceptBelow = new BigDecimal("0.1");
        for (Map<String, String> row : table.asMaps()) {
            BigDecimal loses = new BigDecimal(row.get("loses"));
            uciBySan.put(row.get("as"), row.get("move"));
            verdicts.add(new Judgement.Verdict(row.get("move"), row.get("as"), 0, null, loses,
                    loses.compareTo(acceptBelow) < 0, row.get("line after it")));
        }
        Judgement judgement = new Judgement(new Judgement.EngineSettings("Stockfish 19", 16, 1, 64),
                TestData.FORK_FEN, acceptBelow, verdicts);
        drillId = tx.execute(s -> {
            MistakeFinding finding = findings.findById(mistake.getId()).orElseThrow();
            User user = finding.getMoveEvaluation().getGame().getAccount().getUser();
            return drills.save(Drill.fromFinding(user, finding, judgement, Instant.now())).getId();
        });
    }

    @Given("I have answered the drill correctly twice")
    public void answeredCorrectlyTwice() {
        drillService.answer(drillId, uciBySan.get("Nxd4"));
        drillService.answer(drillId, uciBySan.get("Nc3"));
    }

    // ---- When ----

    @When("I answer the drill with {word}")
    public void answerWith(String san) {
        assertThat(uciBySan).as("moves in the verdict table").containsKey(san);
        answer = drillService.answer(drillId, uciBySan.get(san));
    }

    @When("I answer the drill with the move {string}, which is not legal there")
    public void answerWithAnIllegalMove(String uci) {
        dueBefore = drill().getNextReviewAt();
        try {
            drillService.answer(drillId, uci);
        } catch (IllegalMoveException e) {
            refused = e;
        }
    }

    // ---- Then ----

    @Then("my answer is {word}")
    public void myAnswerIs(String result) {
        assertThat(answer).as("an answer").isNotNull();
        assertThat(answer.correct()).as("correct").isEqualTo(result.equals("correct"));
    }

    @And("I see the line {string} that refutes it")
    public void iSeeTheRefutation(String line) {
        assertThat(answer.refutationUci()).isEqualTo(line);
    }

    @And("I see that {word} and {word} would have been correct, with {word} as the engine's first choice")
    public void iSeeTheAcceptedMoves(String first, String second, String best) {
        assertThat(answer.acceptedMoves()).containsExactlyInAnyOrder(uciBySan.get(first), uciBySan.get(second));
        assertThat(answer.bestMove()).isEqualTo(uciBySan.get(best));
    }

    @Then("my answer is refused as not a legal move")
    public void refusedAsIllegal() {
        assertThat(refused).as("the answer was refused").isNotNull();
        assertThat(refused.getMove()).isEqualTo("e2e5");
    }

    @And("no answer to the drill is on record")
    public void noAnswerOnRecord() {
        assertThat(attempts.findByDrillIdOrderByAttemptedAtDesc(drillId)).isEmpty();
    }

    @And("the drill is still due now")
    public void stillDue() {
        Drill drill = drill();
        assertThat(drill.getNextReviewAt()).isEqualTo(dueBefore);
        assertThat(drill.isDue(Instant.now())).isTrue();
        assertThat(drill.getRepetitions()).isZero();
    }

    @Then("the drill comes back in {int} day(s)")
    public void comesBackIn(int days) {
        Drill drill = drill();
        assertThat(drill.getIntervalDays()).isEqualTo(days);
        assertThat(drill.getNextReviewAt()).isCloseTo(Instant.now().plus(Duration.ofDays(days)),
                org.assertj.core.api.Assertions.within(1, ChronoUnit.MINUTES));
    }

    private Drill drill() {
        return drills.findById(drillId).orElseThrow();
    }
}
