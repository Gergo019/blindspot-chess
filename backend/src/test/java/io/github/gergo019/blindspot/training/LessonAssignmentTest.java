package io.github.gergo019.blindspot.training;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.gergo019.blindspot.TestData;
import io.github.gergo019.blindspot.account.User;
import io.github.gergo019.blindspot.game.Game;

/**
 * The lesson rules of ADR 0008 and its amendment of 2026-11-27, on the entities alone: no Spring, no
 * database. {@link TestData#forkJudgement()} accepts f3d4 and rejects f3e5.
 */
class LessonAssignmentTest {

    private static final Instant NOW = Instant.parse("2026-11-27T10:00:00Z");
    private static final String RIGHT = "f3d4";
    private static final String WRONG = "f3e5";
    private static final Curriculum.Lesson LESSON_1 = Curriculum.lesson("hanging-piece/1").orElseThrow();

    private User user;
    private Game game;
    private int ply = 1;

    @BeforeEach
    void setUp() {
        user = TestData.user("player");
        game = TestData.game(TestData.lichessAccount(user), "g1");
    }

    private Drill drill() {
        return drill(user);
    }

    private Drill drill(User owner) {
        Game g = owner == user ? game : TestData.game(TestData.lichessAccount(owner), "other");
        return Drill.fromFinding(owner, TestData.hangingPiece(TestData.userBlunder(g, ply++)),
                TestData.forkJudgement(), NOW);
    }

