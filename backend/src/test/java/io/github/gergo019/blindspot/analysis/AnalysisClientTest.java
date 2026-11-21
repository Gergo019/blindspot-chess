package io.github.gergo019.blindspot.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import io.github.gergo019.blindspot.analysis.AnalysisFailedException.Kind;
import io.github.gergo019.blindspot.analysis.AnalyzeApi.AnalyzeRequest;
import io.github.gergo019.blindspot.analysis.AnalyzeApi.AnalyzeResponse;
import io.github.gergo019.blindspot.analysis.AnalyzeApi.ScoreDto;

import com.sun.net.httpserver.HttpServer;

/**
 * Contract test of the HTTP mapping against a stubbed analysis service. The
 * JSON below follows analysis-service/README.md.
 */
class AnalysisClientTest {

    /** Fool's mate analysed for White, with a made-up finding to cover the mapping. */
    static final String RESPONSE = """
            {
              "engine": { "name": "Stockfish", "version": "19", "depth": 16, "threads": 1, "hashMb": 64 },
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
                        {"engine":{"name":"Stockfish","version":"19","depth":16,"threads":1,"hashMb":64},"moves":[]}
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
                .extracting(e -> ((AnalysisFailedException) e).getStatus(), e -> ((AnalysisFailedException) e).getKind())
                .containsExactly(422, Kind.REJECTED);
    }

    @Test
    void serverErrorMeansTheServiceIsUnavailable() {
        server.expect(requestTo("http://analysis-service/analyze")).andRespond(withServerError());

        assertThatThrownBy(() -> client.analyze(new AnalyzeRequest("1. e4 1-0", "WHITE")))
                .isInstanceOf(AnalysisFailedException.class)
                .extracting(e -> ((AnalysisFailedException) e).getStatus(), e -> ((AnalysisFailedException) e).getKind())
                .containsExactly(500, Kind.UNAVAILABLE);
    }

    @Test
    void onlyA422IsTheGamesFault() {
        assertThat(AnalysisClient.kindOf(422)).isEqualTo(Kind.REJECTED);
        for (int status : new int[] { 400, 404, 405, 413, 500, 502, 503, 504 }) {
            assertThat(AnalysisClient.kindOf(status)).as("HTTP %d", status).isEqualTo(Kind.UNAVAILABLE);
        }
    }

    // ---- POST /judge (#36, #78) ----

    static final String JUDGE_RESPONSE = """
            {
              "engine": { "name": "Stockfish", "version": "19", "depth": 16, "threads": 1, "hashMb": 64 },
              "fen": "6k1/5ppp/8/8/8/8/5PPP/R5K1 w - - 0 1",
              "sideToMove": "WHITE",
              "acceptBelow": 0.1,
              "verdicts": [
                { "uci": "a1a8", "san": "Ra8#", "score": { "mate": 1 }, "drop": 0.0, "accepted": true,
                  "refutationUci": "" },
                { "uci": "a1a2", "san": "Ra2", "score": { "cp": 512 }, "drop": 0.0918, "accepted": true,
                  "refutationUci": "g8f8 a2a8" }
              ],
              "futureField": "ignored"
            }
            """;

    @Test
    void judgeSendsTheFenAndMapsEveryVerdict() {
        server.expect(requestTo("http://analysis-service/judge"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"fen\":\"6k1/5ppp/8/8/8/8/5PPP/R5K1 w - - 0 1\"}"))
                .andRespond(withSuccess(JUDGE_RESPONSE, MediaType.APPLICATION_JSON));

        var response = client.judge(new AnalyzeApi.JudgeRequest("6k1/5ppp/8/8/8/8/5PPP/R5K1 w - - 0 1"));

        server.verify();
        assertThat(response.engine().depth()).isEqualTo(16);
        assertThat(response.acceptBelow()).isEqualByComparingTo("0.1");
        assertThat(response.verdicts()).hasSize(2);
        var mate = response.verdicts().get(0);
        assertThat(mate.uci()).isEqualTo("a1a8");
        assertThat(mate.san()).isEqualTo("Ra8#");
        assertThat(mate.score()).isEqualTo(new ScoreDto(null, 1));
        assertThat(mate.refutationUci()).isEmpty();
        var rook = response.verdicts().get(1);
        assertThat(rook.drop()).isEqualByComparingTo("0.0918");
        assertThat(rook.accepted()).isTrue();
        assertThat(rook.refutationUci()).isEqualTo("g8f8 a2a8");
    }

    @Test
    void judgeOverItsTimeBudgetIsABudgetFailureNotAnOutage() {
        server.expect(requestTo("http://analysis-service/judge"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"detail":{"reason":"JUDGE_BUDGET_EXCEEDED","message":"Judging took longer than \
                                the budget of 60 s: 61.2 s after 30 of 33 moves","budgetSeconds":60.0,\
                                "elapsedSeconds":61.2,"movesJudged":30,"legalMoves":33}}"""));

        assertThatThrownBy(() -> client.judge(new AnalyzeApi.JudgeRequest("fen")))
                .isInstanceOf(AnalysisFailedException.class)
                .hasMessageContaining("/judge")
                .extracting(e -> ((AnalysisFailedException) e).getStatus(), e -> ((AnalysisFailedException) e).getKind())
                .containsExactly(503, Kind.BUDGET_EXCEEDED);
    }

    @Test
    void onlyThe503WithTheBudgetReasonIsABudgetFailure() {
        String budget = "{\"detail\":{\"reason\":\"JUDGE_BUDGET_EXCEEDED\"}}";
        assertThat(AnalysisClient.kindOf(503, budget)).isEqualTo(Kind.BUDGET_EXCEEDED);
        // Another status with the same body, or a 503 that only mentions the words, is not.
        assertThat(AnalysisClient.kindOf(500, budget)).isEqualTo(Kind.UNAVAILABLE);
        assertThat(AnalysisClient.kindOf(503, "{\"detail\":\"Could not start Stockfish\"}"))
                .isEqualTo(Kind.UNAVAILABLE);
        assertThat(AnalysisClient.kindOf(503, "{\"detail\":\"JUDGE_BUDGET_EXCEEDED\"}")).isEqualTo(Kind.UNAVAILABLE);
        assertThat(AnalysisClient.kindOf(503, "<html>Service Unavailable</html>")).isEqualTo(Kind.UNAVAILABLE);
        assertThat(AnalysisClient.kindOf(503, "")).isEqualTo(Kind.UNAVAILABLE);
        assertThat(AnalysisClient.kindOf(422, "{\"detail\":\"Not a legal position\"}")).isEqualTo(Kind.REJECTED);
    }

    @Test
    void unreadableBodyIsAnInvalidResponse() {
        server.expect(requestTo("http://analysis-service/analyze"))
                .andRespond(withSuccess("{\"engine\": [", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.analyze(new AnalyzeRequest("1. e4 1-0", "WHITE")))
                .isInstanceOf(AnalysisFailedException.class)
                .extracting(e -> ((AnalysisFailedException) e).getKind()).isEqualTo(Kind.INVALID_RESPONSE);
    }

    @Test
    void timeoutsAreToldApart() {
        assertThat(AnalysisClient.isReadTimeout(new ResourceAccessException("x",
                new HttpTimeoutException("request timed out")))).isTrue();
        assertThat(AnalysisClient.isReadTimeout(new ResourceAccessException("x",
                new HttpConnectTimeoutException("connect timed out")))).isFalse();
        assertThat(AnalysisClient.isReadTimeout(new ResourceAccessException("x",
                new ConnectException("Connection refused")))).isFalse();
    }

    // --- The real HTTP client (AnalysisClientConfig) against real failures, no mocks ---

    @Test
    void connectionRefusedMeansTheServiceIsUnavailable() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        AnalysisClient real = realClient("http://127.0.0.1:" + closedPort, Duration.ofSeconds(5));

        assertThatThrownBy(() -> real.analyze(new AnalyzeRequest("1. e4 1-0", "WHITE")))
                .isInstanceOf(AnalysisFailedException.class)
                .extracting(e -> ((AnalysisFailedException) e).getKind()).isEqualTo(Kind.UNAVAILABLE);
    }

    @Test
    void unknownHostMeansTheServiceIsUnavailable() {
        // .invalid never resolves (RFC 2606).
        AnalysisClient real = realClient("http://analysis-service.invalid:8000", Duration.ofSeconds(5));

        assertThatThrownBy(() -> real.analyze(new AnalyzeRequest("1. e4 1-0", "WHITE")))
                .isInstanceOf(AnalysisFailedException.class)
                .extracting(e -> ((AnalysisFailedException) e).getKind()).isEqualTo(Kind.UNAVAILABLE);
    }

    @Test
    void noAnswerWithinTheReadTimeoutIsATimeout() throws IOException {
        HttpServer slow = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch release = new CountDownLatch(1);
        slow.createContext("/analyze", exchange -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        slow.start();
        try {
            AnalysisClient real = realClient("http://127.0.0.1:" + slow.getAddress().getPort(),
                    Duration.ofMillis(300));

            assertThatThrownBy(() -> real.analyze(new AnalyzeRequest("1. e4 1-0", "WHITE")))
                    .isInstanceOf(AnalysisFailedException.class)
                    .extracting(e -> ((AnalysisFailedException) e).getKind()).isEqualTo(Kind.TIMEOUT);
        } finally {
            release.countDown();
            slow.stop(0);
        }
    }

    private static AnalysisClient realClient(String baseUrl, Duration readTimeout) {
        return new AnalysisClient(new AnalysisClientConfig().analysisRestClient(RestClient.builder(), baseUrl,
                readTimeout));
    }
}
