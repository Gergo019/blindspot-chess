package io.github.gergo019.blindspot.training;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import io.github.gergo019.blindspot.TestData;
import io.github.gergo019.blindspot.account.ChessAccount;
import io.github.gergo019.blindspot.account.ChessAccountRepository;
import io.github.gergo019.blindspot.account.User;
import io.github.gergo019.blindspot.account.UserRepository;
import io.github.gergo019.blindspot.analysis.MistakeFindingRepository;
import io.github.gergo019.blindspot.analysis.MoveEvaluationRepository;
import io.github.gergo019.blindspot.game.Game;
import io.github.gergo019.blindspot.game.GameRepository;

/**
 * The lesson model (V6, #88; V7, #89) on a real PostgreSQL: the entity mappings, the rules the database holds
 * on its own (one attempt per check item, the copied {@code is_check}, lesson items going with their drills,
 * a drill in one lesson only), and the rules that depend on items a re-analysis deleted.
 */
@SpringBootTest(properties = { "blindspot.lichess.importer.enabled=false", "blindspot.analysis.job.enabled=false",
        "blindspot.training.drill-preparation.enabled=false" })
@Testcontainers
class LessonModelIT {

    private static final Instant NOW = Instant.parse("2026-11-27T10:00:00Z");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired LessonAssignmentRepository lessons;
    @Autowired DrillRepository drills;
    @Autowired DrillAttemptRepository attempts;
    @Autowired UserRepository users;
    @Autowired ChessAccountRepository accounts;
    @Autowired GameRepository games;
    @Autowired MoveEvaluationRepository evaluations;
    @Autowired MistakeFindingRepository findings;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    User user;
    long lessonId;

    @BeforeEach
    void demoLessonWithFivePositions() {
        jdbc.execute("TRUNCATE users CASCADE");
        lessonId = tx.execute(s -> {
            user = users.save(TestData.user("player"));
            ChessAccount account = accounts.save(TestData.lichessAccount(user));
            Game game = games.save(TestData.game(account, "g1"));
            List<Drill> positions = new ArrayList<>();
            for (int ply = 1; ply <= 9; ply += 2) {
                positions.add(drills.save(Drill.fromFinding(user,
                        findings.save(TestData.hangingPiece(evaluations.save(TestData.userBlunder(game, ply)))),
                        TestData.forkJudgement(), NOW)));
            }
            return lessons.save(LessonAssignment.assign(user, Curriculum.lesson("hanging-piece/1").orElseThrow(),
                    positions)).getId();
        });
    }

    private long itemId(int position) {
        return jdbc.queryForObject("SELECT id FROM lesson_items WHERE assignment_id = ? AND position = ?", Long.class,
                lessonId, position);
    }

    private long drillOf(int position) {
        return jdbc.queryForObject("SELECT drill_id FROM lesson_items WHERE id = ?", Long.class, itemId(position));
    }

    private void insertAttempt(long drillId, Long itemId, boolean isCheck) {
        jdbc.update("INSERT INTO drill_attempts (drill_id, move_uci, correct, lesson_item_id, is_check) "
                + "VALUES (?, 'f3d4', true, ?, ?)", drillId, itemId, isCheck);
    }

    private void answer(int position, String move, Instant at) {
        tx.executeWithoutResult(s -> lessons.findById(lessonId).orElseThrow().answer(position, move, at));
    }

    @Test
    void aLessonIsStoredWithItsItemsAndCurriculumVersion() {
        tx.executeWithoutResult(s -> {
            LessonAssignment lesson = lessons.findByUserIdAndLessonKey(user.getId(), "hanging-piece/1").orElseThrow();
            assertThat(lesson.getCurriculumVersion()).isEqualTo(Curriculum.VERSION);
            assertThat(lesson.getItems()).extracting(LessonItem::getPosition).containsExactly(1, 2, 3, 4, 5);
            assertThat(lesson.currentCheck()).extracting(LessonItem::getPosition).containsExactly(4, 5);
        });
        assertThat(jdbc.queryForList("SELECT is_check FROM lesson_items WHERE assignment_id = ? ORDER BY position",
                Boolean.class, lessonId)).containsExactly(false, false, false, true, true);
        assertThat(lessons.findUsedDrillIds(user.getId())).hasSize(5);
    }

