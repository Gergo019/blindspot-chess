package io.github.gergo019.blindspot.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestClient;

import io.github.gergo019.blindspot.TestData;
import io.github.gergo019.blindspot.analysis.AnalysisService.Outcome;
import io.github.gergo019.blindspot.game.Game;
import io.github.gergo019.blindspot.game.GameEnums.AnalysisStatus;
import io.github.gergo019.blindspot.game.GameRepository;

import tools.jackson.databind.json.JsonMapper;

/**
 * The service's lifecycle handling with a stubbed analysis service and mocked
 * repositories: no database, no Docker. The claim query and the transaction
 * boundaries are tested on PostgreSQL in {@code AnalysisFailureHandlingIT}.
 */
class AnalysisServiceTest {

    static final long GAME_ID = 7L;
    static final Instant STARTED = Instant.parse("2026-11-13T12:00:00Z");
    static final String URL = "http://analysis-service/analyze";

    GameRepository games = mock(GameRepository.class);
    MoveEvaluationRepository evaluations = mock(MoveEvaluationRepository.class);
    MistakeFindingRepository findings = mock(MistakeFindingRepository.class);
    MockRestServiceServer server;
    AnalysisService service;
    Game game;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://analysis-service");
        server = MockRestServiceServer.bindTo(builder).build();
        service = new AnalysisService(games, evaluations, findings, new AnalysisClient(builder.build()),
                mock(PlatformTransactionManager.class), JsonMapper.builder().build(),
                Clock.fixed(STARTED, ZoneOffset.UTC));

