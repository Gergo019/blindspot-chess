package io.github.gergo019.blindspot.lichess;

/**
 * One line of the NDJSON game export ({@code GET /api/games/user/{username}}
 * with {@code Accept: application/x-ndjson} and {@code pgnInJson=true}).
 * Only the fields the importer uses; Lichess adds fields over time and unknown
 * ones are ignored.
 *
 * @param createdAt epoch milliseconds
 * @param winner {@code "white"}, {@code "black"}, or null for a draw or an
 *        unfinished game
 * @param pgn the full PGN with tags, present because of {@code pgnInJson=true}
 */
public record LichessGame(String id, boolean rated, String variant, String speed, long createdAt, String status,
        Players players, String winner, Opening opening, Clock clock, String pgn) {

    public record Players(Player white, Player black) {
    }

    /** {@code user} is null for an anonymous player or the Lichess AI. */
    public record Player(LightUser user, Integer rating) {
    }

    public record LightUser(String id, String name) {
    }

    public record Opening(String eco, String name) {
    }

    /** Seconds. */
    public record Clock(int initial, int increment) {
    }
}