    @Test
    void answersAreStoredAsAttemptsOfTheirItemAndMasteryIsKept() {
        answer(1, "f3e5", NOW);
        answer(4, "f3d4", NOW.plusSeconds(10));
        answer(5, "f3d4", NOW.plusSeconds(20));

        assertThat(jdbc.queryForList("SELECT is_check FROM drill_attempts WHERE lesson_item_id IS NOT NULL "
                + "ORDER BY attempted_at", Boolean.class)).containsExactly(false, true, true);
        tx.executeWithoutResult(s -> {
            LessonAssignment lesson = lessons.findById(lessonId).orElseThrow();
            assertThat(lesson.getMasteredAt()).isEqualTo(NOW.plusSeconds(20).truncatedTo(ChronoUnit.MICROS));
            assertThat(lesson.getItems().get(0).isSolvedFirstTry()).isFalse();
            assertThatThrownBy(() -> lesson.answer(4, "f3d4", NOW.plusSeconds(30)))
                    .isInstanceOf(CheckAlreadyAnsweredException.class);
        });
    }

    @Test
    void theDatabaseRefusesASecondAttemptForTheSameCheckItem() {
        insertAttempt(drillOf(4), itemId(4), true);

        assertThatThrownBy(() -> insertAttempt(drillOf(4), itemId(4), true))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("drill_attempts_one_per_check_item_uq");
    }

