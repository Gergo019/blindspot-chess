package io.github.gergo019.blindspot.training;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import io.github.gergo019.blindspot.TestData;
import io.github.gergo019.blindspot.account.ChessAccount;
import io.github.gergo019.blindspot.account.ChessAccountRepository;
import io.github.gergo019.blindspot.account.User;
import io.github.gergo019.blindspot.account.UserRepository;
import io.github.gergo019.blindspot.analysis.MistakeFinding;
import io.github.gergo019.blindspot.analysis.MistakeFindingRepository;
import io.github.gergo019.blindspot.analysis.Motif;
import io.github.gergo019.blindspot.analysis.MoveEvaluationRepository;
import io.github.gergo019.blindspot.common.api.NotFoundException;
import io.github.gergo019.blindspot.game.Game;
import io.github.gergo019.blindspot.game.GameRepository;
import io.github.gergo019.blindspot.training.LessonService.LessonSummary;
import io.github.gergo019.blindspot.training.LessonService.State;

/**
 * {@link LessonService} on a real PostgreSQL, for the parts the lesson features (lesson_mastery.feature)
 * do not spell out: lessons without positions, how unassigned lessons share positions, the order taken from
 * the findings' facts, the evidence and the schedule of an answer, and the database refusing a second check
 * answer that the entities could not see.
 */
@SpringBootTest(properties = { "blindspot.lichess.importer.enabled=false", "blindspot.analysis.job.enabled=false",
        "blindspot.training.drill-preparation.enabled=false", "blindspot.lichess.username=Player1" })
@Testcontainers
class LessonServiceIT {

    private static final String LESSON_1 = "hanging-piece/1";
    private static final String LESSON_2 = "hanging-piece/2";
    private static final String RIGHT = "f3d4";
    private static final String WRONG = "f3e5";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired LessonService service;
    @Autowired LessonAssignmentRepository lessons;
    @Autowired DrillRepository drills;
    @Autowired UserRepository users;
    @Autowired ChessAccountRepository accounts;
    @Autowired GameRepository games;
    @Autowired MoveEvaluationRepository evaluations;
    @Autowired MistakeFindingRepository findings;
    @Autowired TransactionTemplate tx;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;

    User user;
    int gameNumber;

    @BeforeEach
    void onlyTheConfiguredPlayer() {
        jdbc.execute("TRUNCATE users CASCADE");
        user = tx.execute(s -> {
            User player = users.save(TestData.user("player1"));
            accounts.save(TestData.lichessAccount(player));
            return player;
        });
    }

    /** Prepared drills from a new game, one per {@code materialLost}, in this order; returns their ids. */
    private List<Long> positions(int... materialLost) {
        return tx.execute(s -> {
            ChessAccount account = accounts.findByUserId(user.getId()).get(0);
            Game game = games.save(TestData.game(account, "g" + ++gameNumber));
            List<Long> ids = new ArrayList<>();
            for (int i = 0; i < materialLost.length; i++) {
                String facts = "{\"piece\":\"knight\",\"materialLost\":" + materialLost[i] + ",\"movedPiece\":\"knight\"}";
                MistakeFinding finding = findings.save(new MistakeFinding(
                        evaluations.save(TestData.userBlunder(game, 2 * i + 1)), Motif.HANGING_PIECE,
                        "d8g5 e5f7 g5g2", "detector-0.1", facts));
                ids.add(drills.save(Drill.fromFinding(user, finding, TestData.forkJudgement(), Instant.now())).getId());
            }
            return ids;
        });
    }

    private List<Long> positions(int count) {
        return positions(new int[count]);
    }

