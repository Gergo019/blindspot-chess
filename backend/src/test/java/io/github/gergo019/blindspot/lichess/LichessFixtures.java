package io.github.gergo019.blindspot.lichess;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import tools.jackson.databind.json.JsonMapper;

/**
 * Recorded Lichess responses (see src/test/resources/lichess/README.md).
 */
final class LichessFixtures {

    /** {@code GET /api/games/user/thibault}: three finished rapid games, one blitz game, one rapid game in progress. */
    static final String THIBAULT_EXPORT = "lichess/thibault-export.ndjson";
    static final String USERNAME = "thibault";

    static final String RAPID_WIN_AS_BLACK = "ZlkPN7gH";
    static final String RAPID_WIN_AS_WHITE = "k8OVq8Y5";
    static final String RAPID_ON_TIME = "aDN8u0zd";
    static final String BLITZ = "v31fmkvX";
    static final String IN_PROGRESS = "IVGlNazQ";

    private LichessFixtures() {
    }

    static String read(String resource) {
        try (InputStream in = LichessFixtures.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("No test resource " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<LichessGame> games(String resource) {
        JsonMapper json = JsonMapper.builder().build();
        return read(resource).lines().filter(l -> !l.isBlank()).map(l -> json.readValue(l, LichessGame.class))
                .toList();
    }

    static LichessGame game(String id) {
        return games(THIBAULT_EXPORT).stream().filter(g -> g.id().equals(id)).findFirst().orElseThrow();
    }

    static LichessProperties properties(String baseUrl, String token, int batchSize) {
        return new LichessProperties(baseUrl, token, "blindspot-chess-test", "",
                new LichessProperties.Importer(false, batchSize, java.time.Duration.ofHours(1)));
    }
}
