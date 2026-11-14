package io.github.gergo019.blindspot.lichess;

import java.util.List;

import tools.jackson.databind.json.JsonMapper;

/** Newline-delimited JSON as Lichess exports games: one {@link LichessGame} per non-blank line. */
public final class LichessNdjson {

    private LichessNdjson() {
    }

    /** @throws tools.jackson.core.JacksonException if a line is not a valid game record */
    public static List<LichessGame> parse(String ndjson, JsonMapper json) {
        if (ndjson == null || ndjson.isBlank()) {
            return List.of();
        }
        return ndjson.lines()
                .filter(line -> !line.isBlank())
                .map(line -> json.readValue(line, LichessGame.class))
                .toList();
    }
}
