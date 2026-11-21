"""POST /judge rules without Stockfish: drops, acceptance, ordering, the time budget, rejected FENs.

The engine is replaced by a function that returns fixed scores, so the rules are tested exactly.
"""

from contextlib import contextmanager
from types import SimpleNamespace

import chess
import pytest
from fastapi.testclient import TestClient

from app import judge, main
from app.classify import cp_winning_chances
from app.config import Settings, get_settings
from app.judge import JudgeBudgetExceeded, MoveResult, is_accepted, verdicts
from app.main import app
from app.models import EngineInfo, Score

START = chess.STARTING_FEN
client = TestClient(app)


def result(board: chess.Board, uci: str, cp: int | None = None, mate: int | None = None, *line: str) -> MoveResult:
    move = chess.Move.from_uci(uci)
    return MoveResult(move, Score(cp=cp, mate=mate), (move, *map(chess.Move.from_uci, line)))


class TestAcceptance:
    def test_the_threshold_is_the_inaccuracy_threshold(self):
        assert judge.ACCEPT_BELOW == 0.1

    def test_just_below_the_threshold_is_accepted_and_the_threshold_itself_is_not(self):
        assert is_accepted(0.0999)
        assert not is_accepted(0.1)
        assert not is_accepted(0.1001)

    def test_a_second_safe_move_is_accepted(self):
        board = chess.Board(START)
        out = verdicts(board, [result(board, "e2e4", 30), result(board, "d2d4", 25), result(board, "g1f3", 20)])
        assert [v.uci for v in out if v.accepted] == ["e2e4", "d2d4", "g1f3"]

    def test_a_move_just_above_the_threshold_is_rejected(self):
        board = chess.Board(START)
        # From 0 cp, -50 cp drops 0.0918 (accepted) and -60 cp drops 0.1100 (rejected).
        assert round(cp_winning_chances(0) - cp_winning_chances(-50), 4) == 0.0918
        out = {v.uci: v for v in verdicts(board, [result(board, "e2e4", 0), result(board, "a2a3", -50),
                                                   result(board, "g2g4", -60)])}
        assert out["e2e4"].drop == 0 and out["e2e4"].accepted
        assert out["a2a3"].drop == 0.0918 and out["a2a3"].accepted
        assert out["g2g4"].drop == 0.11 and not out["g2g4"].accepted


class TestVerdicts:
    def test_the_drop_is_measured_from_the_movers_side(self):
        # Black to move: a lower (White's point of view) score is better for Black.
        board = chess.Board("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1")
        out = verdicts(board, [result(board, "e7e5", 30), result(board, "f7f6", 120)])
        assert [(v.uci, v.accepted) for v in out] == [("e7e5", True), ("f7f6", False)]
        assert out[0].drop == 0

    def test_best_first_ties_by_uci(self):
        board = chess.Board(START)
        out = verdicts(board, [result(board, "g1f3", 10), result(board, "b1c3", 10), result(board, "f2f3", -200)])
        assert [v.uci for v in out] == ["b1c3", "g1f3", "f2f3"]

    def test_mates_count_as_full_winning_chances(self):
        board = chess.Board("6k1/5ppp/8/8/8/8/5PPP/R5K1 w - - 0 1")
        out = verdicts(board, [result(board, "a1a8", mate=1), result(board, "g1f1", cp=500),
                               result(board, "a1a2", mate=-3)])
        assert out[0].uci == "a1a8" and out[0].drop == 0
        assert out[-1].uci == "a1a2" and out[-1].drop == 2.0

    def test_the_refutation_is_the_line_after_the_move(self):
        board = chess.Board(START)
        (out,) = verdicts(board, [result(board, "f2f3", -50, None, "e7e5", "g2g4", "d8h4")])
        assert out.refutation_uci == "e7e5 g2g4 d8h4"
        assert out.san == "f3"

    def test_a_mating_move_has_an_empty_refutation(self):
        board = chess.Board("6k1/5ppp/8/8/8/8/5PPP/R5K1 w - - 0 1")
        (out,) = verdicts(board, [result(board, "a1a8", mate=1)])
        assert out.refutation_uci == ""


class FakeClock:
    def __init__(self, per_search: float):
        self.now = 0.0
        self.per_search = per_search

    def __call__(self) -> float:
        return self.now

    def search(self, board: chess.Board, move: chess.Move) -> MoveResult:
        self.now += self.per_search
        return MoveResult(move, Score(cp=0), (move,))


