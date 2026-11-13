package io.github.gergo019.blindspot.lichess;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.github.gergo019.blindspot.account.ChessAccount;
import io.github.gergo019.blindspot.account.ChessAccountRepository;
import io.github.gergo019.blindspot.account.ChessPlatform;

/**
 * Imports recent games for every Lichess account, then waits
 * {@code blindspot.lichess.importer.interval} before the next run. One
 * account's failure does not stop the others.
 */
@Component
@ConditionalOnProperty(name = "blindspot.lichess.importer.enabled", havingValue = "true")
public class LichessImportJob {

    private static final Logger log = LoggerFactory.getLogger(LichessImportJob.class);

    private final ChessAccountRepository accounts;
    private final LichessImportService importService;

    public LichessImportJob(ChessAccountRepository accounts, LichessImportService importService) {
        this.accounts = accounts;
        this.importService = importService;
    }

    @Scheduled(initialDelayString = "${blindspot.lichess.importer.initial-delay:10s}",
            fixedDelayString = "${blindspot.lichess.importer.interval}")
    public void run() {
        for (ChessAccount account : accounts.findByPlatform(ChessPlatform.LICHESS)) {
            try {
                importService.importRecentGames(account.getId());
            } catch (RuntimeException e) {
                log.warn("Lichess import for account {} failed: {}", account.getId(), e.getMessage(), e);
            }
        }
    }
}
