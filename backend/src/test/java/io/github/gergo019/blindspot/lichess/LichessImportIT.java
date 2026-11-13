package io.github.gergo019.blindspot.lichess;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.sun.net.httpserver.HttpServer;

import io.github.gergo019.blindspot.TestData;
import io.github.gergo019.blindspot.account.ChessAccount;
import io.github.gergo019.blindspot.account.ChessAccountRepository;
import io.github.gergo019.blindspot.account.UserRepository;
import io.github.gergo019.blindspot.game.Game;
import io.github.gergo019.blindspot.game.GameEnums.AnalysisStatus;
import io.github.gergo019.blindspot.game.GameEnums.GameResult;
import io.github.gergo019.blindspot.game.GameEnums.PieceColor;
import io.github.gergo019.blindspot.game.GameRepository;
import io.github.gergo019.blindspot.lichess.LichessGameMapper.SkipReason;

/**
 * The whole import against PostgreSQL, with a local HTTP server standing in
 * for Lichess and answering with the recorded export. Never the live API.
 */
@SpringBootTest(properties = {
        "blindspot.lichess.importer.enabled=false",
        "blindspot.lichess.importer.batch-size=10",
        "blindspot.lichess.user-agent=blindspot-chess-it" })
@Testcontainers
class LichessImportIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    static HttpServer lichess;
    static final List<String> requests = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void startFakeLichess() throws IOException {
        lichess = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        lichess.createContext("/api/games/user/", exchange -> {
            requests.add(exchange.getRequestURI() + " UA=" + exchange.getRequestHeaders().getFirst("User-Agent")
                    + " Accept=" + exchange.getRequestHeaders().getFirst("Accept"));
            byte[] body = LichessFixtures.read(LichessFixtures.THIBAULT_EXPORT).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        lichess.start();
    }

    @AfterAll
    static void stopFakeLichess() {
        lichess.stop(0);
    }

    @DynamicPropertySource
    static void lichessUrl(DynamicPropertyRegistry registry) {
        registry.add("blindspot.lichess.base-url", () -> "http://127.0.0.1:" + lichess.getAddress().getPort());
    }

    @Autowired LichessImportService importService;
    @Autowired UserRepository users;
    @Autowired ChessAccountRepository accounts;
    @Autowired GameRepository games;

    @Test
    void importsFinishedRapidGamesAsPendingSkipsTheRestAndNeverImportsTwice() {
        ChessAccount account = accounts.save(TestData.lichessAccount(users.save(TestData.user("thibault"))));

        var first = importService.importRecentGames(account.getId());

        assertThat(first.received()).isEqualTo(5);
        assertThat(first.imported()).isEqualTo(3);
        assertThat(first.skipped(SkipReason.NOT_FINISHED)).isEqualTo(1);
        assertThat(first.skipped(SkipReason.NOT_RAPID)).isEqualTo(1);

        List<Game> stored = games.findByAccountIdOrderByPlayedAtDesc(account.getId(), Pageable.unpaged());
        assertThat(stored).extracting(Game::getExternalId).containsExactly(LichessFixtures.RAPID_WIN_AS_BLACK,
                LichessFixtures.RAPID_WIN_AS_WHITE, LichessFixtures.RAPID_ON_TIME);
        assertThat(stored).extracting(Game::getExternalId)
                .doesNotContain(LichessFixtures.IN_PROGRESS, LichessFixtures.BLITZ);
        assertThat(stored).allSatisfy(g -> {
            assertThat(g.getAnalysisStatus()).isEqualTo(AnalysisStatus.PENDING);
            assertThat(g.getPgn()).doesNotEndWith("*");
        });
        assertThat(stored).extracting(Game::getUserColor)
                .containsExactly(PieceColor.BLACK, PieceColor.WHITE, PieceColor.BLACK);
        assertThat(stored).extracting(Game::getResult).containsOnly(GameResult.WIN);
        assertThat(accounts.findById(account.getId()).orElseThrow().getLastSyncedAt()).isNotNull();

        // The same export again: everything is already there.
        var second = importService.importRecentGames(account.getId());

        assertThat(second.imported()).isZero();
        assertThat(second.skipped(SkipReason.ALREADY_IMPORTED)).isEqualTo(3);
        assertThat(games.findByAccountIdOrderByPlayedAtDesc(account.getId(), Pageable.unpaged())).hasSize(3);

        assertThat(requests).hasSize(2).allSatisfy(r -> assertThat(r)
                .startsWith("/api/games/user/thibault?max=10&perfType=rapid&finished=true&ongoing=false")
                .contains("UA=blindspot-chess-it")
                .contains("Accept=application/x-ndjson"));
    }
}
