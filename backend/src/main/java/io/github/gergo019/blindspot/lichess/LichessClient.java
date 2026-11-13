package io.github.gergo019.blindspot.lichess;

import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import tools.jackson.databind.json.JsonMapper;

/**
 * Reads a user's recent games from the Lichess game export
 * ({@code GET /api/games/user/{username}}, NDJSON).
 */
@Component
public class LichessClient {

    static final MediaType NDJSON = MediaType.parseMediaType("application/x-ndjson");

    private final RestClient restClient;
    private final JsonMapper json;

    public LichessClient(@Qualifier(LichessClientConfig.REST_CLIENT) RestClient restClient, JsonMapper json) {
        this.restClient = restClient;
        this.json = json;
    }

    /**
     * The user's most recent finished rapid games, newest first. Lichess
     * filters by speed and finished state on its side; the importer checks
     * both again, so a wrong filter here cannot let a game in progress through.
     *
     * @param max at most this many games
     * @throws LichessException if Lichess answers with an error, cannot be
     *         reached, or sends a line that is not valid JSON
     */
    public List<LichessGame> recentRapidGames(String username, int max) {
        try {
            String body = restClient.get()
                    .uri(uri -> uri.path("/api/games/user/{username}")
                            .queryParam("max", max)
                            .queryParam("perfType", "rapid")
                            .queryParam("finished", true)
                            .queryParam("ongoing", false)
                            .queryParam("moves", true)
                            .queryParam("pgnInJson", true)
                            .queryParam("tags", true)
                            .queryParam("opening", true)
                            .queryParam("clocks", false)
                            .queryParam("evals", false)
                            .queryParam("sort", "dateDesc")
                            .build(username))
                    .accept(NDJSON)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw new LichessException("Lichess answered " + res.getStatusCode().value()
                                + " for the games of " + username);
                    })
                    .body(String.class);
            return parse(body);
        } catch (LichessException e) {
            throw e;
        } catch (RestClientException | tools.jackson.core.JacksonException e) {
            throw new LichessException("Reading the games of " + username + " failed: " + e.getMessage(), e);
        }
    }

    private List<LichessGame> parse(String body) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        return body.lines()
                .filter(line -> !line.isBlank())
                .map(line -> json.readValue(line, LichessGame.class))
                .toList();
    }
}
