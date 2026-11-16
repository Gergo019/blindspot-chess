package io.github.gergo019.blindspot.demo;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.github.gergo019.blindspot.account.ChessAccount;
import io.github.gergo019.blindspot.account.ChessAccountRepository;
import io.github.gergo019.blindspot.account.ChessPlatform;
import io.github.gergo019.blindspot.account.User;
import io.github.gergo019.blindspot.account.UserRepository;
import io.github.gergo019.blindspot.lichess.LichessGame;
import io.github.gergo019.blindspot.lichess.LichessImportService;
import io.github.gergo019.blindspot.lichess.LichessNdjson;

import tools.jackson.databind.json.JsonMapper;

/**
 * With the {@code demo} profile: on a database without the user {@code demo},
 * creates it with a Lichess account and imports {@code demo/games.ndjson}
 * (real games from the Lichess open database, selected by
 * {@code tools/demo-data}) through the normal importer. The games are stored
 * PENDING, and the normal background analysis takes it from there.
 *
 * <p>All in one transaction: either the user and all games are stored, or
 * nothing, and the next start tries again.
 */
@Component
@Profile("demo")
public class DemoDataLoader implements ApplicationRunner {

    public static final String DEMO_USER = "demo";
    static final String GAMES = "demo/games.ndjson";

    private static final Logger log = LoggerFactory.getLogger(DemoDataLoader.class);

    private final UserRepository users;
    private final ChessAccountRepository accounts;
    private final LichessImportService importService;
    private final JsonMapper json;

    public DemoDataLoader(UserRepository users, ChessAccountRepository accounts, LichessImportService importService,
            JsonMapper json) {
        this.users = users;
        this.accounts = accounts;
        this.importService = importService;
        this.json = json;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.findByUsername(DEMO_USER).isPresent()) {
            log.info("Demo data: user '{}' exists, nothing loaded", DEMO_USER);
            return;
        }
        User user = users.save(new User(DEMO_USER));
        ChessAccount account = accounts.save(new ChessAccount(user, ChessPlatform.LICHESS, DEMO_USER));
        List<LichessGame> games = LichessNdjson.parse(read(GAMES), json);

        var result = importService.importGames(account.getId(), games);

        if (result.imported() != games.size()) {
            // Every demo game was selected to be importable; anything else is a broken file.
            throw new IllegalStateException("Demo data: only " + result.imported() + " of " + games.size()
                    + " games importable, skipped " + result.skipped());
        }
        log.info("Demo data: user '{}' with {} games from the Lichess open database, PENDING analysis", DEMO_USER,
                result.imported());
    }

    private static String read(String resource) {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Demo data: cannot read " + resource, e);
        }
    }
}
