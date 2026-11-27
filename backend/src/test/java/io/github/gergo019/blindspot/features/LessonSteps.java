package io.github.gergo019.blindspot.features;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.github.gergo019.blindspot.TestData;
import io.github.gergo019.blindspot.account.ChessAccountRepository;
import io.github.gergo019.blindspot.account.User;
import io.github.gergo019.blindspot.account.UserRepository;
import io.github.gergo019.blindspot.analysis.MistakeFindingRepository;
import io.github.gergo019.blindspot.analysis.MoveEvaluationRepository;
import io.github.gergo019.blindspot.game.Game;
import io.github.gergo019.blindspot.game.GameRepository;
import io.github.gergo019.blindspot.training.CheckAlreadyAnsweredException;
import io.github.gergo019.blindspot.training.Drill;
import io.github.gergo019.blindspot.training.DrillRepository;
import io.github.gergo019.blindspot.training.Judgement;
import io.github.gergo019.blindspot.training.LessonItem;
import io.github.gergo019.blindspot.training.LessonService;
import io.github.gergo019.blindspot.training.LessonService.LessonDetail;
import io.github.gergo019.blindspot.training.LessonService.LessonSummary;
import io.github.gergo019.blindspot.training.LessonService.State;

/**
 * Steps for lesson_mastery.feature (#89). Positions are seeded through the repositories, as prepared drills
 * with {@link TestData#forkJudgement()}'s verdicts; everything else goes through {@link LessonService},
 * because there is no lesson API yet (#90). A re-analysis is played by deleting findings, as the analysis
 * does before it stores new ones. The player and the empty database come from {@link BusinessRuleSteps}' hook.
 */
public class LessonSteps {

    private static final Map<String, String> UCI_BY_SAN = Map.of("Nxd4", "f3d4", "Nxe5", "f3e5");

    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final ChessAccountRepository accounts;
    private final GameRepository games;
    private final MoveEvaluationRepository evaluations;
    private final MistakeFindingRepository findings;
    private final DrillRepository drills;
    private final LessonService lessons;

    private int gameNumber;
    private List<Long> newPositions = List.of();
    private List<LessonService.Item> itemsWhenStarted = List.of();
    private LessonService.Item answeredCheckItem;
    private LessonService.LessonAnswer answer;
    private CheckAlreadyAnsweredException refused;

    public LessonSteps(TransactionTemplate tx, JdbcTemplate jdbc, UserRepository users,
            ChessAccountRepository accounts, GameRepository games, MoveEvaluationRepository evaluations,
            MistakeFindingRepository findings, DrillRepository drills, LessonService lessons) {
        this.tx = tx;
        this.jdbc = jdbc;
        this.users = users;
        this.accounts = accounts;
        this.games = games;
        this.evaluations = evaluations;
        this.findings = findings;
        this.drills = drills;
        this.lessons = lessons;
    }

    private static String key(int lesson) {
        return "hanging-piece/" + lesson;
    }

    private LessonSummary summary(int lesson) {
        return lessons.lessons().stream().filter(l -> l.key().equals(key(lesson))).findFirst().orElseThrow();
    }

    private LessonDetail lesson1() {
        return lessons.lesson(key(1));
    }

    /** The items of lesson 1's current check, in order: "the first" and "the second" check puzzle. */
    private LessonService.Item checkPuzzle(String ordinal) {
        List<LessonService.Item> check = lesson1().items().stream().filter(LessonService.Item::currentCheck).toList();
        int index = switch (ordinal) {
            case "first" -> 0;
            case "second" -> 1;
            default -> throw new IllegalArgumentException("first or second, not " + ordinal);
        };
        assertThat(check).as("the current check").hasSizeGreaterThan(index);
        return check.get(index);
    }

    private static String uci(String san) {
        assertThat(UCI_BY_SAN).as("moves the steps know").containsKey(san);
        return UCI_BY_SAN.get(san);
    }

    private void deleteFindingOf(long drillId) {
        jdbc.update("DELETE FROM mistake_findings WHERE id = (SELECT finding_id FROM drills WHERE id = ?)", drillId);
    }

    // ---- Given ----

