package io.github.gergo019.blindspot.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import io.github.gergo019.blindspot.analysis.AnalyzeApi.AnalyzeRequest;
import io.github.gergo019.blindspot.analysis.AnalyzeApi.AnalyzeResponse;
import io.github.gergo019.blindspot.analysis.AnalyzeApi.ScoreDto;

/**
 * Contract test of the HTTP mapping against a stubbed analysis service. The
 * JSON below follows analysis-service/README.md.
 */
class AnalysisClientTest {

    /** Fool's mate analysed for White, with a made-up finding to cover the mapping. */
    static final String RESPONSE = """
            {
              "engine": { "name": "Stockfish", "version": "19", "depth": 16 },
              "moves": [
                { "ply": 3, "byUser": true,
                  "fenBefore": "rnbqkbnr/pppp1ppp/8/4p3/8/5P2/PPPPP1PP/RNBQKBNR w KQkq - 0 2",
                  "moveUci": "g2g4", "moveSan": "g4", "bestMoveUci": "e2e3",
                  "scoreBefore": { "cp": -67 }, "scoreAfter": { "mate": -1 },
                  "classification": "BLUNDER" },
                { "ply": 4, "byUser": false,
                  "fenBefore": "rnbqkbnr/pppp1ppp/8/4p3/6P1/5P2/PPPPP2P/RNBQKBNR b KQkq - 0 2",
                  "moveUci": "d8h4", "moveSan": "Qh4#", "bestMoveUci": "d8h4",
                  "scoreBefore": { "mate": -1 }, "scoreAfter": { "mate": 0 },
                  "classification": "BEST" }
              ],
              "findings": [
                { "ply": 3, "motif": "HANGING_PIECE", "evidenceLineUci": "e5d4 c2c3",
                  "detectorVersion": "hanging-0.2",
                  "facts": { "piece": "knight", "square": "d4", "capturedBy": "pawn", "materialLost": 3,
                             "movedPiece": "knight", "movedFrom": "f3", "movedTo": "d4" },
                  "futureField": "ignored" }
              ]
            }
            """;

    MockRestServiceServer server;
    AnalysisClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://analysis-service");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new AnalysisClient(builder.build());
    }

    @Test
    void sendsThePgnAndColourAndMapsMovesScoresAndFindings() {
        server.expect(requestTo("http://analysis-service/analyze"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"pgn\":\"1. f3 e5 2. g4 Qh4# 0-1\",\"userColor\":\"WHITE\"}"))
                .andRespond(withSuccess(RESPONSE, MediaType.APPLICATION_JSON));

        AnalyzeResponse response = client.analyze(new AnalyzeRequest("1. f3 e5 2. g4 Qh4# 0-1", "WHITE"));

        server.verify();
        assertThat(response.engine().name()).isEqualTo("Stockfish");
        assertThat(response.engine().version()).isEqualTo("19");
        assertThat(response.engine().depth()).isEqualTo(16);

        assertThat(response.moves()).hasSize(2);
        var g4 = response.moves().get(0);
        assertThat(g4.ply()).isEqualTo(3);
        assertThat(g4.byUser()).isTrue();
        assertThat(g4.moveUci()).isEqualTo("g2g4");
        assertThat(g4.moveSan()).isEqualTo("g4");
        assertThat(g4.bestMoveUci()).isEqualTo("e2e3");
        assertThat(g4.scoreBefore()).isEqualTo(new ScoreDto(-67, null));
        assertThat(g4.scoreAfter()).isEqualTo(new ScoreDto(null, -1));
        assertThat(g4.classification()).isEqualTo("BLUNDER");
        // Mate on the board after the final move: mate 0, not a missing score.
        assertThat(response.moves().get(1).scoreAfter()).isEqualTo(new ScoreDto(null, 0));

        assertThat(response.findings()).hasSize(1);
        var finding = response.findings().get(0);
        assertThat(finding.ply()).isEqualTo(3);
        assertThat(finding.motif()).isEqualTo("HANGING_PIECE");
        assertThat(finding.evidenceLineUci()).isEqualTo("e5d4 c2c3");
        assertThat(finding.detectorVersion()).isEqualTo("hanging-0.2");
        assertThat(finding.facts().piece()).isEqualTo("knight");
        assertThat(finding.facts().square()).isEqualTo("d4");
        assertThat(finding.facts().capturedBy()).isEqualTo("pawn");
        assertThat(finding.facts().materialLost()).isEqualTo(3);
        assertThat(finding.facts().movedPiece()).isEqualTo("knight");
        assertThat(finding.facts().movedFrom()).isEqualTo("f3");
        assertThat(finding.facts().movedTo()).isEqualTo("d4");
    }

    @Test
    void responseWithoutFindingsGivesAnEmptyList() {
        server.expect(requestTo("http://analysis-service/analyze"))
                .andRespond(withSuccess("""
                        {"engine":{"name":"Stockfish","version":"19","depth":16},"moves":[]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.analyze(new AnalyzeRequest("1. e4 1-0", "WHITE")).findings()).isEmpty();
    }

    @Test
    void unprocessableGameBecomesAnAnalysisFailureWithTheReason() {
        server.expect(requestTo("http://analysis-service/analyze"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"detail\":\"Only completed games can be analysed; the result is '*'\"}"));

        assertThatThrownBy(() -> client.analyze(new AnalyzeRequest("1. e4 *", "WHITE")))
                .isInstanceOf(AnalysisFailedException.class)
                .hasMessageContaining("422")
                .hasMessageContaining("Only completed games")
                .extracting(e -> ((AnalysisFailedException) e).getStatus()).isEqualTo(422);
    }

    @Test
    void serverErrorBecomesAnAnalysisFailure() {
        server.expect(requestTo("http://analysis-service/analyze")).andRespond(withServerError());

        assertThatThrownBy(() -> client.analyze(new AnalyzeRequest("1. e4 1-0", "WHITE")))
                .isInstanceOf(AnalysisFailedException.class)
                .extracting(e -> ((AnalysisFailedException) e).getStatus()).isEqualTo(500);
    }
}
