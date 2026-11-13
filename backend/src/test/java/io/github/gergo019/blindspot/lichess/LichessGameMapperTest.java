package io.github.gergo019.blindspot.lichess;

import static io.github.gergo019.blindspot.lichess.LichessFixtures.USERNAME;
import static io.github.gergo019.blindspot.lichess.LichessFixtures.game;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import io.github.gergo019.blindspot.TestData;
import io.github.gergo019.blindspot.account.ChessAccount;
import io.github.gergo019.blindspot.game.Game;
import io.github.gergo019.blindspot.game.GameEnums.AnalysisStatus;
import io.github.gergo019.blindspot.game.GameEnums.GameResult;
import io.github.gergo019.blindspot.game.GameEnums.PieceColor;
import io.github.gergo019.blindspot.game.GameEnums.Speed;
import io.github.gergo019.blindspot.lichess.LichessGameMapper.SkipReason;

class LichessGameMapperTest {

    @Test
    void finishedRapidStandardGamesOfTheUserAreImportable() {
        assertThat(LichessGameMapper.skipReason(game(LichessFixtures.RAPID_WIN_AS_BLACK), USERNAME)).isEmpty();
        assertThat(LichessGameMapper.skipReason(game(LichessFixtures.RAPID_WIN_AS_WHITE), USERNAME)).isEmpty();
        assertThat(LichessGameMapper.skipReason(game(LichessFixtures.RAPID_ON_TIME), USERNAME)).isEmpty();
    }

    @Test
    void gameInProgressIsSkipped() {
        assertThat(LichessGameMapper.skipReason(game(LichessFixtures.IN_PROGRESS), USERNAME))
                .contains(SkipReason.NOT_FINISHED);
    }

    @Test
    void everyUnfinishedStatusIsSkippedEvenForAnOtherwiseImportableGame() {
        LichessGame finished = game(LichessFixtures.RAPID_WIN_AS_WHITE);
        for (String status : LichessGameMapper.UNFINISHED) {
            assertThat(LichessGameMapper.skipReason(withStatus(finished, status), USERNAME))
                    .as(status).contains(SkipReason.NOT_FINISHED);
        }
    }

    @Test
    void nonRapidGameIsSkipped() {
        assertThat(LichessGameMapper.skipReason(game(LichessFixtures.BLITZ), USERNAME)).contains(SkipReason.NOT_RAPID);
    }

    @Test
    void variantsOtherGamesAndMissingPgnAreSkipped() {
        LichessGame g = game(LichessFixtures.RAPID_WIN_AS_WHITE);
        assertThat(LichessGameMapper.skipReason(new LichessGame(g.id(), g.rated(), "chess960", g.speed(),
                g.createdAt(), g.status(), g.players(), g.winner(), g.opening(), g.clock(), g.pgn()), USERNAME))
                .contains(SkipReason.NOT_STANDARD);
        assertThat(LichessGameMapper.skipReason(g, "someone-else")).contains(SkipReason.NOT_USERS_GAME);
        assertThat(LichessGameMapper.skipReason(new LichessGame(g.id(), g.rated(), g.variant(), g.speed(),
                g.createdAt(), g.status(), g.players(), g.winner(), g.opening(), g.clock(), null), USERNAME))
                .contains(SkipReason.NO_PGN);
    }

    @Test
    void userIsMatchedCaseInsensitively() {
        assertThat(LichessGameMapper.userColor(game(LichessFixtures.RAPID_WIN_AS_WHITE), "Thibault"))
                .contains(PieceColor.WHITE);
    }

    @Test
    void resultIsSeenFromTheUsersSide() {
        assertThat(LichessGameMapper.result("white", PieceColor.WHITE)).isEqualTo(GameResult.WIN);
        assertThat(LichessGameMapper.result("black", PieceColor.WHITE)).isEqualTo(GameResult.LOSS);
        assertThat(LichessGameMapper.result("white", PieceColor.BLACK)).isEqualTo(GameResult.LOSS);
        assertThat(LichessGameMapper.result(null, PieceColor.BLACK)).isEqualTo(GameResult.DRAW);
    }

    @Test
    void recordedGameBecomesAPendingGame() {
        ChessAccount account = TestData.lichessAccount(TestData.user(USERNAME));

        Game game = LichessGameMapper.toGame(account, game(LichessFixtures.RAPID_WIN_AS_BLACK));

        assertThat(game.getExternalId()).isEqualTo("ZlkPN7gH");
        assertThat(game.getPlayedAt()).isEqualTo(Instant.ofEpochMilli(1794566295497L));
        assertThat(game.getSpeed()).isEqualTo(Speed.RAPID);
        assertThat(game.isRated()).isTrue();
        assertThat(game.getUserColor()).isEqualTo(PieceColor.BLACK);
        assertThat(game.getResult()).isEqualTo(GameResult.WIN);
        assertThat(game.getUserRating()).isEqualTo(1836);
        assertThat(game.getOpponentName()).isEqualTo("alhamaoee");
        assertThat(game.getOpponentRating()).isEqualTo(1960);
        assertThat(game.getOpeningEco()).isEqualTo("B32");
        assertThat(game.getOpeningName()).isEqualTo("Sicilian Defense: Open");
        assertThat(game.getTimeControl()).isEqualTo("600+0");
        assertThat(game.getPgn()).startsWith("[Event \"rated rapid game\"]").endsWith("Re1# 0-1");
        assertThat(game.getAnalysisStatus()).isEqualTo(AnalysisStatus.PENDING);
    }

    private static LichessGame withStatus(LichessGame g, String status) {
        return new LichessGame(g.id(), g.rated(), g.variant(), g.speed(), g.createdAt(), status, g.players(),
                g.winner(), g.opening(), g.clock(), g.pgn());
    }
}