class TestBudget:
    def test_within_the_budget_every_legal_move_is_judged(self):
        board = chess.Board(START)
        clock = FakeClock(per_search=1.0)
        out = judge.judge(board, clock.search, budget_seconds=20.0, clock=clock)
        assert len(out) == board.legal_moves.count() == 20

    def test_over_the_budget_fails_with_no_verdicts(self):
        board = chess.Board(START)
        clock = FakeClock(per_search=1.0)
        with pytest.raises(JudgeBudgetExceeded) as raised:
            judge.judge(board, clock.search, budget_seconds=5.5, clock=clock)
        assert raised.value.moves_judged == 6  # stops after the search that crossed the budget
        assert raised.value.legal_moves == 20
        assert raised.value.elapsed_seconds == 6.0

    def test_the_search_that_crosses_the_budget_completes_and_nothing_follows(self):
        # The budget is only checked between searches, so no search is ever cut short.
        board = chess.Board(START)
        searched = []

        def search(position, move):
            searched.append(move.uci())
            return MoveResult(move, Score(cp=0), (move,))

        clock = iter([0.0, 100.0]).__next__
        with pytest.raises(JudgeBudgetExceeded):
            judge.judge(board, search, budget_seconds=1.0, clock=clock)
        assert searched == ["a2a3"]

    def test_moves_are_searched_in_uci_order(self):
        board = chess.Board(START)
        searched = []

        def search(position, move):
            searched.append(move.uci())
            return MoveResult(move, Score(cp=0), (move,))

        judge.judge(board, search, budget_seconds=1e9)
        assert searched == sorted(searched)

    def test_an_exceeded_budget_is_a_503_without_verdicts(self, monkeypatch):
        def over_budget(board, search, budget_seconds, clock=None):
            raise JudgeBudgetExceeded(budget_seconds, 61.2, 7, 20)

        monkeypatch.setattr(judge, "judge", over_budget)
        monkeypatch.setattr(main, "open_engine", _engine_without_process)
        app.dependency_overrides[get_settings] = lambda: _settings(budget=60.0)
        try:
            response = client.post("/judge", json={"fen": START})
        finally:
            app.dependency_overrides.clear()
        assert response.status_code == 503
        body = response.json()
        assert "verdicts" not in body
        assert body["detail"]["reason"] == "JUDGE_BUDGET_EXCEEDED"
        assert body["detail"]["budgetSeconds"] == 60.0
        assert body["detail"]["movesJudged"] == 7 and body["detail"]["legalMoves"] == 20


def _settings(budget: float) -> Settings:
    return Settings(stockfish_path="stockfish", depth=16, threads=1, hash_mb=64, judge_budget_seconds=budget)


@contextmanager
def _engine_without_process(settings):
    """Stands in for open_engine: an engine object that never starts Stockfish."""
    yield SimpleNamespace(
        info=EngineInfo(name="Stockfish", version="19", depth=16, threads=1, hash_mb=64), search_move=None
    )


class TestRejectedPositions:
    """Rejected before any engine starts, so these run without Stockfish."""

    @pytest.mark.parametrize(
        ("fen", "reason"),
        [
            ("not a fen", "Not a valid FEN"),
            ("8/8/8/8/8/8/8/8 w - - 0 1", "Not a legal position"),  # no kings
            ("4k3/8/8/8/8/8/8/R3K3 w K - 0 1", "Not a legal position"),  # kingside castling right, no h1 rook
            ("4k3/4Q3/8/8/8/8/8/4K3 w - - 0 1", "Not a legal position"),  # the side not to move is in check
            ("rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3", "game is over"),  # checkmate
            ("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1", "game is over"),  # stalemate
            ("4k3/8/8/8/8/8/8/4K3 w - - 0 1", "game is over"),  # insufficient material
        ],
    )
    def test_is_422(self, fen, reason):
        response = client.post("/judge", json={"fen": fen})
        assert response.status_code == 422, response.text
        assert reason in response.json()["detail"]

    @pytest.mark.parametrize("fen", ["", "x" * 101])
    def test_empty_or_too_long_is_422(self, fen):
        assert client.post("/judge", json={"fen": fen}).status_code == 422

    def test_missing_engine_is_503(self):
        app.dependency_overrides[get_settings] = lambda: Settings(
            stockfish_path="/nonexistent/stockfish", depth=16, threads=1, hash_mb=64
        )
        try:
            response = client.post("/judge", json={"fen": START})
        finally:
            app.dependency_overrides.clear()
        assert response.status_code == 503
        assert isinstance(response.json()["detail"], str)