    private LessonSummary summary(String key) {
        return service.lessons().stream().filter(l -> l.key().equals(key)).findFirst().orElseThrow();
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    @Test
    void withoutPositionsEveryLessonNeedsFiveAndStartingChangesNothing() {
        assertThat(service.lessons()).hasSize(5).allSatisfy(l -> {
            assertThat(l.state()).isEqualTo(State.NEEDS_POSITIONS);
            assertThat(l.positionsNeeded()).isEqualTo(5);
            assertThat(l.recommended()).isFalse();
        });

        LessonService.LessonDetail started = service.start(LESSON_1);

        assertThat(started.summary().state()).isEqualTo(State.NEEDS_POSITIONS);
        assertThat(started.items()).isEmpty();
        assertThat(count("lesson_assignments")).isZero();
    }

    @Test
    void unassignedLessonsSharePositionsInLessonOrder() {
        positions(4);
        assertThat(summary(LESSON_1).positionsNeeded()).isEqualTo(1);

        positions(8);   // 12 in all: lesson 1 would take 7, lesson 2 the other 5

        assertThat(summary(LESSON_1)).extracting(LessonSummary::state, LessonSummary::size, LessonSummary::recommended)
                .containsExactly(State.AVAILABLE, 7, true);
        assertThat(summary(LESSON_2)).extracting(LessonSummary::state, LessonSummary::size, LessonSummary::recommended)
                .containsExactly(State.AVAILABLE, 5, false);
        assertThat(summary("hanging-piece/3").positionsNeeded()).isEqualTo(5);
    }

    @Test
    void startingALaterLessonFirstLeavesTheEarlierLessonsPositions() {
        List<Long> ids = positions(12);

        service.start(LESSON_2);

        assertThat(service.lesson(LESSON_2).items()).extracting(LessonService.Item::drillId)
                .containsExactlyElementsOf(ids.subList(7, 12));
        assertThat(summary(LESSON_1)).extracting(LessonSummary::state, LessonSummary::size)
                .containsExactly(State.AVAILABLE, 7);
    }

    @Test
    void positionsAreOrderedByTheFindingsFacts() {
        List<Long> ids = positions(1, 9, 3, 5, 3);   // materialLost read through FindingReadService

        List<LessonService.Item> items = service.start(LESSON_1).items();

        assertThat(items).extracting(LessonService.Item::drillId)
                .containsExactly(ids.get(1), ids.get(3), ids.get(2), ids.get(4), ids.get(0));
        assertThat(items).extracting(LessonService.Item::role).containsExactly(LessonItem.Role.PRACTICE,
                LessonItem.Role.PRACTICE, LessonItem.Role.PRACTICE, LessonItem.Role.CHECK, LessonItem.Role.CHECK);
        assertThat(items).extracting(LessonService.Item::currentCheck).containsExactly(false, false, false, true, true);
    }

    @Test
    void startingIsIdempotent() {
        positions(5);
        List<LessonService.Item> first = service.start(LESSON_1).items();

        LessonService.LessonDetail again = service.start(LESSON_1);

        assertThat(again.items()).isEqualTo(first);
        assertThat(again.summary()).extracting(LessonSummary::state, LessonSummary::recommended)
                .containsExactly(State.IN_PROGRESS, true);
        assertThat(count("lesson_assignments")).isEqualTo(1);
        assertThat(count("lesson_items")).isEqualTo(5);
    }

    @Test
    void aWrongAnswerComesWithItsEvidenceAndIsScheduledThroughSm2() {
        positions(5);
        service.start(LESSON_1);
        Instant before = Instant.now();

        LessonService.LessonAnswer answer = service.answer(LESSON_1, 1, WRONG);

        assertThat(answer.firstTry()).isTrue();
        assertThat(answer.answer().correct()).isFalse();
        assertThat(answer.answer().refutationUci()).isEqualTo("d8g5 e5f7 g5g2");
        assertThat(answer.answer().bestMove()).isEqualTo("f3d4");
        assertThat(answer.answer().acceptedMoves()).containsExactly("b1c3", "f3d4");
        assertThat(answer.gameMove().uci()).isEqualTo("f3e5");
        assertThat(answer.evidenceLineUci()).containsExactly("d8g5", "e5f7", "g5g2");
        assertThat(answer.lesson().state()).isEqualTo(State.IN_PROGRESS);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM drill_attempts WHERE lesson_item_id IS NOT NULL",
                Long.class)).isEqualTo(1);
        Drill drill = drills.findById(answer.answer().drillId()).orElseThrow();
        assertThat(drill.getIntervalDays()).isEqualTo(1);
        assertThat(drill.getNextReviewAt()).isCloseTo(before.plus(Duration.ofDays(1)),
                org.assertj.core.api.Assertions.within(1, ChronoUnit.MINUTES));
    }

    @Test
    void aPracticeItemCanBeAnsweredAgainButOnlyTheFirstAnswerCounts() {
        positions(5);
        service.start(LESSON_1);
        service.answer(LESSON_1, 1, WRONG);

        LessonService.LessonAnswer again = service.answer(LESSON_1, 1, RIGHT);

        assertThat(again.firstTry()).isFalse();
        assertThat(again.answer().correct()).isTrue();
        assertThat(again.lesson().solvedFirstTry()).isZero();
        assertThat(service.lesson(LESSON_1).items().get(0)).extracting(LessonService.Item::answered,
                LessonService.Item::solvedFirstTry).containsExactly(true, false);
    }

    @Test
    void anIllegalMoveIsRejectedAndNothingIsRecorded() {
        positions(5);
        service.start(LESSON_1);

        assertThatThrownBy(() -> service.answer(LESSON_1, 4, "e2e5")).isInstanceOf(IllegalMoveException.class);

        assertThat(count("drill_attempts")).isZero();
        assertThat(service.lesson(LESSON_1).items().get(3).answered()).isFalse();
    }

    @Test
    void aSecondAnswerToACheckItemIsRefused() {
        positions(5);
        service.start(LESSON_1);
        service.answer(LESSON_1, 4, WRONG);

        assertThatThrownBy(() -> service.answer(LESSON_1, 4, RIGHT)).isInstanceOf(CheckAlreadyAnsweredException.class);

        assertThat(count("drill_attempts")).isEqualTo(1);
    }

    @Test
    void theDatabaseRefusesACheckAnswerThatAConcurrentAnswerBeat() {
        positions(5);
        service.start(LESSON_1);
        long item = jdbc.queryForObject("SELECT id FROM lesson_items WHERE position = 4", Long.class);
        long drill = jdbc.queryForObject("SELECT drill_id FROM lesson_items WHERE id = ?", Long.class, item);
        TransactionTemplate concurrent = new TransactionTemplate(transactions);
        concurrent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            // This transaction has seen the item unanswered ...
            lessons.findByUserIdAndLessonKey(user.getId(), LESSON_1).orElseThrow().getItems()
                    .forEach(i -> i.getAttempts().size());
            // ... when another answer to it is committed.
            concurrent.executeWithoutResult(c -> jdbc.update("INSERT INTO drill_attempts "
                    + "(drill_id, move_uci, correct, lesson_item_id, is_check) VALUES (?, 'f3d4', true, ?, true)",
                    drill, item));
            service.answer(LESSON_1, 4, RIGHT);
        })).isInstanceOf(CheckAlreadyAnsweredException.class);

        assertThat(count("drill_attempts")).isEqualTo(1);
        assertThat(summary(LESSON_1).state()).isEqualTo(State.IN_PROGRESS);
    }

    @Test
    void unknownLessonsAndPositionsAreNotFound() {
        positions(5);

        assertThatThrownBy(() -> service.start("hanging-piece/9")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.answer(LESSON_1, 1, RIGHT)).isInstanceOf(NotFoundException.class);
        service.start(LESSON_1);
        assertThatThrownBy(() -> service.answer(LESSON_1, 6, RIGHT)).isInstanceOf(NotFoundException.class);
    }
}
