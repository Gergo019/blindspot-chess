package io.github.gergo019.blindspot.training;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.gergo019.blindspot.TestData;
import io.github.gergo019.blindspot.account.User;
import io.github.gergo019.blindspot.analysis.Motif;
import io.github.gergo019.blindspot.game.Game;

/**
 * Pure unit tests of the drill and spaced-repetition rules: no Spring, no database.
 */
class DrillTest {

    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");

    private User user;
    private Drill drill;

    @BeforeEach
    void setUp() {
        user = TestData.user("player");
        Game game = TestData.game(TestData.lichessAccount(user), "g1");
        drill = Drill.fromFinding(user, TestData.hangingPiece(TestData.userBlunder(game, 7)), NOW);
    }

    @Test
    void ownGameDrillAsksForTheEngineMoveInTheOriginalPosition() {
        assertThat(drill.getKind()).isEqualTo(Drill.Kind.OWN_GAME);
        assertThat(drill.getMotif()).isEqualTo(Motif.HANGING_PIECE);
        assertThat(drill.getFen()).isEqualTo(TestData.FORK_FEN);
        assertThat(drill.getSolutionUci()).isEqualTo("f3d4");
        assertThat(drill.isDue(NOW)).isTrue();
    }

    @Test
    void transferDrillComesFromAPuzzle() {
        Drill transfer = Drill.transferPuzzle(user, Motif.HANGING_PIECE, "00sHx", 1450,
                "8/8/8/8/8/8/8/K6k w - - 0 1", "a1b1", NOW);

        assertThat(transfer.getKind()).isEqualTo(Drill.Kind.TRANSFER);
        assertThat(transfer.getPuzzleId()).isEqualTo("00sHx");
        assertThat(transfer.getPuzzleRating()).isEqualTo(1450);
        assertThat(transfer.getFinding()).isNull();
        assertThatThrownBy(() -> Drill.transferPuzzle(user, Motif.HANGING_PIECE, " ", 1450, "fen", "a1b1", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Drill.transferPuzzle(user, Motif.HANGING_PIECE, "00sHx", 0, "fen", "a1b1", NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void correctAnswersSpreadReviewsOut() {
        drill.answer("f3d4", NOW);
        assertThat(drill.getIntervalDays()).isEqualTo(1);

        drill.answer("f3d4", NOW);
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
            drill.answer("a2a3", NOW);
        }
        assertThat(drill.getEaseFactor()).isEqualByComparingTo(Drill.MIN_EASE);
    }

    @Test
    void startEaseIsTwoPointFive() {
        assertThat(drill.getEaseFactor()).isEqualByComparingTo(new BigDecimal("2.50"));
    }
}
