package io.github.gergo019.blindspot.lichess;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import io.github.gergo019.blindspot.account.ChessAccount;
import io.github.gergo019.blindspot.game.Game;
import io.github.gergo019.blindspot.game.GameEnums.GameResult;
import io.github.gergo019.blindspot.game.GameEnums.PieceColor;
import io.github.gergo019.blindspot.game.GameEnums.Speed;
import io.github.gergo019.blindspot.lichess.LichessGame.Player;

/**
 * Decides whether a Lichess game may be imported and turns it into a
 * {@link Game}. Pure functions, so the rules are tested without Spring.
 */
final class LichessGameMapper {

    /** Why a game from the export is not imported. */
    enum SkipReason {
        /** Product rule 1: only completed games. */
        NOT_FINISHED,
        NOT_RAPID,
        /** Chess960, From Position and other variants: the analysis service handles standard chess only. */
        NOT_STANDARD,
        /** The account's user is not one of the two players. */
        NOT_USERS_GAME,
        /** No PGN in the record, so nothing to analyse. */
        NO_PGN,
        ALREADY_IMPORTED
    }

    /**
     * Lichess statuses (GameStatusName) of games that did not finish. Anything
     * else ended on the board, by resignation, on time, by agreement or by
     * adjudication. {@code unknownFinish} has no reliable result, so it is
     * treated as unfinished too.
     */
    static final Set<String> UNFINISHED = Set.of("created", "started", "aborted", "noStart", "unknownFinish");

    private static final int MAX_OPENING_NAME = 255;
    private static final int MAX_NAME = 100;

    private LichessGameMapper() {
    }

    /** Checks in a fixed order, so a game in progress is always reported as NOT_FINISHED first. */
    static Optional<SkipReason> skipReason(LichessGame game, String username) {
        if (game.status() == null || UNFINISHED.contains(game.status())) {
            return Optional.of(SkipReason.NOT_FINISHED);
        }
        if (!"rapid".equals(game.speed())) {
            return Optional.of(SkipReason.NOT_RAPID);
        }
        if (!"standard".equals(game.variant())) {
            return Optional.of(SkipReason.NOT_STANDARD);
        }
        if (userColor(game, username).isEmpty()) {
            return Optional.of(SkipReason.NOT_USERS_GAME);
        }
        if (game.pgn() == null || game.pgn().isBlank()) {
            return Optional.of(SkipReason.NO_PGN);
        }
        return Optional.empty();
    }

    /** Requires a game for which {@link #skipReason} is empty. */
    static Game toGame(ChessAccount account, LichessGame lg) {
        PieceColor color = userColor(lg, account.getPlatformUsername())
                .orElseThrow(() -> new IllegalArgumentException("Not a game of " + account.getPlatformUsername()));
        Player user = color == PieceColor.WHITE ? lg.players().white() : lg.players().black();
        Player opponent = color == PieceColor.WHITE ? lg.players().black() : lg.players().white();

        Game game = new Game(account, lg.id(), Instant.ofEpochMilli(lg.createdAt()), Speed.RAPID, lg.rated(), color,
                result(lg.winner(), color), lg.pgn().strip());
        game.setUserRating(user.rating());
        game.describeOpponent(opponent.user() == null ? null : truncate(opponent.user().name(), MAX_NAME),
                opponent.rating());
        if (lg.opening() != null) {
            game.describeOpening(lg.opening().eco(), truncate(lg.opening().name(), MAX_OPENING_NAME));
        }
        if (lg.clock() != null) {
            game.setTimeControl(lg.clock().initial() + "+" + lg.clock().increment());
        }
        return game;
    }

    /** Lichess user ids are the lower-cased user names. */
    static Optional<PieceColor> userColor(LichessGame game, String username) {
        if (game.players() == null) {
            return Optional.empty();
        }
        if (isUser(game.players().white(), username)) {
            return Optional.of(PieceColor.WHITE);
        }
        if (isUser(game.players().black(), username)) {
            return Optional.of(PieceColor.BLACK);
        }
        return Optional.empty();
    }

    static GameResult result(String winner, PieceColor userColor) {
        if (winner == null) {
            return GameResult.DRAW;
        }
        return winner.equalsIgnoreCase(userColor.name()) ? GameResult.WIN : GameResult.LOSS;
    }

    private static boolean isUser(Player player, String username) {
        return player != null && player.user() != null && player.user().id() != null
                && player.user().id().equalsIgnoreCase(username);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
