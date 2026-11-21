"""Judging every legal move of a drill position (POST /judge, #78, ADR 0008).

Each legal move is searched on its own (the engine's ``searchmoves``), at the pinned depth and
from a cleared state, like every other search (engine.py). Its verdict is the drop in winning
chances against the best judged move, on the classification's scale (classify.py); a move is
accepted when that drop is below the inaccuracy threshold, 0.1.

Time budget: the searches are never shortened, so a result stays reproducible. The elapsed time
is checked after every search; once it exceeds the budget, the request fails with
:class:`JudgeBudgetExceeded` and no verdicts at all. It can overrun the budget by at most one
search.
"""

import time
from collections.abc import Callable
from dataclasses import dataclass

import chess

from app.classify import INACCURACY_DROP, winning_chances
from app.models import Score, Verdict

ACCEPT_BELOW = INACCURACY_DROP
DROP_DECIMALS = 4  # the backend stores the drop as NUMERIC(5, 4)


class InvalidPositionError(ValueError):
    """The FEN cannot be judged: not a FEN, not a legal position, or no legal moves."""


class JudgeBudgetExceeded(Exception):
    """Judging took longer than the budget; nothing of it is returned."""

    def __init__(self, budget_seconds: float, elapsed_seconds: float, moves_judged: int, legal_moves: int):
        self.budget_seconds = budget_seconds
        self.elapsed_seconds = elapsed_seconds
        self.moves_judged = moves_judged
        self.legal_moves = legal_moves
        super().__init__(
            f"Judging took longer than the budget of {budget_seconds:g} s: "
            f"{elapsed_seconds:.1f} s after {moves_judged} of {legal_moves} moves"
        )


@dataclass(frozen=True)
class MoveResult:
    """What the engine said about one root move: the score after it and its line (starting with it)."""

    move: chess.Move
    score: Score  # White's point of view
    pv: tuple[chess.Move, ...]


def parse_position(fen: str) -> chess.Board:
    """A position that can be judged, or :class:`InvalidPositionError`.

    An invalid position (for example a missing king, or the side not to move in check) must never
    reach Stockfish, which may crash on it.
    """
    try:
        board = chess.Board(fen)
    except ValueError as exc:
        raise InvalidPositionError(f"Not a valid FEN: {exc}") from exc
    status = board.status()
    if status != chess.STATUS_VALID:
        problems = [flag.name for flag in chess.Status if flag and flag in status and flag.name]
        raise InvalidPositionError(f"Not a legal position: {', '.join(problems).lower()}")
    outcome = board.outcome(claim_draw=False)
    if outcome is not None:
        raise InvalidPositionError(f"The game is over in this position ({outcome.termination.name.lower()})")
    return board


def mover_chances(score: Score, board: chess.Board) -> float:
    """Winning chances in [-1, +1] for the side to move in ``board`` (the mover), for a root score."""
    white = winning_chances(score.cp, score.mate, white_to_move=board.turn == chess.WHITE)
    return white if board.turn == chess.WHITE else -white


def is_accepted(drop: float) -> bool:
    return drop < ACCEPT_BELOW


def verdicts(board: chess.Board, results: list[MoveResult]) -> list[Verdict]:
    """Verdicts for the judged moves of ``board``: best first, ties by UCI. Pure, no engine."""
    if not results:
        return []
    chances = {result.move: mover_chances(result.score, board) for result in results}
    best = max(chances.values())
    out = []
    for result in results:
        drop = round(best - chances[result.move], DROP_DECIMALS)
        out.append(
            Verdict(
                uci=result.move.uci(),
                san=board.san(result.move),
                score=result.score,
                drop=abs(drop),  # never -0.0
                accepted=is_accepted(drop),
                refutation_uci=" ".join(move.uci() for move in result.pv[1:]),
            )
        )
    out.sort(key=lambda verdict: (verdict.drop, verdict.uci))
    return out


def judge(
    board: chess.Board,
    search: Callable[[chess.Board, chess.Move], MoveResult],
    budget_seconds: float,
    clock: Callable[[], float] = time.monotonic,
) -> list[Verdict]:
    """Searches every legal move of ``board`` with ``search`` and returns their verdicts.

    Moves are searched in UCI order, so the order of searches is the same on every run. Raises
    :class:`JudgeBudgetExceeded` (and returns nothing) as soon as the elapsed time exceeds
    ``budget_seconds``.
    """
    moves = sorted(board.legal_moves, key=lambda move: move.uci())
    start = clock()
    results = []
    for move in moves:
        results.append(search(board, move))
        elapsed = clock() - start
        if elapsed > budget_seconds:
            raise JudgeBudgetExceeded(budget_seconds, elapsed, len(results), len(moves))
    return verdicts(board, results)
