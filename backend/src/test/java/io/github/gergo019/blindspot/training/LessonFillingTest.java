package io.github.gergo019.blindspot.training;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.gergo019.blindspot.analysis.api.FindingResponses.Facts;
import io.github.gergo019.blindspot.training.LessonFilling.Fillable;
import io.github.gergo019.blindspot.training.LessonFilling.NeedsPositions;
import io.github.gergo019.blindspot.training.LessonFilling.Outcome;
import io.github.gergo019.blindspot.training.LessonFilling.Position;

/** The curriculum, the order of positions and how lessons fill up (ADR 0008), without a database. */
class LessonFillingTest {

    private static final Curriculum.Unit UNIT = Curriculum.Unit.HANGING_PIECE;

    private static List<Position> positions(int n) {
        List<Position> positions = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            positions.add(new Position(i, 3, false));
        }
        return positions;
    }

    // ---- curriculum ----

    @Test
    void theCurriculumHasOneUnitOfFiveLessonsUnderStableKeys() {
        assertThat(Curriculum.lessons(UNIT)).extracting(Curriculum.Lesson::key).containsExactly("hanging-piece/1",
                "hanging-piece/2", "hanging-piece/3", "hanging-piece/4", "hanging-piece/5");
        assertThat(Curriculum.lessons(UNIT)).extracting(Curriculum.Lesson::number).containsExactly(1, 2, 3, 4, 5);
        assertThat(Curriculum.lessons(UNIT)).allSatisfy(lesson -> {
            assertThat(lesson.title()).isNotBlank();
            assertThat(lesson.explanation()).isNotBlank();
        });
        assertThat(UNIT.motif()).isEqualTo(io.github.gergo019.blindspot.analysis.Motif.HANGING_PIECE);
        assertThat(Curriculum.lesson("hanging-piece/6")).isEmpty();
    }

    @Test
    void sizesAreFivePracticeAndTwoCheckWithAMinimumOfThreeAndTwo() {
        assertThat(Curriculum.TARGET_PRACTICE).isEqualTo(5);
        assertThat(Curriculum.MIN_PRACTICE).isEqualTo(3);
        assertThat(Curriculum.CHECK_ITEMS).isEqualTo(2);
        assertThat(Curriculum.TARGET_SIZE).isEqualTo(7);
        assertThat(Curriculum.MIN_SIZE).isEqualTo(5);
    }

    // ---- order ----

    @Test
    void higherMaterialLostFirstAndDiscoveredLossesLast() {
        List<Position> ordered = LessonFilling.order(List.of(
                new Position(1, 3, false),
                new Position(2, 9, true),    // a discovered queen loss still comes after every direct loss
                new Position(3, 9, false),
                new Position(4, 5, false),
                new Position(5, 3, true)));

        assertThat(ordered).extracting(Position::drillId).containsExactly(3L, 4L, 1L, 2L, 5L);
    }

    @Test
    void tiesAreBrokenByDrillIdSoTheOrderIsStable() {
        assertThat(LessonFilling.order(List.of(new Position(7, 3, false), new Position(2, 3, false))))
                .extracting(Position::drillId).containsExactly(2L, 7L);
    }

    @Test
    void positionFactsFromTheFindingsReadModel() {
        Position direct = Position.of(1, new Facts("rook", "b6", "knight", 4, "rook", "b8", "b6"));
        Position discovered = Position.of(2, new Facts("knight", "d4", "knight", 2, "queen", "d1", "e2"));
        Position unknown = Position.of(3, new Facts(null, null, null, null, null, null, null));

        assertThat(direct).isEqualTo(new Position(1, 4, false));
        assertThat(discovered).isEqualTo(new Position(2, 2, true));
        assertThat(unknown).isEqualTo(new Position(3, 0, false));
    }

    // ---- filling ----

    @Test
    void withTheDemosFivePositionsOnlyLessonOneFills() {
        List<Outcome> plan = LessonFilling.plan(UNIT, positions(5), Set.of());

        assertThat(plan.get(0)).isInstanceOfSatisfying(Fillable.class, fill -> {
            assertThat(fill.practice()).containsExactly(1L, 2L, 3L);
            assertThat(fill.check()).containsExactly(4L, 5L);
        });
        assertThat(plan.subList(1, 5)).allSatisfy(outcome -> assertThat(outcome)
                .isInstanceOfSatisfying(NeedsPositions.class, needs -> assertThat(needs.positionsNeeded()).isEqualTo(5)));
    }

    @Test
    void fewerThanFivePositionsFillNoLesson() {
        assertThat(LessonFilling.plan(UNIT, positions(4), Set.of()).get(0))
                .isEqualTo(new NeedsPositions(Curriculum.lesson("hanging-piece/1").orElseThrow(), 1));
    }

    @Test
    void aLessonTakesAtMostSevenAndTheNextOneTheRest() {
        List<Outcome> plan = LessonFilling.plan(UNIT, positions(13), Set.of());

        assertThat(plan.get(0)).isInstanceOfSatisfying(Fillable.class,
                fill -> assertThat(fill.drillIds()).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L));
        assertThat(plan.get(1)).isInstanceOfSatisfying(Fillable.class, fill -> {
            assertThat(fill.practice()).containsExactly(8L, 9L, 10L, 11L);
            assertThat(fill.check()).containsExactly(12L, 13L);
        });
        assertThat(plan.get(2)).isInstanceOfSatisfying(NeedsPositions.class,
                needs -> assertThat(needs.positionsNeeded()).isEqualTo(5));
    }

    @Test
    void aLaterLessonNeverTakesWhatAnEarlierUnassignedLessonWouldGet() {
        // 8 positions: lesson 1 takes 7, so lesson 2 has 1 and needs 4 more.
        List<Outcome> plan = LessonFilling.plan(UNIT, positions(8), Set.of());

        assertThat(plan.get(1)).isInstanceOfSatisfying(NeedsPositions.class,
                needs -> assertThat(needs.positionsNeeded()).isEqualTo(4));
    }

    @Test
    void assignedLessonsTakeNothing() {
        List<Outcome> plan = LessonFilling.plan(UNIT, positions(5), Set.of("hanging-piece/1"));

        assertThat(plan).hasSize(4);
        assertThat(plan.get(0).lesson().key()).isEqualTo("hanging-piece/2");
        assertThat(plan.get(0)).isInstanceOf(Fillable.class);
    }

    @Test
    void positionsAreTakenInTheHeuristicsOrder() {
        List<Position> unused = new ArrayList<>(positions(4));
        unused.add(new Position(99, 9, false));

        assertThat(LessonFilling.plan(UNIT, unused, Set.of()).get(0)).isInstanceOfSatisfying(Fillable.class,
                fill -> assertThat(fill.drillIds().get(0)).isEqualTo(99L));
    }

    // ---- retry ----

    @Test
    void aRetryTakesTheFirstTwoUnusedPositionsInOrder() {
        assertThat(LessonFilling.retryPositions(List.of(new Position(5, 1, false), new Position(6, 9, false),
                new Position(7, 5, false)))).containsExactly(6L, 7L);
        assertThat(LessonFilling.retryPositions(List.of())).isEmpty();   // the demo after lesson 1: no retry
    }
}
