package io.github.gergo019.blindspot.lichess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import io.github.gergo019.blindspot.TestData;
import io.github.gergo019.blindspot.account.ChessAccount;
import io.github.gergo019.blindspot.account.ChessAccountRepository;
import io.github.gergo019.blindspot.game.Game;
import io.github.gergo019.blindspot.game.GameRepository;
import io.github.gergo019.blindspot.lichess.LichessGameMapper.SkipReason;

/** Batch limit and failure handling, with mocked repositories: no database, no HTTP. */
class LichessImportServiceTest {

    static final long ACCOUNT_ID = 3L;
    static final Instant NOW = Instant.parse("2026-11-13T15:00:00Z");

    LichessClient client = mock(LichessClient.class);
    ChessAccountRepository accounts = mock(ChessAccountRepository.class);
    GameRepository games = mock(GameRepository.class);
    ChessAccount account = TestData.lichessAccount(TestData.user(LichessFixtures.USERNAME));

    @BeforeEach
    void setUp() {
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(games.save(any(Game.class))).thenAnswer(call -> call.getArgument(0));
    }

    LichessImportService service(int batchSize) {
        return new LichessImportService(client, accounts, games, mock(PlatformTransactionManager.class),
                Clock.fixed(NOW, ZoneOffset.UTC), LichessFixtures.properties("https://lichess.test", "", batchSize));
    }

    @Test
    void neverStoresMoreThanTheBatchSizeEvenIfLichessSendsMore() {
        List<LichessGame> all = LichessFixtures.games(LichessFixtures.THIBAULT_EXPORT);
        when(client.recentRapidGames(anyString(), eq(2))).thenReturn(all);

        var result = service(2).importRecentGames(ACCOUNT_ID);

        // The first two lines are a rapid game and a blitz game.
        assertThat(result.received()).isEqualTo(2);
        assertThat(result.imported()).isEqualTo(1);
        assertThat(result.skipped(SkipReason.NOT_RAPID)).isEqualTo(1);
        verify(games, times(1)).save(any(Game.class));
        assertThat(account.getLastSyncedAt()).isEqualTo(NOW);
    }

    @Test
    void lichessFailureStoresNothingAndDoesNotMarkTheAccountSynced() {
        when(client.recentRapidGames(anyString(), anyInt())).thenThrow(new LichessException("Lichess answered 429"));

        assertThatThrownBy(() -> service(20).importRecentGames(ACCOUNT_ID)).isInstanceOf(LichessException.class);

        verify(games, never()).save(any());
        verify(games, never()).existsByAccountIdAndExternalId(anyLong(), anyString());
        assertThat(account.getLastSyncedAt()).isNull();
    }

    @Test
    void batchSizeBelowOneIsRejected() {
        assertThatThrownBy(() -> service(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