    private List<Drill> drills(int n) {
        List<Drill> drills = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            drills.add(drill());
        }
        return drills;
    }

    /** The demo lesson: 3 practice items, then 2 check items at positions 4 and 5. */
    private LessonAssignment demoLesson() {
        return LessonAssignment.assign(user, LESSON_1, drills(5));
    }

    // ---- assigning ----

    @Test
    void practiceComesFirstAndTheLastTwoPositionsAreTheCheck() {
        LessonAssignment lesson = demoLesson();

        assertThat(lesson.getLessonKey()).isEqualTo("hanging-piece/1");
        assertThat(lesson.getCurriculumVersion()).isEqualTo(Curriculum.VERSION);
        assertThat(lesson.getItems()).extracting(LessonItem::getPosition).containsExactly(1, 2, 3, 4, 5);
        assertThat(lesson.getItems()).extracting(LessonItem::getRole).containsExactly(LessonItem.Role.PRACTICE,
                LessonItem.Role.PRACTICE, LessonItem.Role.PRACTICE, LessonItem.Role.CHECK, LessonItem.Role.CHECK);
        assertThat(lesson.isMastered()).isFalse();
    }

    @Test
    void aFullLessonHasFivePracticeItemsAndTwoCheckItems() {
        LessonAssignment lesson = LessonAssignment.assign(user, LESSON_1, drills(7));

        assertThat(lesson.getItems().stream().filter(LessonItem::isCheck).count()).isEqualTo(2);
        assertThat(lesson.currentCheck()).extracting(LessonItem::getPosition).containsExactly(6, 7);
    }

    @Test
    void aLessonNeedsAtLeastThreePracticeAndTwoCheckPositionsAndTakesAtMostSeven() {
        assertThatThrownBy(() -> LessonAssignment.assign(user, LESSON_1, drills(4)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LessonAssignment.assign(user, LESSON_1, drills(8)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPositionAppearsOnlyOnceInALesson() {
        List<Drill> drills = drills(4);
        drills.add(drills.get(0));

        assertThatThrownBy(() -> LessonAssignment.assign(user, LESSON_1, drills))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("never repeated");
    }

    @Test
    void aLessonTakesOnlyItsUsersDrills() {
        List<Drill> drills = drills(4);
        drills.add(drill(TestData.user("someone-else")));

        assertThatThrownBy(() -> LessonAssignment.assign(user, LESSON_1, drills))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- answering and mastery ----

    @Test
    void bothCheckItemsRightFirstTryMasterTheLesson() {
        LessonAssignment lesson = demoLesson();

        lesson.answer(4, RIGHT, NOW);
        assertThat(lesson.isMastered()).isFalse();
        lesson.answer(5, RIGHT, NOW.plusSeconds(30));

        assertThat(lesson.isMastered()).isTrue();
        assertThat(lesson.getMasteredAt()).isEqualTo(NOW.plusSeconds(30));
    }

    @Test
    void practiceAloneNeverMastersALesson() {
        LessonAssignment lesson = demoLesson();

        for (int position = 1; position <= 3; position++) {
            lesson.answer(position, RIGHT, NOW);
        }

        assertThat(lesson.isMastered()).isFalse();
        assertThat(lesson.getItems().get(0).isSolvedFirstTry()).isTrue();
    }

    @Test
    void oneWrongCheckAnswerFailsTheCheck() {
        LessonAssignment lesson = demoLesson();

        lesson.answer(4, WRONG, NOW);
        lesson.answer(5, RIGHT, NOW);

        assertThat(lesson.isMastered()).isFalse();
        assertThat(lesson.isCheckFailed()).isTrue();
    }

    @Test
    void aCheckItemIsAnsweredOnce() {
        LessonAssignment lesson = demoLesson();
        lesson.answer(4, WRONG, NOW);

        assertThatThrownBy(() -> lesson.answer(4, RIGHT, NOW)).isInstanceOf(CheckAlreadyAnsweredException.class);
        assertThat(lesson.getItems().get(3).getAttempts()).hasSize(1);
    }

    @Test
    void practiceItemsCanBeAnsweredAgainButOnlyTheFirstAnswerCountsAsFirstTry() {
        LessonAssignment lesson = demoLesson();

        lesson.answer(1, WRONG, NOW);
        lesson.answer(1, RIGHT, NOW.plusSeconds(10));

        LessonItem item = lesson.getItems().get(0);
        assertThat(item.getAttempts()).hasSize(2);
        assertThat(item.isSolvedFirstTry()).isFalse();
    }

    @Test
    void attemptsNameTheirItemAndCopyItsRoleAndGoThroughTheDrillsSchedule() {
        LessonAssignment lesson = demoLesson();

        DrillAttempt practice = lesson.answer(1, RIGHT, NOW);
        DrillAttempt check = lesson.answer(4, RIGHT, NOW);

        assertThat(practice.getLessonItem()).isSameAs(lesson.getItems().get(0));
        assertThat(practice.isCheck()).isFalse();
        assertThat(check.isCheck()).isTrue();
        assertThat(lesson.getItems().get(0).getDrill().getRepetitions()).isEqualTo(1);   // SM-2 as before
    }

    @Test
    void anIllegalMoveRecordsNothing() {
        LessonAssignment lesson = demoLesson();

        assertThatThrownBy(() -> lesson.answer(4, "a1a8", NOW)).isInstanceOf(IllegalMoveException.class);
        assertThat(lesson.getItems().get(3).isAnswered()).isFalse();
    }

    @Test
    void anUnknownPositionIsRefused() {
        assertThatThrownBy(() -> demoLesson().answer(6, RIGHT, NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPassedLessonStaysPassed() {
        LessonAssignment lesson = demoLesson();
        lesson.answer(4, RIGHT, NOW);
        lesson.answer(5, RIGHT, NOW);

        lesson.answer(1, WRONG, NOW.plusSeconds(60));   // practice after mastery

        assertThat(lesson.getMasteredAt()).isEqualTo(NOW);
        assertThat(lesson.isCheckFailed()).isFalse();
        assertThatThrownBy(() -> lesson.retry(drills(2))).isInstanceOf(IllegalStateException.class);
    }

    // ---- retry (amendment of 2026-11-27) ----

    private LessonAssignment failedCheck() {
        LessonAssignment lesson = demoLesson();
        lesson.answer(4, WRONG, NOW);
        lesson.answer(5, RIGHT, NOW);
        return lesson;
    }

    @Test
    void aRetryNeedsTwoUnusedPositions() {
        LessonAssignment lesson = failedCheck();

        assertThat(lesson.isRetryAvailable(0)).isFalse();
        assertThat(lesson.positionsNeededForRetry(0)).isEqualTo(2);   // the demo: lesson 1 used all 5
        assertThat(lesson.positionsNeededForRetry(1)).isEqualTo(1);
        assertThat(lesson.isRetryAvailable(2)).isTrue();
        assertThat(lesson.positionsNeededForRetry(2)).isZero();
        assertThatThrownBy(() -> lesson.retry(drills(1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRetryAddsANewPairOfCheckItemsAfterTheOthers() {
        LessonAssignment lesson = failedCheck();

        lesson.retry(drills(2));

        // Position 6 stays free, so the new check never joins the old one (see the void-check tests in LessonModelIT).
        assertThat(lesson.getItems()).extracting(LessonItem::getPosition).containsExactly(1, 2, 3, 4, 5, 7, 8);
        assertThat(lesson.currentCheck()).extracting(LessonItem::getPosition).containsExactly(7, 8);
        assertThat(lesson.isCheckFailed()).isFalse();   // the new check is not answered yet
        assertThat(lesson.needsNewCheck()).isFalse();
        assertThat(lesson.isRetryAvailable(2)).isFalse();
    }

    @Test
    void aCompleteCheckIsNotVoid() {
        assertThat(demoLesson().isCheckVoid()).isFalse();
        assertThat(failedCheck().isCheckVoid()).isFalse();
        assertThat(failedCheck().needsNewCheck()).isTrue();
    }

    @Test
    void aSecondRetryLeavesAGapAgain() {
        LessonAssignment lesson = failedCheck();
        lesson.retry(drills(2));
        lesson.answer(7, WRONG, NOW);
        lesson.answer(8, RIGHT, NOW);

        lesson.retry(drills(2));

        assertThat(lesson.currentCheck()).extracting(LessonItem::getPosition).containsExactly(10, 11);
    }

    @Test
    void passingTheRetriedCheckMastersTheLesson() {
        LessonAssignment lesson = failedCheck();
        lesson.retry(drills(2));

        lesson.answer(7, RIGHT, NOW.plusSeconds(100));
        lesson.answer(8, RIGHT, NOW.plusSeconds(200));

        assertThat(lesson.getMasteredAt()).isEqualTo(NOW.plusSeconds(200));
    }

    @Test
    void rightAnswersFromTwoDifferentChecksDoNotMasterTheLesson() {
        LessonAssignment lesson = failedCheck();   // position 5 was right
        lesson.retry(drills(2));

        lesson.answer(7, RIGHT, NOW);
        lesson.answer(8, WRONG, NOW);

        assertThat(lesson.isMastered()).isFalse();
        assertThat(lesson.isCheckFailed()).isTrue();
    }

    @Test
    void positionsAreNeverRepeatedAsCheckItems() {
        LessonAssignment lesson = failedCheck();
        Drill oldCheck = lesson.getItems().get(3).getDrill();
        Drill practice = lesson.getItems().get(0).getDrill();

        assertThatThrownBy(() -> lesson.retry(List.of(oldCheck, drill())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("never repeated");
        assertThatThrownBy(() -> lesson.retry(List.of(practice, drill())))
                .isInstanceOf(IllegalArgumentException.class);
        Drill fresh = drill();
        assertThatThrownBy(() -> lesson.retry(List.of(fresh, fresh))).isInstanceOf(IllegalArgumentException.class);
        assertThat(lesson.getItems()).hasSize(5);   // a refused retry changes nothing
    }

    @Test
    void noRetryBeforeTheCheckIsFullyAnswered() {
        LessonAssignment lesson = demoLesson();
        lesson.answer(4, WRONG, NOW);

        assertThat(lesson.isCheckFailed()).isFalse();
        assertThat(lesson.positionsNeededForRetry(0)).isZero();
        assertThatThrownBy(() -> lesson.retry(drills(2))).isInstanceOf(IllegalStateException.class);
    }
}
