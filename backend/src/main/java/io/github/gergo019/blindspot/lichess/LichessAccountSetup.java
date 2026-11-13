package io.github.gergo019.blindspot.lichess;

import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.github.gergo019.blindspot.account.ChessAccount;
import io.github.gergo019.blindspot.account.ChessAccountRepository;
import io.github.gergo019.blindspot.account.ChessPlatform;
import io.github.gergo019.blindspot.account.User;
import io.github.gergo019.blindspot.account.UserRepository;

/**
 * Until there is sign-up, {@code LICHESS_USERNAME} names the one Lichess
 * account to import for. At startup it gets a user and a chess account if it
 * has none; with the variable unset, nothing happens.
 */
@Component
public class LichessAccountSetup implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LichessAccountSetup.class);

    private final LichessProperties properties;
    private final UserRepository users;
    private final ChessAccountRepository accounts;

    public LichessAccountSetup(LichessProperties properties, UserRepository users, ChessAccountRepository accounts) {
        this.properties = properties;
        this.users = users;
        this.accounts = accounts;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String username = properties.username() == null ? "" : properties.username().strip();
        if (username.isEmpty()) {
            return;
        }
        // Lichess user names are case-insensitive; the lower-cased name is the user id.
        String id = username.toLowerCase(Locale.ROOT);
        if (accounts.findByPlatformAndPlatformUsername(ChessPlatform.LICHESS, id).isPresent()) {
            return;
        }
        User user = users.findByUsername(id).orElseGet(() -> users.save(new User(id)));
        accounts.save(new ChessAccount(user, ChessPlatform.LICHESS, id));
        log.info("Created Lichess account {} for importing", id);
    }
}
