package io.github.gergo019.blindspot.training;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.gergo019.blindspot.TestData;
import io.github.gergo019.blindspot.account.User;
import io.github.gergo019.blindspot.analysis.MistakeFinding;
import io.github.gergo019.blindspot.analysis.Motif;
import io.github.gergo019.blindspot.game.Game;

/**
 * Pure unit tests of the drill, answer-checking and spaced-repetition rules: no Spring, no database.
 */
class DrillTest {

    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");

    private User user;
    private MistakeFinding finding;
    private Drill drill;

    @BeforeEach
    void setUp() {
        user = TestData.user("player");
        Game game = TestData.game(TestData.lichessAccount(user), "g1");
        finding = TestData.hangingPiece(TestData.userBlunder(game, 7));
        drill = Drill.fromFinding(user, finding, TestData.forkJudgement(), NOW);
    }

    @Test
    void ownGameDrillAsksForASafeMoveInTheOriginalPosition() {
        assertThat(drill.getKind()).isEqualTo(Drill.Kind.OWN_GAME);
        assertThat(drill.getTask()).isEqualTo(Drill.Task.FIND_SAFE_MOVE);
        assertThat(drill.getMotif()).isEqualTo(Motif.HANGING_PIECE);
        assertThat(drill.getFen()).isEqualTo(TestData.FORK_FEN);
        assertThat(drill.getSolutionUci()).isEqualTo("f3d4");   // the best verdict, for display
        assertThat(drill.isDue(NOW)).isTrue();
    }

    @Test
    void theVerdictsAndHowTheyWereProducedAreKeptWithTheDrill() {
        assertThat(drill.getVerdicts()).containsOnlyKeys("f3d4", "b1c3", "d2d3", "f3e5");
        assertThat(drill.acceptedMoves()).containsExactly("b1c3", "f3d4");
        assertThat(drill.getVerdictEngine()).isEqualTo(new Judgement.EngineSettings("Stockfish 19", 16, 1, 64));
        assertThat(drill.getAcceptBelow()).isEqualByComparingTo("0.1");
        assertThat(drill.verdictFor("f3e5").refutationUci()).isEqualTo("d8g5 e5f7 g5g2");
    }

    // ---- answer checking by lookup ----

    @Test
    void theEnginesFirstChoiceIsCorrect() {
        assertThat(drill.answer("f3d4", NOW).isCorrect()).isTrue();
    }

    @Test
    void aSecondSafeMoveIsCorrectToo() {
        assertThat(drill.answer("b1c3", NOW).isCorrect()).isTrue();   // drop 0.0999, just below 0.1
    }

    @Test
    void aMoveAtTheThresholdIsWrong() {
        assertThat(drill.answer("d2d3", NOW).isCorrect()).isFalse();   // drop 0.1
    }

    @Test
    void theGamesMistakeIsWrong() {
        assertThat(drill.answer("f3e5", NOW).isCorrect()).isFalse();
    }

    @Test
    void movesAreReadAsUciIgnoringCaseAndSpaces() {
        DrillAttempt attempt = drill.answer(" B1C3 ", NOW);
        assertThat(attempt.isCorrect()).isTrue();
        assertThat(attempt.getMoveUci()).isEqualTo("b1c3");
    }

    @ParameterizedTest
    @ValueSource(strings = { "e2e5", "a1a8", "Nc3", "", "e7e8x" })
    void anIllegalMoveIsRefusedAndChangesNothing(String move) {
        drill.answer("f3d4", NOW);   // one correct answer: repetitions 1, interval 1
        Instant reviewBefore = drill.getNextReviewAt();
        BigDecimal easeBefore = drill.getEaseFactor();

        assertThatThrownBy(() -> drill.answer(move, NOW.plusSeconds(60)))
                .isInstanceOf(IllegalMoveException.class);

        assertThat(drill.getRepetitions()).isEqualTo(1);
        assertThat(drill.getIntervalDays()).isEqualTo(1);
        assertThat(drill.getNextReviewAt()).isEqualTo(reviewBefore);
        assertThat(drill.getEaseFactor()).isEqualByComparingTo(easeBefore);
    }

    @Test
    void aNullMoveIsRefused() {
        assertThatThrownBy(() -> drill.answer(null, NOW)).isInstanceOf(IllegalMoveException.class);
    }

    // ---- creating drills: the sanity rule and the judgement's contract ----

