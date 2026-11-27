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
import org.springframework.jdbc.core.JdbcTemplate;
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
import io.github.gergo019.blindspot.game.GameEnums.AnalysisStatus;
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
@SpringBootTest(properties = { "blindspot.lichess.importer.enabled=false", "blindspot.analysis.job.enabled=false",
        "blindspot.training.drill-preparation.enabled=false" })
@Testcontainers
@Transactional
class SchemaIT {

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
    @Autowired JdbcTemplate jdbc;
    @Autowired jakarta.persistence.EntityManager entityManager;

    @Test
    void healthReportsDatabaseUp() {
        var response = healthController.health();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().database()).isEqualTo("UP");
    }

    @Test
    void coachingLoopRoundTrip() {
        User user = users.save(TestData.user("player"));
        ChessAccount account = accounts.save(TestData.lichessAccount(user));
        Game game = games.save(TestData.game(account, "abcd1234"));

        MoveEvaluation blunder = evaluations.save(TestData.userBlunder(game, 7));
        evaluations.save(TestData.opponentBestMove(game, 8));
        MistakeFinding finding = findings.save(TestData.hangingPiece(blunder));

        Explanation rejected = Explanation.llm(finding, Mode.LLM_FREE, "model", "v1", FACTS, "Wins the h1 rook.");
        rejected.recordVerification(1, "unsupported capture");
        explanations.save(rejected);
        // Passes verification, but is not a template: never showable in v1 (ADR 0003).
        Explanation passedLlm = Explanation.llm(finding, Mode.LLM_FACTS, "model", "v1", FACTS,
                "Your knight on e5 can be taken.");
        passedLlm.recordVerification(0, null);
        explanations.save(passedLlm);
        Explanation unverifiedTemplate = Explanation.template(finding, FACTS, "Not checked yet.");
        explanations.save(unverifiedTemplate);
        Explanation template = Explanation.template(finding, FACTS, "Your knight on e5 is unprotected.");
        template.recordVerification(0, null);
        explanations.save(template);

        Instant now = Instant.parse("2026-11-12T10:00:00Z");
        Drill own = drills.save(Drill.fromFinding(user, finding, TestData.forkJudgement(), now));
        drills.save(Drill.transferPuzzle(user, Motif.HANGING_PIECE, "00sHx", 1450, TestData.forkJudgement(), now));
        attempts.save(own.answer("f3d4", now));
        drills.flush();

        assertThat(evaluations.findMistakesOfUser(user.getId())).extracting(MoveEvaluation::getMoveSan)
                .containsExactly("Nxe5");
        assertThat(findings.findByUserAndMotif(user.getId(), Motif.HANGING_PIECE)).hasSize(1);
        assertThat(findings.findGameIdsWithMotif(Motif.HANGING_PIECE, java.util.List.of(game.getId())))
                .containsExactly(game.getId());
        assertThat(explanations.findShowable(finding.getId())).extracting(Explanation::getContent)
                .containsExactly("Your knight on e5 is unprotected.");
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

    @Test
    void stuckAnalysisQueryFindsOnlyGamesRunningSinceBeforeTheCutoff() {
        ChessAccount account = accounts.save(TestData.lichessAccount(users.save(TestData.user("stuck"))));
        Instant cutoff = Instant.parse("2026-11-13T12:00:00Z");
        Game stuck = TestData.game(account, "stuck");
        stuck.startAnalysis(cutoff.minusSeconds(60));
        Game recent = TestData.game(account, "recent");
        recent.startAnalysis(cutoff.plusSeconds(60));
        Game pending = TestData.game(account, "pending");
        games.saveAllAndFlush(java.util.List.of(stuck, recent, pending));

        assertThat(games.findByAnalysisStatusAndAnalysisStartedAtBefore(AnalysisStatus.RUNNING, cutoff))
                .extracting(Game::getExternalId).containsExactly("stuck");
        Game reloaded = games.findById(stuck.getId()).orElseThrow();
        assertThat(reloaded.getAnalysisAttempts()).isEqualTo(1);
        assertThat(reloaded.getAnalysisStartedAt()).isEqualTo(cutoff.minusSeconds(60));
    }

    @Test
    void runningGameMustHaveAStartTimeInTheDatabase() {
        ChessAccount account = accounts.save(TestData.lichessAccount(users.save(TestData.user("nostart"))));
        Game game = games.saveAndFlush(TestData.game(account, "nostart"));

        assertThatThrownBy(() -> jdbc.update("UPDATE games SET analysis_status = 'RUNNING' WHERE id = ?", game.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void analysisAttemptsCannotBeNegative() {
        ChessAccount account = accounts.save(TestData.lichessAccount(users.save(TestData.user("negative"))));
        Game game = games.saveAndFlush(TestData.game(account, "negative"));

        assertThatThrownBy(() -> jdbc.update("UPDATE games SET analysis_attempts = -1 WHERE id = ?", game.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void engineThreadsAndHashSizeAreStoredWithEveryEvaluation() {
        Game game = games.save(TestData.game(accounts.save(TestData.lichessAccount(users.save(TestData.user("eng")))),
                "eng"));
        MoveEvaluation stored = evaluations.saveAndFlush(TestData.userBlunder(game, 7));

        assertThat(jdbc.queryForList("SELECT engine_threads, engine_hash_mb FROM move_evaluations WHERE id = ?",
                stored.getId()).get(0)).containsEntry("engine_threads", 1).containsEntry("engine_hash_mb", 64);
    }

    @Test
    void anEvaluationStoredBeforeV4LoadsWithoutEngineSettings() {
        Game game = games.save(TestData.game(accounts.save(TestData.lichessAccount(users.save(TestData.user("old")))),
                "old"));
        MoveEvaluation stored = evaluations.saveAndFlush(TestData.userBlunder(game, 7));
        jdbc.update("UPDATE move_evaluations SET engine_threads = NULL, engine_hash_mb = NULL WHERE id = ?",
                stored.getId());
        evaluations.flush();
        entityManager.clear();

        MoveEvaluation.EngineRun run = evaluations.findById(stored.getId()).orElseThrow().getEngineRun();
        assertThat(run.isComplete()).isFalse();
        assertThat(run.version()).isEqualTo(TestData.ENGINE.version());
    }

    @Test
    void aDrillKeepsEveryVerdictAndHowItWasProduced() {
        MistakeFinding finding = savedFinding("verdicts");
        User user = finding.getMoveEvaluation().getGame().getAccount().getUser();
        Drill saved = drills.saveAndFlush(Drill.fromFinding(user, finding, TestData.forkJudgement(),
                Instant.parse("2026-11-21T10:00:00Z")));
        entityManager.clear();

        Drill reloaded = drills.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getTask()).isEqualTo(Drill.Task.FIND_SAFE_MOVE);
        assertThat(reloaded.getVerdicts()).containsOnlyKeys("f3d4", "b1c3", "d2d3", "f3e5");
        assertThat(reloaded.acceptedMoves()).containsExactly("b1c3", "f3d4");
        assertThat(reloaded.verdictFor("f3e5").drop()).isEqualByComparingTo("0.6");
        assertThat(reloaded.verdictFor("f3e5").refutationUci()).isEqualTo("d8g5 e5f7 g5g2");
        assertThat(reloaded.verdictFor("f3e5").scoreCp()).isEqualTo(-210);
        assertThat(reloaded.getVerdictEngine()).isEqualTo(TestData.forkJudgement().engine());
        assertThat(reloaded.getAcceptBelow()).isEqualByComparingTo("0.1");
        assertThat(reloaded.answer("b1c3", Instant.parse("2026-11-21T10:05:00Z")).isCorrect()).isTrue();
    }

    @Test
    void aFindingHasOneDrillPerTask() {
        MistakeFinding finding = savedFinding("twice");
        User user = finding.getMoveEvaluation().getGame().getAccount().getUser();
        Instant now = Instant.parse("2026-11-21T10:00:00Z");
        drills.saveAndFlush(Drill.fromFinding(user, finding, TestData.forkJudgement(), now));

        assertThatThrownBy(() -> drills.saveAndFlush(Drill.fromFinding(user, finding, TestData.forkJudgement(), now)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aVerdictHasExactlyOneScore() {
        MistakeFinding finding = savedFinding("score");
        User user = finding.getMoveEvaluation().getGame().getAccount().getUser();
        Drill drill = drills.saveAndFlush(Drill.fromFinding(user, finding, TestData.forkJudgement(),
                Instant.parse("2026-11-21T10:00:00Z")));

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE drill_move_verdicts SET score_mate = 3 WHERE drill_id = ? AND move_uci = 'f3d4'",
                drill.getId())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aPendingPreparationNeedsARetryTime() {
        MistakeFinding finding = savedFinding("pending");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO drill_preparations (finding_id, task, status, attempts, "
                + "next_attempt_at, last_error) VALUES (?, 'FIND_SAFE_MOVE', 'PENDING', 1, NULL, 'x')",
                finding.getId())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aSkippedPreparationHasNoRetryTime() {
        MistakeFinding finding = savedFinding("skipped");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO drill_preparations (finding_id, task, status, attempts, "
                + "next_attempt_at, last_error) VALUES (?, 'FIND_SAFE_MOVE', 'SKIPPED', 1, now(), 'x')",
                finding.getId())).isInstanceOf(DataIntegrityViolationException.class);
    }

    private MistakeFinding savedFinding(String name) {
        User user = users.save(TestData.user(name));
        Game game = games.save(TestData.game(accounts.save(TestData.lichessAccount(user)), name));
        return findings.saveAndFlush(TestData.hangingPiece(evaluations.save(TestData.userBlunder(game, 7))));
    }

    @Test
    void engineThreadsAndHashSizeAreRecordedTogether() {
        Game game = games.save(TestData.game(accounts.save(TestData.lichessAccount(users.save(TestData.user("half")))),
                "half"));
        MoveEvaluation stored = evaluations.saveAndFlush(TestData.userBlunder(game, 7));

        assertThatThrownBy(() -> jdbc.update("UPDATE move_evaluations SET engine_hash_mb = NULL WHERE id = ?",
                stored.getId())).isInstanceOf(DataIntegrityViolationException.class);
    }
}
