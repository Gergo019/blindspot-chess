package io.github.gergo019.blindspot.lichess;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings under {@code blindspot.lichess}.
 *
 * @param baseUrl the Lichess server
 * @param apiToken optional personal token; raises the rate limit, public games
 *        work without it
 * @param userAgent sent with every request; Lichess answers the game export
 *        with a 404 page unless the client identifies itself
 * @param username optional Lichess account to import for; created at startup
 *        if it does not exist yet
 * @param importer the background import
 */
@ConfigurationProperties("blindspot.lichess")
public record LichessProperties(String baseUrl, String apiToken, String userAgent, String username,
        Importer importer) {

    /**
     * @param batchSize at most this many games are requested per account and run
     * @param interval pause between the end of one run and the start of the next
     */
    public record Importer(boolean enabled, int batchSize, Duration interval) {
    }
}
