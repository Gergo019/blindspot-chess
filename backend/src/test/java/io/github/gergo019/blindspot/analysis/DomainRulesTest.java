package io.github.gergo019.blindspot.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import io.github.gergo019.blindspot.TestData;
import io.github.gergo019.blindspot.analysis.Explanation.Mode;
import io.github.gergo019.blindspot.analysis.MoveEvaluation.EngineRun;
import io.github.gergo019.blindspot.analysis.MoveEvaluation.Score;
import io.github.gergo019.blindspot.game.Game;
import io.github.gergo019.blindspot.game.GameEnums.AnalysisStatus;

class DomainRulesTest {

    private static final String FACTS = "{\"motif\":\"HANGING_PIECE\",\"piece\":\"knight\",\"square\":\"e5\"}";

    private static final Instant T0 = Instant.parse("2026-11-13T12:00:00Z");

    private final Game game = TestData.game(TestData.lichessAccount(TestData.user("player")), "g1");
    private final MistakeFinding finding = TestData.hangingPiece(TestData.userBlunder(game, 7));

    @Test
    void explanationIsHiddenUntilItPassesVerification() {
        Explanation explanation = Explanation.template(finding, FACTS, "Your knight on e5 can be taken for free.");

        assertThat(explanation.isShowable()).isFalse();
        explanation.recordVerification(0, null);
        assertThat(explanation.isShowable()).isTrue();
    }

    @Test
    void anyUnsupportedClaimFailsTheExplanation() {
        Explanation explanation = Explanation.llm(finding, Mode.LLM_FREE, "model", "v1", FACTS,
                "Nxe5 loses because Black wins the rook on h1.");

        explanation.recordVerification(1, "Claims a rook capture that is not in the evidence line");

        assertThat(explanation.isShowable()).isFalse();
        assertThat(explanation.getUnsupportedClaims()).isEqualTo(1);
        assertThatThrownBy(() -> explanation.recordVerification(0, null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void llmExplanationsRecordModelAndPrompt() {
        assertThatThrownBy(() -> Explanation.llm(finding, Mode.LLM_FACTS, null, "v1", FACTS, "text"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Explanation.llm(finding, Mode.TEMPLATE, "model", "v1", FACTS, "text"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void findingsNeedEvidenceAndOnlyCoverTheUsersMistakes() {
        MoveEvaluation blunder = TestData.userBlunder(game, 7);
        assertThatThrownBy(() -> new MistakeFinding(blunder, Motif.HANGING_PIECE, " ", "v1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MistakeFinding(TestData.opponentBestMove(game, 8), Motif.HANGING_PIECE, "a1a2", "v1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void scoreIsEitherCentipawnsOrMateAndEngineRunIsComplete() {
        assertThat(Score.mate(3).mateIn()).isEqualTo(3);
        assertThatThrownBy(() -> new Score(50, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Score(null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EngineRun("", 20)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void analysisFollowsItsLifecycle() {
        assertThatThrownBy(game::completeAnalysis).isInstanceOf(IllegalStateException.class);

        game.startAnalysis(T0);
        game.failAnalysis();
        game.startAnalysis(T0);   // retry after failure is allowed
        game.completeAnalysis();

        assertThat(game.getAnalysisStatus()).isEqualTo(AnalysisStatus.DONE);
        assertThatThrownBy(() -> game.startAnalysis(T0)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void everyStartIsCountedAndTimestamped() {
        assertThat(game.getAnalysisAttempts()).isZero();
        assertThat(game.getAnalysisStartedAt()).isNull();

        game.startAnalysis(T0);

        assertThat(game.getAnalysisAttempts()).isEqualTo(1);
        assertThat(game.getAnalysisStartedAt()).isEqualTo(T0);
    }

    @Test
    void analysisIsStuckOnlyWhenRunningLongerThanTheTimeout() {
        Duration timeout = Duration.ofMinutes(30);
        assertThat(game.isAnalysisStuck(T0.plus(Duration.ofHours(5)), timeout)).isFalse(); // PENDING

        game.startAnalysis(T0);

        assertThat(game.isAnalysisStuck(T0.plus(timeout), timeout)).isFalse();
        assertThat(game.isAnalysisStuck(T0.plus(timeout).plusSeconds(1), timeout)).isTrue();
        game.completeAnalysis();
        assertThat(game.isAnalysisStuck(T0.plus(Duration.ofHours(5)), timeout)).isFalse(); // DONE
    }

    @Test
    void stuckAnalysisGoesBackToPendingOnceThenFails() {
        game.startAnalysis(T0);
        assertThat(game.recoverStuckAnalysis()).isEqualTo(AnalysisStatus.PENDING);
        assertThat(game.getAnalysisStatus()).isEqualTo(AnalysisStatus.PENDING);

        game.startAnalysis(T0.plus(Duration.ofHours(1)));
        assertThat(game.recoverStuckAnalysis()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(game.getAnalysisStatus()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(game.getAnalysisAttempts()).isEqualTo(Game.MAX_ANALYSIS_ATTEMPTS);
    }

    @Test
    void onlyARunningAnalysisCanBeRecovered() {
        assertThatThrownBy(game::recoverStuckAnalysis).isInstanceOf(IllegalStateException.class);
    }
}
