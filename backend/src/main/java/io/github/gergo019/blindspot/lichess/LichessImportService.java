package io.github.gergo019.blindspot.lichess;

import java.time.Clock;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.gergo019.blindspot.account.ChessAccount;
import io.github.gergo019.blindspot.account.ChessAccountRepository;
import io.github.gergo019.blindspot.account.ChessPlatform;
import io.github.gergo019.blindspot.game.GameRepository;
import io.github.gergo019.blindspot.lichess.LichessGameMapper.SkipReason;

/**
 * Imports a Lichess account's recent completed rapid games. Imported games
 * are stored as PENDING; analysing them is a separate job (#22).
 *
 * <p>Each run asks for the newest {@code batchSize} games and stores the ones
 * not seen before, so re-running imports nothing twice. The HTTP call runs
 * outside the transaction that stores the games.
 */
@Service
public class LichessImportService {

    private static final Logger log = LoggerFactory.getLogger(LichessImportService.class);

    private final LichessClient client;
    private final ChessAccountRepository accounts;
    private final GameRepository games;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final int batchSize;

    public LichessImportService(LichessClient client, ChessAccountRepository accounts, GameRepository games,
            PlatformTransactionManager transactionManager, Clock clock, LichessProperties properties) {
        if (properties.importer().batchSize() < 1) {
            throw new IllegalArgumentException("blindspot.lichess.importer.batch-size must be at least 1");
        }
        this.client = client;
        this.accounts = accounts;
        this.games = games;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.batchSize = properties.importer().batchSize();
    }

    /** What one run did with the games Lichess sent. */
    public record ImportResult(int received, int imported, Map<SkipReason, Integer> skipped) {

        public int skipped(SkipReason reason) {
            return skipped.getOrDefault(reason, 0);
        }
    }

    /**
     * @throws IllegalArgumentException if there is no Lichess account with this id
     * @throws LichessException if Lichess cannot be read; nothing is stored
     */
    public ImportResult importRecentGames(long accountId) {
        String username = tx.execute(status -> lichessAccount(accountId).getPlatformUsername());

        // A defensive cap on top of the request's max parameter.
        List<LichessGame> received = client.recentRapidGames(username, batchSize).stream().limit(batchSize).toList();

        ImportResult result = importGames(accountId, received);
        log.info("Lichess import for {}: {} received, {} imported, skipped {}", username, result.received(),
                result.imported(), result.skipped());
        return result;
    }

    /**
     * Stores games already read in the Lichess export format, with the same
     * rules as a live import: skip what is not importable or already stored,
     * store the rest as PENDING. The demo data goes through here too.
     *
     * @throws IllegalArgumentException if there is no Lichess account with this id
     */
    public ImportResult importGames(long accountId, List<LichessGame> received) {
        return tx.execute(status -> store(lichessAccount(accountId), received));
    }

    private ImportResult store(ChessAccount account, List<LichessGame> received) {
        Map<SkipReason, Integer> skipped = new EnumMap<>(SkipReason.class);
        int imported = 0;
        for (LichessGame lichessGame : received) {
            Optional<SkipReason> reason = LichessGameMapper.skipReason(lichessGame, account.getPlatformUsername());
            if (reason.isEmpty() && games.existsByAccountIdAndExternalId(account.getId(), lichessGame.id())) {
                reason = Optional.of(SkipReason.ALREADY_IMPORTED);
            }
            if (reason.isPresent()) {
                skipped.merge(reason.get(), 1, Integer::sum);
                continue;
            }
            games.save(LichessGameMapper.toGame(account, lichessGame));
            imported++;
        }
        account.markSynced(clock.instant());
        accounts.save(account);
        return new ImportResult(received.size(), imported, Map.copyOf(skipped));
    }

    private ChessAccount lichessAccount(long accountId) {
        ChessAccount account = accounts.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("No chess account with id " + accountId));
        if (account.getPlatform() != ChessPlatform.LICHESS) {
            throw new IllegalArgumentException("Account " + accountId + " is not a Lichess account");
        }
        return account;
    }
}
