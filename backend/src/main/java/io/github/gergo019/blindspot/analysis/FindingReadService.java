package io.github.gergo019.blindspot.analysis;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.gergo019.blindspot.account.CurrentPlayer;
import io.github.gergo019.blindspot.analysis.MoveEvaluation.EngineRun;
import io.github.gergo019.blindspot.analysis.api.FindingResponses.Engine;
import io.github.gergo019.blindspot.analysis.api.FindingResponses.Explanation;
import io.github.gergo019.blindspot.analysis.api.FindingResponses.Facts;
import io.github.gergo019.blindspot.analysis.api.FindingResponses.FindingDetail;
import io.github.gergo019.blindspot.analysis.api.FindingResponses.FindingSummary;
import io.github.gergo019.blindspot.analysis.api.FindingResponses.GameSummary;
import io.github.gergo019.blindspot.analysis.api.FindingResponses.Move;
import io.github.gergo019.blindspot.common.api.NotFoundException;
import io.github.gergo019.blindspot.common.api.PageResponse;
import io.github.gergo019.blindspot.game.Game;
import io.github.gergo019.blindspot.game.GameEnums.PieceColor;
import tools.jackson.databind.json.JsonMapper;

/**
 * Read side of the findings API (#51): the current player's findings as response records. Read-only;
 * nothing here changes data.
 */
@Service
@Transactional(readOnly = true)
public class FindingReadService {

    private final CurrentPlayer player;
    private final MistakeFindingRepository findings;
    private final ExplanationRepository explanations;
    private final JsonMapper json;

    public FindingReadService(CurrentPlayer player, MistakeFindingRepository findings,
            ExplanationRepository explanations, JsonMapper json) {
        this.player = player;
        this.findings = findings;
        this.explanations = explanations;
        this.json = json;
    }

    /** Newest game first. {@code motif} null means all motifs. Three queries per page, however large. */
    public PageResponse<FindingSummary> page(Motif motif, int page, int size) {
        Optional<Long> userId = player.userId();
        PageRequest request = PageRequest.of(page, size);
        if (userId.isEmpty()) {
            return PageResponse.of(Page.empty(request), this::summary);
        }
        Page<MistakeFinding> result = motif == null
                ? findings.findPageForUser(userId.get(), request)
                : findings.findPageForUserAndMotif(userId.get(), motif, request);
        return PageResponse.of(result, this::summary);
    }

    public FindingDetail detail(long id) {
        MistakeFinding finding = player.userId()
                .flatMap(userId -> findings.findForUser(id, userId))
                .orElseThrow(() -> new NotFoundException("finding", id));
        MoveEvaluation move = finding.getMoveEvaluation();
        Game game = move.getGame();
        EngineRun engine = move.getEngineRun();
        // Product rule 3: only a TEMPLATE explanation that PASSED verification is ever shown.
        Explanation explanation = explanations.findShowable(finding.getId()).stream()
                .findFirst()
                .map(e -> new Explanation(e.getContent()))
                .orElse(null);
        return new FindingDetail(finding.getId(), finding.getMotif(), move.getPly(), moveNumber(move.getPly()),
                side(move.getPly()), move.getFenBefore(), new Move(move.getMoveUci(), move.getMoveSan()),
                new Move(move.getBestMoveUci(), null), evidence(finding.getEvidenceLineUci()), facts(finding),
                move.getClassification().name(), finding.getDetectorVersion(),
                new Engine(engine.version(), engine.depth(), engine.threads(), engine.hashMb()), game(game),
                explanation);
    }

    private FindingSummary summary(MistakeFinding finding) {
        MoveEvaluation move = finding.getMoveEvaluation();
        return new FindingSummary(finding.getId(), finding.getMotif(), move.getPly(), moveNumber(move.getPly()),
                side(move.getPly()), new Move(move.getMoveUci(), move.getMoveSan()), facts(finding),
                finding.getDetectorVersion(), game(move.getGame()));
    }

    private Facts facts(MistakeFinding finding) {
        return json.readValue(finding.getFacts(), Facts.class);
    }

    static GameSummary game(Game game) {
        String url = "https://lichess.org/" + game.getExternalId()
                + (game.getUserColor() == PieceColor.BLACK ? "/black" : "");
        return new GameSummary(game.getId(), game.getExternalId(), url, game.getPlayedAt(), game.getUserColor(),
                game.getResult(), game.getTimeControl(), game.getOpponentName(), game.getOpponentRating(),
                game.getUserRating(), game.getOpeningEco(), game.getOpeningName());
    }

    private static List<String> evidence(String uci) {
        return Arrays.stream(uci.strip().split("\\s+")).filter(m -> !m.isEmpty()).toList();
    }

    /** Ply 1 is White's first move (standard games only; the importer skips the rest). */
    static int moveNumber(int ply) {
        return (ply + 1) / 2;
    }

    static PieceColor side(int ply) {
        return ply % 2 == 1 ? PieceColor.WHITE : PieceColor.BLACK;
    }
}
