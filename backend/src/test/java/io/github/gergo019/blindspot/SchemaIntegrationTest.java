package io.github.gergo019.blindspot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import io.github.gergo019.blindspot.account.ChessAccount;
import io.github.gergo019.blindspot.account.ChessAccountRepository;
import io.github.gergo019.blindspot.account.User;
import io.github.gergo019.blindspot.account.UserRepository;
import io.github.gergo019.blindspot.analysis.Explanation;
import io.github.gergo019.blindspot.analysis.Explanation.Mode;
import io.github.gergo019.blindspot.analysis.ExplanationRepository;
import io.github.gergo019.blindspot.analysis.MistakeFinding;
import io.github.gergo019.blindspot.analysis.MistakeFindingRepository;
import io.github.gergo019.blindspot.analysis.Motif;
import io.github.gergo019.blindspot.analysis.MoveEvaluation;
import io.github.gergo019.blindspot.analysis.MoveEvaluationRepository;
import io.github.gergo019.blindspot.game.Game;
import io.github.gergo019.blindspot.game.GameRepository;
import io.github.gergo019.blindspot.health.HealthController;
import io.github.gergo019.blindspot.training.Drill;
import io.github.gergo019.blindspot.training.DrillAttemptRepository;
import io.github.gergo019.blindspot.training.DrillRepository;

/**
 * Boots the whole application against a real PostgreSQL container. Startup
 * itself proves that the Flyway migration runs and that Hibernate's schema
 * validation accepts every entity mapping.
 */
@SpringBootTest
@Testcontainers
@Transactional
class SchemaIntegrationTest {

    private static final String FACTS = "{\"motif\":\"HANGING_PIECE\",\"square\":\"e5\"}";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired HealthController healthController;
    @Autowired UserRepository users;
    @Autowired ChessAccountRepository accounts;
    @Autowired GameRepository games;
    @Autowired MoveEvaluationRepository evaluations;
    @Autowired MistakeFindingRepository findings;
    @Autowired ExplanationRepository explanations;
    @Autowired DrillRepository drills;
    @Autowired DrillAttemptRepository attempts;

    @Test
    void healthReportsDatabaseUp() {
        var response = healthController.health();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().database()).isEqualTo("UP");
    }

    @Test
    void coachingLoopRoundTrip() {
        User user = users.save(TestData.user("hunter"));
        ChessAccount account = accounts.save(TestData.lichessAccount(user));
        Game game = games.save(TestData.game(account, "abcd1234"));

        MoveEvaluation blunder = evaluations.save(TestData.userBlunder(game, 7));
        evaluations.save(TestData.opponentBestMove(game, 8));
        MistakeFinding finding = findings.save(TestData.hangingPiece(blunder));

        Explanation rejected = Explanation.llm(finding, Mode.LLM_FREE, "model", "v1", FACTS, "Wins the h1 rook.");
        rejected.recordVerification(1, "unsupported capture");
        explanations.save(rejected);
        Explanation template = Explanation.template(finding, FACTS, "Your knight on e5 is unprotected.");
        template.recordVerification(0, null);
        explanations.save(template);

        Instant now = Instant.parse("2026-11-12T10:00:00Z");
        Drill own = drills.save(Drill.fromFinding(user, finding, now));
        drills.save(Drill.transferPuzzle(user, Motif.HANGING_PIECE, "00sHx", 1450, TestData.FORK_FEN, "f3d4", now));
        attempts.save(own.answer("f3d4", now));
        drills.flush();

        assertThat(evaluations.findMistakesOfUser(user.getId())).extracting(MoveEvaluation::getMoveSan)
                .containsExactly("Nxe5");
        assertThat(findings.findByUserAndMotif(user.getId(), Motif.HANGING_PIECE)).hasSize(1);
        assertThat(findings.findGameIdsWithMotif(Motif.HANGING_PIECE, java.util.List.of(game.getId())))
                .containsExactly(game.getId());
        assertThat(explanations.findShowable(finding.getId())).extracting(Explanation::getMode)
                .containsExactly(Mode.TEMPLATE);
        assertThat(drills.findByUserIdAndNextReviewAtLessThanEqualOrderByNextReviewAtAsc(user.getId(), now))
                .extracting(Drill::getKind)
                .containsExactly(Drill.Kind.TRANSFER);
        assertThat(attempts.findByDrillIdOrderByAttemptedAtDesc(own.getId())).hasSize(1);
    }

    @Test
    void sameGameCannotBeImportedTwice() {
        User user = users.save(TestData.user("dup"));
        ChessAccount account = accounts.save(TestData.lichessAccount(user));
        games.saveAndFlush(TestData.game(account, "same-id"));

        assertThatThrownBy(() -> games.saveAndFlush(TestData.game(account, "same-id")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