        game = TestData.game(TestData.lichessAccount(TestData.user("player")), "g1");
        when(games.findById(GAME_ID)).thenReturn(Optional.of(game));
        // The claim query, as the database applies it to a PENDING game.
        when(games.claimForAnalysis(GAME_ID, STARTED)).thenAnswer(call -> {
            if (game.getAnalysisStatus() != AnalysisStatus.PENDING) {
                return false;
            }
            game.startAnalysis(STARTED);
            return true;
        });
        when(games.save(any(Game.class))).thenAnswer(call -> call.getArgument(0));
        when(evaluations.saveAll(anyList())).thenAnswer(call -> call.getArgument(0));
        when(findings.saveAll(anyList())).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void rejectedGameIsMarkedFailedAtOnceAndNothingIsStored() {
        server.expect(requestTo(URL))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"detail\":\"The PGN is not a legal game\"}"));

        assertThat(service.analyzeGame(GAME_ID)).isEqualTo(Outcome.FAILED);

        assertThat(game.getAnalysisStatus()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(game.getAnalysisAttempts()).isEqualTo(1);
        verify(evaluations, never()).saveAll(anyList());
        verify(findings, never()).saveAll(anyList());
    }

    @Test
    void anUnknownClassificationIsAnInvalidResponseAndNothingIsStored() {
        // A classification the backend does not know: the game must not stay RUNNING, nor be failed at once.
        server.expect(org.springframework.test.web.client.ExpectedCount.times(2), requestTo(URL))
                .andRespond(withSuccess(AnalysisClientTest.RESPONSE.replace("\"BLUNDER\"", "\"SUPERB\""),
                        MediaType.APPLICATION_JSON));

        assertThat(service.analyzeGame(GAME_ID)).isEqualTo(Outcome.INVALID_RESPONSE_RETRY);
        assertThat(game.getAnalysisStatus()).isEqualTo(AnalysisStatus.PENDING);
        assertThat(game.getAnalysisAttempts()).isEqualTo(1);
        verify(evaluations, never()).saveAll(anyList());
        verify(findings, never()).saveAll(anyList());

        assertThat(service.analyzeGame(GAME_ID)).isEqualTo(Outcome.INVALID_RESPONSE_FAILED);
        assertThat(game.getAnalysisStatus()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(game.getAnalysisAttempts()).isEqualTo(Game.MAX_ANALYSIS_ATTEMPTS);
    }

    @Test
    void anUnknownMotifIsAnInvalidResponseAndNothingIsStored() {
        server.expect(requestTo(URL))
                .andRespond(withSuccess(AnalysisClientTest.RESPONSE.replace("\"HANGING_PIECE\"", "\"PAWN_STORM\""),
                        MediaType.APPLICATION_JSON));

        assertThat(service.analyzeGame(GAME_ID)).isEqualTo(Outcome.INVALID_RESPONSE_RETRY);
        assertThat(game.getAnalysisStatus()).isEqualTo(AnalysisStatus.PENDING);
        assertThat(game.getAnalysisAttempts()).isEqualTo(1);
        // The evaluations were fine, but they are not stored without the finding.
        verify(evaluations, never()).saveAll(anyList());
    }

    @Test
    void contentTheDatabaseRejectsIsAnInvalidResponse() {
        server.expect(requestTo(URL)).andRespond(withSuccess(AnalysisClientTest.RESPONSE, MediaType.APPLICATION_JSON));
        doThrow(new DataIntegrityViolationException("duplicate key value violates move_evaluations_game_ply_uq"))
                .when(evaluations).flush();

        assertThat(service.analyzeGame(GAME_ID)).isEqualTo(Outcome.INVALID_RESPONSE_RETRY);
        assertThat(game.getAnalysisStatus()).isEqualTo(AnalysisStatus.PENDING);
        assertThat(game.getAnalysisAttempts()).isEqualTo(1);
    }

    @Test
    void serverErrorPutsTheGameBackWithoutUsingAnAttempt() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThat(service.analyzeGame(GAME_ID)).isEqualTo(Outcome.SERVICE_UNAVAILABLE);

        assertThat(game.getAnalysisStatus()).isEqualTo(AnalysisStatus.PENDING);
        assertThat(game.getAnalysisAttempts()).isZero();
    }

    @Test
    void connectionRefusedPutsTheGameBackWithoutUsingAnAttempt() {
        server.expect(requestTo(URL)).andRespond(withException(new ConnectException("Connection refused")));

        assertThat(service.analyzeGame(GAME_ID)).isEqualTo(Outcome.SERVICE_UNAVAILABLE);

        assertThat(game.getAnalysisStatus()).isEqualTo(AnalysisStatus.PENDING);
        assertThat(game.getAnalysisAttempts()).isZero();
    }

    @Test
    void anOutageOfAnyLengthNeverFailsTheGame() {
        server.expect(org.springframework.test.web.client.ExpectedCount.times(10), requestTo(URL))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        for (int run = 0; run < 10; run++) {
            assertThat(service.analyzeGame(GAME_ID)).isEqualTo(Outcome.SERVICE_UNAVAILABLE);
        }

        assertThat(game.getAnalysisStatus()).isEqualTo(AnalysisStatus.PENDING);
        assertThat(game.getAnalysisAttempts()).isZero();
    }

    @Test
    void aTimeoutCountsAsAnAttemptAndTheSecondOneFailsTheGame() {
        server.expect(requestTo(URL)).andRespond(withException(new HttpTimeoutException("request timed out")));
        server.expect(requestTo(URL)).andRespond(withException(new HttpTimeoutException("request timed out")));

        assertThat(service.analyzeGame(GAME_ID)).isEqualTo(Outcome.RETRY);
        assertThat(game.getAnalysisStatus()).isEqualTo(AnalysisStatus.PENDING);
        assertThat(game.getAnalysisAttempts()).isEqualTo(1);

        assertThat(service.analyzeGame(GAME_ID)).isEqualTo(Outcome.FAILED);
        assertThat(game.getAnalysisStatus()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(game.getAnalysisAttempts()).isEqualTo(Game.MAX_ANALYSIS_ATTEMPTS);
    }

    @Test
    void anUnreadableResponseCountsAsAnAttemptAndTheSecondOneFailsTheGame() {
        // A 2xx the backend cannot read, e.g. after the service changed its contract.
        server.expect(org.springframework.test.web.client.ExpectedCount.times(2), requestTo(URL))
                .andRespond(withSuccess("{\"engine\": [", MediaType.APPLICATION_JSON));

        assertThat(service.analyzeGame(GAME_ID)).isEqualTo(Outcome.INVALID_RESPONSE_RETRY);
        assertThat(game.getAnalysisStatus()).isEqualTo(AnalysisStatus.PENDING);
        assertThat(game.getAnalysisAttempts()).isEqualTo(1);
        verify(evaluations, never()).saveAll(anyList());

        assertThat(service.analyzeGame(GAME_ID)).isEqualTo(Outcome.INVALID_RESPONSE_FAILED);
        assertThat(game.getAnalysisStatus()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(game.getAnalysisAttempts()).isEqualTo(Game.MAX_ANALYSIS_ATTEMPTS);
    }

    @Test
    void anEmptyResponseIsHandledLikeAnUnreadableOne() {
        server.expect(requestTo(URL)).andRespond(withSuccess());

        assertThat(service.analyzeGame(GAME_ID)).isEqualTo(Outcome.INVALID_RESPONSE_RETRY);
        assertThat(game.getAnalysisStatus()).isEqualTo(AnalysisStatus.PENDING);
        assertThat(game.getAnalysisAttempts()).isEqualTo(1);
    }

    @Test
    void aGameThatIsNotClaimedIsNotSent() {
        server.expect(org.springframework.test.web.client.ExpectedCount.never(), requestTo(URL));
        when(games.claimForAnalysis(anyLong(), any())).thenReturn(false);

        assertThat(service.analyzeGame(GAME_ID)).isEqualTo(Outcome.NOT_CLAIMED);

        server.verify();
        verify(games, never()).save(any());
    }

    @Test
    void successfulAnalysisStoresEvaluationsAndFindingsWithFacts() {
        server.expect(requestTo(URL))
                .andRespond(withSuccess(AnalysisClientTest.RESPONSE, MediaType.APPLICATION_JSON));

        assertThat(service.analyzeGame(GAME_ID)).isEqualTo(Outcome.DONE);

        verify(games).claimForAnalysis(GAME_ID, STARTED);
        assertThat(game.getAnalysisStatus()).isEqualTo(AnalysisStatus.DONE);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MoveEvaluation>> storedMoves = ArgumentCaptor.forClass(List.class);
        verify(evaluations).saveAll(storedMoves.capture());
        List<MoveEvaluation> stored = storedMoves.getValue();
        assertThat(stored).extracting(MoveEvaluation::getPly).containsExactly(3, 4);
        assertThat(stored.get(0).getEngineRun().version()).isEqualTo("Stockfish 19");
        assertThat(stored.get(1).getScoreAfter()).isEqualTo(MoveEvaluation.Score.mate(0));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MistakeFinding>> storedFindings = ArgumentCaptor.forClass(List.class);
        verify(findings).saveAll(storedFindings.capture());
        assertThat(storedFindings.getValue()).hasSize(1);
        MistakeFinding finding = storedFindings.getValue().get(0);
        assertThat(finding.getMotif()).isEqualTo(Motif.HANGING_PIECE);
        assertThat(finding.getDetectorVersion()).isEqualTo("hanging-0.2");
        assertThat(finding.getFacts())
                .contains("\"movedPiece\":\"knight\"")
                .contains("\"materialLost\":3");
    }
}