    @Given("I left a piece hanging in {int} (new )positions that are ready as puzzles")
    public void positionsReadyAsPuzzles(int count) {
        newPositions = tx.execute(s -> {
            User player = users.findByUsername("player1").orElseThrow();
            Game game = games.save(TestData.game(accounts.findByUserId(player.getId()).get(0), "lesson-game-"
                    + ++gameNumber));
            List<Long> ids = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                ids.add(drills.save(Drill.fromFinding(player,
                        findings.save(TestData.hangingPiece(evaluations.save(TestData.userBlunder(game, 2 * i + 1)))),
                        TestData.forkJudgement(), Instant.now())).getId());
            }
            return ids;
        });
    }

    @Given("in each of them Nxd4 is a correct answer and Nxe5 is wrong")
    public void theVerdicts() {
        Judgement judgement = TestData.forkJudgement();
        assertThat(judgement.verdictFor(uci("Nxd4")).accepted()).isTrue();
        assertThat(judgement.verdictFor(uci("Nxe5")).accepted()).isFalse();
    }

    @Given("I have started lesson {int}")
    public void startedLesson(int lesson) {
        itemsWhenStarted = lessons.start(key(lesson)).items();
        assertThat(itemsWhenStarted).as("lesson " + lesson + " was assigned").isNotEmpty();
    }

    @Given("I have answered the {word} check puzzle with {word}")
    public void answeredCheckPuzzle(String ordinal, String san) {
        answerCheckPuzzle(ordinal, san);
    }

    @Given("I have failed its check")
    public void failedTheCheck() {
        answerCheckPuzzle("first", "Nxe5");
        answerCheckPuzzle("second", "Nxd4");
        assertThat(summary(1).state()).isEqualTo(State.IN_PROGRESS);
    }

    @Given("I have passed its check")
    public void passedTheCheck() {
        answerCheckPuzzle("first", "Nxd4");
        answerCheckPuzzle("second", "Nxd4");
        assertThat(summary(1).state()).isEqualTo(State.MASTERED);
    }

    // ---- When ----

    @When("I start lesson {int} again")
    public void startAgain(int lesson) {
        lessons.start(key(lesson));
    }

    @When("I answer the {word} check puzzle with {word}")
    public void answerCheckPuzzle(String ordinal, String san) {
        answeredCheckItem = checkPuzzle(ordinal);
        answer = lessons.answer(key(1), answeredCheckItem.position(), uci(san));
    }

    @When("I answer the {word} check puzzle again, with {word}")
    public void answerCheckPuzzleAgain(String ordinal, String san) {
        LessonService.Item item = checkPuzzle(ordinal);
        try {
            lessons.answer(key(1), item.position(), uci(san));
        } catch (CheckAlreadyAnsweredException e) {
            refused = e;
        }
    }

    @When("a re-analysis removes the findings behind all its puzzles")
    public void reanalysisRemovesAll() {
        lesson1().items().forEach(item -> deleteFindingOf(item.drillId()));
        assertThat(lesson1().items()).isEmpty();
    }

    @When("a re-analysis removes the finding behind the {word} check puzzle")
    public void reanalysisRemovesACheckPuzzle(String ordinal) {
        deleteFindingOf(checkPuzzle(ordinal).drillId());
    }

    // ---- Then ----

    @Then("lesson {int} can be started")
    public void canBeStarted(int lesson) {
        assertThat(summary(lesson).state()).isEqualTo(State.AVAILABLE);
    }

    @Then("lesson {int} is recommended next")
    public void recommendedNext(int lesson) {
        assertThat(lessons.lessons()).filteredOn(LessonSummary::recommended).extracting(LessonSummary::key)
                .containsExactly(key(lesson));
    }

    @Then("lesson {int} needs {int} more positions")
    public void needsPositions(int lesson, int count) {
        assertThat(summary(lesson)).extracting(LessonSummary::state, LessonSummary::positionsNeeded)
                .containsExactly(State.NEEDS_POSITIONS, count);
    }

    @Then("lesson {int} has {int} practice puzzles and then {int} check puzzles")
    public void practiceThenCheck(int lesson, int practice, int check) {
        List<LessonItem.Role> roles = new ArrayList<>();
        for (int i = 0; i < practice; i++) {
            roles.add(LessonItem.Role.PRACTICE);
        }
        for (int i = 0; i < check; i++) {
            roles.add(LessonItem.Role.CHECK);
        }
        assertThat(lessons.lesson(key(lesson)).items()).extracting(LessonService.Item::role)
                .containsExactlyElementsOf(roles);
    }

    @Then("lesson {int} is mastered")
    public void mastered(int lesson) {
        LessonDetail detail = lessons.lesson(key(lesson));
        assertThat(detail.summary().state()).isEqualTo(State.MASTERED);
        assertThat(detail.masteredAt()).isNotNull();
    }

    @Then("lesson {int} is still mastered")
    public void stillMastered(int lesson) {
        mastered(lesson);
    }

    @Then("my second answer is refused, because a check puzzle is answered once")
    public void secondAnswerRefused() {
        assertThat(refused).as("the second answer was refused").isNotNull();
    }

    @Then("only my first answer to it is on record")
    public void onlyTheFirstAnswer() {
        assertThat(jdbc.queryForList("SELECT move_uci FROM drill_attempts WHERE drill_id = ?", String.class,
                answeredCheckItem.drillId())).containsExactly(uci("Nxe5"));
    }

    @Then("my move was wrong, and I see the line {string} that refutes it")
    public void wrongWithRefutation(String line) {
        assertThat(answer.answer().correct()).isFalse();
        assertThat(answer.answer().refutationUci()).isEqualTo(line);
    }

    @Then("lesson {int} is still in progress, and no retry is available")
    public void inProgressWithoutRetry(int lesson) {
        assertThat(summary(lesson)).extracting(LessonSummary::state, LessonSummary::retryAvailable)
                .containsExactly(State.IN_PROGRESS, false);
    }

    @Then("a retry of lesson {int} needs {int} more positions")
    public void retryNeeds(int lesson, int count) {
        assertThat(summary(lesson).positionsNeeded()).isEqualTo(count);
    }

    @Then("a retry of lesson {int} is available")
    public void retryAvailable(int lesson) {
        assertThat(summary(lesson)).extracting(LessonSummary::state, LessonSummary::retryAvailable,
                LessonSummary::positionsNeeded).containsExactly(State.IN_PROGRESS, true, 0);
    }

    @Then("lesson {int} still has the same {int} puzzles")
    public void sameItems(int lesson, int count) {
        assertThat(lessons.lesson(key(lesson)).items()).hasSize(count)
                .extracting(LessonService.Item::position, LessonService.Item::drillId, LessonService.Item::role)
                .containsExactlyElementsOf(itemsWhenStarted.stream()
                        .map(i -> tuple(i.position(), i.drillId(), i.role())).toList());
    }

    @Then("lesson {int} has a new check of {int} puzzles on the new positions")
    public void newCheck(int lesson, int count) {
        List<LessonService.Item> items = lessons.lesson(key(lesson)).items();
        assertThat(items).filteredOn(LessonService.Item::currentCheck).extracting(LessonService.Item::drillId)
                .hasSize(count).containsExactlyElementsOf(newPositions);
        assertThat(itemsWhenStarted).extracting(LessonService.Item::drillId).doesNotContainAnyElementsOf(newPositions);
    }
}
