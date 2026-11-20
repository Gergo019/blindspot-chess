package io.github.gergo019.blindspot.account;

import java.util.Locale;
import java.util.Optional;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * The player the read API serves, until there is sign-up (#51): the one configured account.
 *
 * <ul>
 * <li>With the {@code demo} profile: the demo user ({@code demo}), whose games the demo loader imports.</li>
 * <li>Otherwise: {@code blindspot.lichess.username} ({@code LICHESS_USERNAME}), lower-cased like the
 * account the Lichess setup creates.</li>
 * </ul>
 *
 * Queries take the returned user id, never a user name, so a signed-in user's id can replace this
 * class without changing them. Empty when no player is configured or not created yet; the API then
 * shows an empty report rather than an error.
 */
@Component
public class CurrentPlayer {

    static final String DEMO_USER = "demo";

    private final UserRepository users;
    private final Environment environment;

    public CurrentPlayer(UserRepository users, Environment environment) {
        this.users = users;
        this.environment = environment;
    }

    public Optional<Long> userId() {
        return username().flatMap(users::findByUsername).map(User::getId);
    }

    Optional<String> username() {
        if (environment.acceptsProfiles(Profiles.of("demo"))) {
            return Optional.of(DEMO_USER);
        }
        String configured = environment.getProperty("blindspot.lichess.username", "").strip();
        return configured.isEmpty() ? Optional.empty() : Optional.of(configured.toLowerCase(Locale.ROOT));
    }
}
