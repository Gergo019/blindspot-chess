package io.github.gergo019.blindspot.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import io.github.gergo019.blindspot.account.ChessAccount;
import io.github.gergo019.blindspot.account.ChessAccountRepository;
import io.github.gergo019.blindspot.account.ChessPlatform;
import io.github.gergo019.blindspot.account.UserRepository;
import io.github.gergo019.blindspot.game.Game;
import io.github.gergo019.blindspot.game.GameEnums.AnalysisStatus;
import io.github.gergo019.blindspot.game.GameEnums.PieceColor;
import io.github.gergo019.blindspot.game.GameEnums.Speed;
import io.github.gergo019.blindspot.game.GameRepository;
import io.github.gergo019.blindspot.lichess.LichessImportJob;

/**
 * The demo profile on a fresh database: the demo file is loaded at startup
 * through the importer, every game is stored PENDING, and a second start loads
 * nothing. The analysis job is off here; analysing all demo games is not part
 * of CI.
 */
@SpringBootTest(properties = "blindspot.analysis.job.enabled=false")
@ActiveProfiles("demo")
@Testcontainers
class DemoDataIT {

    /** The number of games selected by tools/demo-data (see its README). */
    static final int DEMO_GAMES = 20;

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired DemoDataLoader loader;
    @Autowired UserRepository users;
    @Autowired ChessAccountRepository accounts;
    @Autowired GameRepository games;
    @Autowired ApplicationContext context;

    @Test
    void demoGamesAreLoadedOnceAsPendingRapidGamesOfTheDemoUser() throws IOException {
        assertThat(lines()).hasSize(DEMO_GAMES);

        ChessAccount account = accounts.findByPlatformAndPlatformUsername(ChessPlatform.LICHESS, "demo")
                .orElseThrow();
        assertThat(users.findByUsername("demo")).isPresent();
        List<Game> stored = games.findByAccountIdOrderByPlayedAtDesc(account.getId(), Pageable.unpaged());

        assertThat(stored).hasSize(DEMO_GAMES);
        assertThat(stored).extracting(Game::getExternalId).doesNotHaveDuplicates();
        assertThat(stored).extracting(Game::getUserColor).contains(PieceColor.WHITE, PieceColor.BLACK);
        assertThat(stored).allSatisfy(game -> {
            assertThat(game.getAnalysisStatus()).isEqualTo(AnalysisStatus.PENDING);
            assertThat(game.getSpeed()).isEqualTo(Speed.RAPID);
            assertThat(game.getPgn()).contains("\"demo\"").doesNotEndWith("*");
            assertThat(game.getOpponentName()).startsWith("opponent_");
        });

        // A restart: the demo user exists, so nothing is loaded again.
        loader.run(new DefaultApplicationArguments());
        assertThat(games.findByAccountIdOrderByPlayedAtDesc(account.getId(), Pageable.unpaged())).hasSize(DEMO_GAMES);
        assertThat(games.count()).isEqualTo(DEMO_GAMES);
    }

    @Test
    void liveLichessImportIsOffInTheDemoProfile() {
        assertThat(context.getBeanNamesForType(LichessImportJob.class)).isEmpty();
    }

    private static List<String> lines() throws IOException {
        try (InputStream in = DemoDataIT.class.getClassLoader().getResourceAsStream("demo/games.ndjson")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().filter(l -> !l.isBlank()).toList();
        }
    }
}
