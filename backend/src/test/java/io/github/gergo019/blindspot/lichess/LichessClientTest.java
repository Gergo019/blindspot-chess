package io.github.gergo019.blindspot.lichess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.json.JsonMapper;

/** The request Lichess gets and the parsing of a recorded NDJSON export. */
class LichessClientTest {

    MockRestServiceServer server;

    LichessClient client(String token) {
        RestClient.Builder builder = LichessClientConfig.configure(RestClient.builder(),
                LichessFixtures.properties("https://lichess.test", token, 20));
        server = MockRestServiceServer.bindTo(builder).build();
        return new LichessClient(builder.build(), JsonMapper.builder().build());
    }

    @Test
    void asksForFinishedRapidGamesAsNdjsonAndParsesEveryLine() {
        LichessClient client = client("");
        server.expect(once(), requestTo(Matchers.startsWith("https://lichess.test/api/games/user/thibault?")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("max", "20"))
                .andExpect(queryParam("perfType", "rapid"))
                .andExpect(queryParam("finished", "true"))
                .andExpect(queryParam("ongoing", "false"))
                .andExpect(queryParam("pgnInJson", "true"))
                .andExpect(header(HttpHeaders.ACCEPT, "application/x-ndjson"))
                .andExpect(header(HttpHeaders.USER_AGENT, "blindspot-chess-test"))
                .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                .andRespond(withSuccess(LichessFixtures.read(LichessFixtures.THIBAULT_EXPORT), LichessClient.NDJSON));

        List<LichessGame> games = client.recentRapidGames("thibault", 20);

        server.verify();
        assertThat(games).extracting(LichessGame::id).containsExactly(LichessFixtures.RAPID_WIN_AS_BLACK,
                LichessFixtures.BLITZ, LichessFixtures.RAPID_WIN_AS_WHITE, LichessFixtures.IN_PROGRESS,
                LichessFixtures.RAPID_ON_TIME);
        LichessGame first = games.get(0);
        assertThat(first.speed()).isEqualTo("rapid");
        assertThat(first.variant()).isEqualTo("standard");
        assertThat(first.status()).isEqualTo("mate");
        assertThat(first.winner()).isEqualTo("black");
        assertThat(first.players().black().user().id()).isEqualTo("thibault");
        assertThat(first.clock().initial()).isEqualTo(600);
        assertThat(first.pgn()).contains("[GameId \"ZlkPN7gH\"]");
        assertThat(games.get(3).status()).isEqualTo("started");
        assertThat(games.get(3).winner()).isNull();
    }

    @Test
    void sendsTheTokenWhenOneIsConfigured() {
        LichessClient client = client(" lip_secret ");
        server.expect(requestTo(Matchers.startsWith("https://lichess.test/api/games/user/thibault")))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer lip_secret"))
                .andRespond(withSuccess("", LichessClient.NDJSON));

        assertThat(client.recentRapidGames("thibault", 5)).isEmpty();
        server.verify();
    }

    @Test
    void errorStatusBecomesALichessException() {
        LichessClient client = client("");
        server.expect(requestTo(Matchers.startsWith("https://lichess.test/api/games/user/nobody")))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.recentRapidGames("nobody", 5))
                .isInstanceOf(LichessException.class).hasMessageContaining("404");
    }

    @Test
    void lineThatIsNotJsonBecomesALichessException() {
        LichessClient client = client("");
        server.expect(requestTo(Matchers.startsWith("https://lichess.test/api/games/user/thibault")))
                .andRespond(withSuccess("<!DOCTYPE html><html>Page not found</html>", LichessClient.NDJSON));

        assertThatThrownBy(() -> client.recentRapidGames("thibault", 5)).isInstanceOf(LichessException.class);
    }
}
