package io.github.gergo019.blindspot.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestClient;

import io.github.gergo019.blindspot.TestData;
import io.github.gergo019.blindspot.game.Game;
import io.github.gergo019.blindspot.game.GameEnums.AnalysisStatus;
import io.github.gergo019.blindspot.game.GameRepository;

import tools.jackson.databind.json.JsonMapper;

/**
 * The service's lifecycle handling with a stubbed analysis service and mocked
 * repositories: no database, no Docker.
 */
class AnalysisServiceTest {

    static final long GAME_ID = 7L;

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
                mock(PlatformTransactionManager.class), JsonMapper.builder().build());

        game = TestData.game(TestData.lichessAccount(TestData.user("player")), "g1");
        when(games.findById(GAME_ID)).thenReturn(Optional.of(game));
        when(games.save(any(Game.class))).thenAnswer(call -> call.getArgument(0));
        when(evaluations.save(any(MoveEvaluation.class))).thenAnswer(call -> call.getArgument(0));
        when(findings.save(any(MistakeFinding.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void rejectedGameIsMarkedFailedAndNothingIsStored() {
        server.expect(requestTo("http://analysis-service/analyze"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"detail\":\"The PGN is not a legal game\"}"));

        assertThat(service.analyzeGame(GAME_ID)).isEqualTo(AnalysisStatus.FAILED);

        assertThat(game.getAnalysisStatus()).isEqualTo(AnalysisStatus.FAILED);
        verify(evaluations, never()).save(any());
        verify(findings, never()).save(any());
    }

    @Test
    void unexpectedResponseContentIsMarkedFailedNotLeftRunning() {
        // A classification the backend does not know: storing fails, the game must not stay RUNNING.
        server.expect(requestTo("http://analysis-service/analyze"))
                .andRespond(withSuccess(AnalysisClientTest.RESPONSE.replace("\"BLUNDER\"", "\"SUPERB\""),
                        MediaType.APPLICATION_JSON));

        assertThat(service.analyzeGame(GAME_ID)).isEqualTo(AnalysisStatus.FAILED);
        assertThat(game.getAnalysisStatus()).isEqualTo(AnalysisStatus.FAILED);
    }

    @Test
    void successfulAnalysisStoresEvaluationsAndFindingsWithFacts() {
        server.expect(requestTo("http://analysis-service/analyze"))
                .andRespond(withSuccess(AnalysisClientTest.RESPONSE, MediaType.APPLICATION_JSON));

        assertThat(service.analyzeGame(GAME_ID)).isEqualTo(AnalysisStatus.DONE);

        assertThat(game.getAnalysisStatus()).isEqualTo(AnalysisStatus.DONE);
        ArgumentCaptor<MoveEvaluation> stored = ArgumentCaptor.forClass(MoveEvaluation.class);
        verify(evaluations, org.mockito.Mockito.times(2)).save(stored.capture());
        assertThat(stored.getAllValues()).extracting(MoveEvaluation::getPly).containsExactly(3, 4);
        assertThat(stored.getAllValues().get(0).getEngineRun().version()).isEqualTo("Stockfish 19");
        assertThat(stored.getAllValues().get(1).getScoreAfter()).isEqualTo(MoveEvaluation.Score.mate(0));

        ArgumentCaptor<MistakeFinding> finding = ArgumentCaptor.forClass(MistakeFinding.class);
        verify(findings).save(finding.capture());
        assertThat(finding.getValue().getMotif()).isEqualTo(Motif.HANGING_PIECE);
        assertThat(finding.getValue().getDetectorVersion()).isEqualTo("hanging-0.2");
        assertThat(finding.getValue().getFacts())
                .contains("\"movedPiece\":\"knight\"")
                .contains("\"materialLost\":3");
    }
}