    @Test
    void aJudgementThatAcceptsTheGamesMistakeGivesNoDrill() {
        Judgement acceptsTheMistake = judgementWith(new Judgement.Verdict("f3e5", "Nxe5", 30, null,
                new BigDecimal("0.05"), true, "d8g5"));
        assertThatThrownBy(() -> Drill.fromFinding(user, finding, acceptsTheMistake, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("accepts the game's move f3e5");
    }

    @Test
    void aJudgementWithoutAVerdictForTheGamesMoveGivesNoDrill() {
        Judgement withoutTheMistake = new Judgement(TestData.forkJudgement().engine(), TestData.FORK_FEN,
                new BigDecimal("0.1"), List.of(TestData.forkJudgement().verdicts().get(0)));
        assertThatThrownBy(() -> Drill.fromFinding(user, finding, withoutTheMistake, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no verdict for the game's move");
    }

    @Test
    void aJudgementOfAnotherPositionGivesNoDrill() {
        Judgement other = new Judgement(TestData.forkJudgement().engine(), "8/8/8/8/8/8/8/K6k w - - 0 1",
                new BigDecimal("0.1"), TestData.forkJudgement().verdicts());
        assertThatThrownBy(() -> Drill.fromFinding(user, finding, other, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not " + TestData.FORK_FEN);
    }

    @Test
    void aJudgementMustBeConsistentWithItsThreshold() {
        assertThatThrownBy(() -> judgementWith(new Judgement.Verdict("f3e5", "Nxe5", 30, null,
                new BigDecimal("0.1"), true, "")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("accepted=true");
        assertThatThrownBy(() -> new Judgement(TestData.forkJudgement().engine(), TestData.FORK_FEN,
                new BigDecimal("0.1"), List.of(new Judgement.Verdict("f3e5", "Nxe5", 30, null, new BigDecimal("0.5"),
                        false, ""))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least one move");
        assertThatThrownBy(() -> new Judgement.Verdict("f3e5", "Nxe5", 30, null, new BigDecimal("0.12345"), false,
                "")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("4 decimals");
        assertThatThrownBy(() -> new Judgement.Verdict("f3e5", "Nxe5", 30, 2, BigDecimal.ZERO, true, ""))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exactly one");
        assertThatThrownBy(() -> judgementPlus(new Judgement.Verdict("f3d4", "Nxd4", 30, null, BigDecimal.ZERO,
                true, ""))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Two verdicts");
    }

    /** The fork judgement with the verdict for the same move replaced. */
    private static Judgement judgementWith(Judgement.Verdict replacement) {
        List<Judgement.Verdict> verdicts = new ArrayList<>(TestData.forkJudgement().verdicts());
        verdicts.removeIf(v -> v.uci().equals(replacement.uci()));
        verdicts.add(replacement);
        return judgement(verdicts);
    }

    /** The fork judgement with one more verdict added as it is (also a second one for the same move). */
    private static Judgement judgementPlus(Judgement.Verdict extra) {
        List<Judgement.Verdict> verdicts = new ArrayList<>(TestData.forkJudgement().verdicts());
        verdicts.add(extra);
        return judgement(verdicts);
    }

    private static Judgement judgement(List<Judgement.Verdict> verdicts) {
        return new Judgement(TestData.forkJudgement().engine(), TestData.FORK_FEN, new BigDecimal("0.1"), verdicts);
    }

    @Test
    void transferDrillComesFromAPuzzle() {
        Drill transfer = Drill.transferPuzzle(user, Motif.HANGING_PIECE, "00sHx", 1450, TestData.forkJudgement(),
                NOW);
        assertThat(transfer.getKind()).isEqualTo(Drill.Kind.TRANSFER);
        assertThat(transfer.getPuzzleId()).isEqualTo("00sHx");
        assertThat(transfer.getPuzzleRating()).isEqualTo(1450);
        assertThat(transfer.getFinding()).isNull();
        assertThat(transfer.getFen()).isEqualTo(TestData.FORK_FEN);
        assertThatThrownBy(() -> Drill.transferPuzzle(user, Motif.HANGING_PIECE, " ", 1450, TestData.forkJudgement(),
                NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Drill.transferPuzzle(user, Motif.HANGING_PIECE, "00sHx", 0, TestData.forkJudgement(),
                NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- SM-2 scheduling, unchanged by #36 ----

    @Test
    void correctAnswersSpreadReviewsOut() {
        drill.answer("f3d4", NOW);
        assertThat(drill.getIntervalDays()).isEqualTo(1);
        drill.answer("b1c3", NOW);   // a second safe move counts as correct too
        assertThat(drill.getIntervalDays()).isEqualTo(6);
        drill.answer("f3d4", NOW);
        assertThat(drill.getIntervalDays()).isEqualTo(15);   // 6 x 2.50
        assertThat(drill.getNextReviewAt()).isEqualTo(NOW.plus(Duration.ofDays(15)));
        assertThat(drill.isDue(NOW)).isFalse();
    }

    @Test
    void wrongAnswerResetsTheScheduleAndLowersEase() {
        drill.answer("f3d4", NOW);
        drill.answer("f3d4", NOW);
        DrillAttempt attempt = drill.answer("f3e5", NOW);

        assertThat(attempt.isCorrect()).isFalse();
        assertThat(drill.getRepetitions()).isZero();
        assertThat(drill.getIntervalDays()).isEqualTo(1);
        assertThat(drill.getEaseFactor()).isEqualByComparingTo("1.96");   // 2.50 - 0.54
    }

    @Test
    void easeNeverDropsBelowMinimum() {
        for (int i = 0; i < 10; i++) {
            drill.answer("d2d3", NOW);
        }
        assertThat(drill.getEaseFactor()).isEqualByComparingTo(Drill.MIN_EASE);
    }

    @Test
    void startEaseIsTwoPointFive() {
        assertThat(drill.getEaseFactor()).isEqualByComparingTo(new BigDecimal("2.50"));
    }
}