    @Test
    void practiceItemsTakeAnyNumberOfAttempts() {
        insertAttempt(drillOf(1), itemId(1), false);
        insertAttempt(drillOf(1), itemId(1), false);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM drill_attempts WHERE lesson_item_id = ?", Long.class,
                itemId(1))).isEqualTo(2);
    }

    @Test
    void theCopiedIsCheckMustMatchTheItemsRole() {
        // Marking a check answer as practice would get around the one-attempt index.
        assertThatThrownBy(() -> insertAttempt(drillOf(4), itemId(4), false))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("drill_attempts_lesson_item_fk");
        assertThatThrownBy(() -> insertAttempt(drillOf(1), itemId(1), true))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("drill_attempts_lesson_item_fk");
    }

    @Test
    void anAttemptMustAnswerTheItemsOwnDrill() {
        assertThatThrownBy(() -> insertAttempt(drillOf(1), itemId(2), false))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("drill_attempts_lesson_item_fk");
    }

    @Test
    void aCheckAttemptNeedsAnItemAndAReviewHasNone() {
        assertThatThrownBy(() -> insertAttempt(drillOf(1), null, true))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("drill_attempts_check_has_item");
        insertAttempt(drillOf(1), null, false);   // a review from the SM-2 queue
    }

    @Test
    void lessonItemsAreDeletedWithTheirDrillAndMasteryStays() {
        answer(4, "f3d4", NOW);
        answer(5, "f3d4", NOW);
        long checkDrill = drillOf(5);

        jdbc.update("DELETE FROM drills WHERE id = ?", checkDrill);   // as a re-analysis does, via its finding

        assertThat(jdbc.queryForObject("SELECT count(*) FROM lesson_items WHERE assignment_id = ?", Long.class,
                lessonId)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM drill_attempts WHERE drill_id = ?", Long.class,
                checkDrill)).isZero();
        assertThat(jdbc.queryForObject("SELECT mastered_at IS NOT NULL FROM lesson_assignments WHERE id = ?",
                Boolean.class, lessonId)).isTrue();
    }

    /** Retries the lesson's check on two new positions from a new game. */
    private void retry(String gameId) {
        tx.executeWithoutResult(s -> {
            ChessAccount account = accounts.findAll().get(0);
            Game game = games.save(TestData.game(account, gameId));
            List<Drill> fresh = new ArrayList<>();
            for (int ply = 1; ply <= 3; ply += 2) {
                fresh.add(drills.save(Drill.fromFinding(user,
                        findings.save(TestData.hangingPiece(evaluations.save(TestData.userBlunder(game, ply)))),
                        TestData.forkJudgement(), NOW)));
            }
            lessons.findById(lessonId).orElseThrow().retry(fresh);
        });
    }

    /** As a re-analysis does: the finding goes, and with it the drill and its lesson item. */
    private void deletePosition(int position) {
        jdbc.update("DELETE FROM drills WHERE id = ?", drillOf(position));
    }

    private <T> T inLesson(Function<LessonAssignment, T> read) {
        return tx.execute(s -> read.apply(lessons.findById(lessonId).orElseThrow()));
    }

    private List<Integer> currentCheck() {
        return inLesson(l -> l.currentCheck().stream().map(LessonItem::getPosition).toList());
    }

    private int positionsNeededForRetry(int unused) {
        Integer needed = inLesson(l -> l.positionsNeededForRetry(unused));
        return needed;
    }

    private boolean mastered() {
        return jdbc.queryForObject("SELECT mastered_at IS NOT NULL FROM lesson_assignments WHERE id = ?",
                Boolean.class, lessonId);
    }

    @Test
    void aCheckWithAnItemDeletedNeverCombinesWithAnotherCheck() {
        answer(4, "f3e5", NOW);   // the first check fails
        answer(5, "f3d4", NOW);
        retry("g2");              // positions 7 and 8; 6 stays free
        deletePosition(8);        // a re-analysis removes the retry's second item

        answer(7, "f3d4", NOW.plusSeconds(60));   // right, and position 5 was right too

        assertThat(mastered()).isFalse();
        assertThat(inLesson(LessonAssignment::isCheckVoid)).isTrue();
    }

    @Test
    void aCheckItemDeletedBeforeTheCheckVoidsItAndARetryReplacesIt() {
        deletePosition(5);

        assertThat(inLesson(LessonAssignment::isCheckVoid)).isTrue();
        assertThat(positionsNeededForRetry(0)).isEqualTo(2);
        answer(4, "f3d4", NOW);   // the item that is left cannot pass the check
        assertThat(mastered()).isFalse();

        retry("g2");
        assertThat(currentCheck()).containsExactly(6, 7);
        answer(6, "f3d4", NOW.plusSeconds(10));
        answer(7, "f3d4", NOW.plusSeconds(20));

        assertThat(mastered()).isTrue();
    }

    @Test
    void oneItemDeletedFromEachOfTwoChecksVoidsTheCheck() {
        answer(4, "f3e5", NOW);   // the first check fails, position 5 right
        answer(5, "f3d4", NOW);
        retry("g2");
        answer(7, "f3d4", NOW);   // right on the retry's first item

        deletePosition(4);        // one item of each check: 5 and 7, both right, are left
        deletePosition(8);

        assertThat(currentCheck()).containsExactly(7);
        assertThat(inLesson(LessonAssignment::isCheckVoid)).isTrue();
        assertThat(mastered()).isFalse();
    }

    @Test
    void aRetryAfterADeletedPracticeItemTakesPositionsAfterTheHighest() {
        deletePosition(3);
        answer(4, "f3e5", NOW);
        answer(5, "f3d4", NOW);

        retry("g2");   // counting items would give 5, which is taken

        assertThat(jdbc.queryForList("SELECT position FROM lesson_items WHERE assignment_id = ? ORDER BY position",
                Integer.class, lessonId)).containsExactly(1, 2, 4, 5, 7, 8);
    }

    @Test
    void aDrillBelongsToOneLessonOnly() {
        long otherLesson = jdbc.queryForObject("INSERT INTO lesson_assignments (user_id, lesson_key, "
                + "curriculum_version) VALUES (?, 'hanging-piece/2', ?) RETURNING id", Long.class, user.getId(),
                Curriculum.VERSION);

        assertThatThrownBy(() -> jdbc.update("INSERT INTO lesson_items (assignment_id, drill_id, position, role) "
                + "VALUES (?, ?, 1, 'PRACTICE')", otherLesson, drillOf(1)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("lesson_items_drill_uq");
    }

    @Test
    void aUserHasEachLessonOnce() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO lesson_assignments (user_id, lesson_key, curriculum_version) "
                + "VALUES (?, 'hanging-piece/1', ?)", user.getId(), Curriculum.VERSION))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aPositionAppearsOnceInALesson() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO lesson_items (assignment_id, drill_id, position, role) "
                + "VALUES (?, ?, 6, 'CHECK')", lessonId, drillOf(1)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("lesson_items_assignment_drill_uq");
    }
}
