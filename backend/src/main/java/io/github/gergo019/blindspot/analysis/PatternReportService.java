package io.github.gergo019.blindspot.analysis;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.gergo019.blindspot.account.CurrentPlayer;
import io.github.gergo019.blindspot.analysis.api.FindingResponses.MotifPattern;
import io.github.gergo019.blindspot.analysis.api.FindingResponses.PatternReport;
import io.github.gergo019.blindspot.game.GameRepository;

/**
 * The blind-spot report (#51, {@link PatternRule}): for every motif, in how many of the player's last
 * {@value PatternRule#WINDOW} eligible games it occurred, and whether that reaches the threshold. Every
 * motif is listed, also with zero games, and the sample size is always given.
 */
@Service
@Transactional(readOnly = true)
public class PatternReportService {

    private final CurrentPlayer player;
    private final GameRepository games;
    private final MistakeFindingRepository findings;

    public PatternReportService(CurrentPlayer player, GameRepository games, MistakeFindingRepository findings) {
        this.player = player;
        this.games = games;
        this.findings = findings;
    }

    public PatternReport report() {
        List<Long> window = player.userId()
                .map(userId -> games.findEligibleForReport(userId, PageRequest.of(0, PatternRule.WINDOW)))
                .orElse(List.of());
        Map<Motif, Long> counts = window.isEmpty() ? Map.of()
                : findings.countGamesByMotif(window).stream()
                        .collect(Collectors.toMap(MotifGameCount::motif, MotifGameCount::games));
        int sampleSize = window.size();
        List<MotifPattern> motifs = Arrays.stream(Motif.values())
                .map(motif -> {
                    long gamesWithFinding = counts.getOrDefault(motif, 0L);
                    return new MotifPattern(motif, gamesWithFinding, sampleSize,
                            PatternRule.isPattern(gamesWithFinding));
                })
                .toList();
        return new PatternReport(PatternRule.WINDOW, PatternRule.THRESHOLD, sampleSize, motifs);
    }
}
