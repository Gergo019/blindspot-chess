"""Stockfish over UCI with python-chess.

Reproducibility:

* One engine process per request, always closed. A pooled engine would carry
  its transposition table from one request into the next.
* Threads, Hash and depth are fixed by the settings (see config.py).
* Every position is searched from a cleared state: each search is sent as a
  new game, so Stockfish runs ``ucinewgame`` and clears its hash and history
  tables first. A position's evaluation then does not depend on which
  positions were searched before it, and re-analysing a single ply gives the
  same answer as analysing the whole game.
"""

from collections.abc import Iterator
from contextlib import contextmanager
from dataclasses import dataclass

import chess
import chess.engine

from app.classify import classify
from app.config import Settings
from app.models import EngineInfo, MoveEvaluation, Score
from app.pgn import ParsedGame


class EngineUnavailableError(RuntimeError):
    """The Stockfish binary could not be started."""


@dataclass(frozen=True)
class PositionEval:
    score: Score  # White's point of view
    best_move: chess.Move | None  # None when the game is over in this position


def split_engine_id(engine_id: str) -> tuple[str, str]:
    """``"Stockfish 19"`` -> ``("Stockfish", "19")``."""
    name, _, version = engine_id.strip().partition(" ")
    return name, version.strip() or "unknown"


class Engine:
    def __init__(self, engine: chess.engine.SimpleEngine, settings: Settings) -> None:
        self._engine = engine
        self._limit = chess.engine.Limit(depth=settings.depth)
        name, version = split_engine_id(engine.id.get("name", "unknown"))
        self.info = EngineInfo(name=name, version=version, depth=settings.depth)

    def evaluate(self, board: chess.Board) -> PositionEval:
        outcome = board.outcome(claim_draw=False)
        if outcome is not None:
            # Game over on the board: no search. Checkmate is mate 0 (the side
            # to move is mated, the Lichess convention); every other ending
            # by the rules (stalemate, insufficient material, 75-move rule,
            # fivefold repetition) is a draw, 0 centipawns.
            if outcome.termination == chess.Termination.CHECKMATE:
                return PositionEval(Score(mate=0), None)
            return PositionEval(Score(cp=0), None)

        # A fresh game object per search makes python-chess send ucinewgame.
        info = self._engine.analyse(board, self._limit, game=object())
        pov = info["score"].white()
        if pov.is_mate():
            score = Score(mate=pov.mate())
        else:
            score = Score(cp=pov.score())
        best = info.get("pv", [None])[0]
        if best is None:
            raise RuntimeError(f"Stockfish returned no best move for {board.fen()}")
        return PositionEval(score, best)


@contextmanager
def open_engine(settings: Settings) -> Iterator[Engine]:
    try:
        raw = chess.engine.SimpleEngine.popen_uci(settings.stockfish_path)
    except (FileNotFoundError, PermissionError, chess.engine.EngineError) as exc:
        raise EngineUnavailableError(
            f"Could not start Stockfish at {settings.stockfish_path!r}: {exc}"
        ) from exc
    try:
        raw.configure({"Threads": settings.threads, "Hash": settings.hash_mb})
        yield Engine(raw, settings)
    finally:
        raw.quit()


def analyze_game(game: ParsedGame, user_is_white: bool, engine: Engine) -> list[MoveEvaluation]:
    """Evaluate every half-move of the game, for both sides."""
    board = game.start.copy()
    current = engine.evaluate(board)
    evaluations: list[MoveEvaluation] = []
    for ply, move in enumerate(game.moves, start=1):
        white_moved = board.turn == chess.WHITE
        fen_before = board.fen()
        san = board.san(move)
        before = current
        board.push(move)
        current = engine.evaluate(board)
        evaluations.append(
            MoveEvaluation(
                ply=ply,
                by_user=white_moved == user_is_white,
                fen_before=fen_before,
                move_uci=move.uci(),
                move_san=san,
                best_move_uci=before.best_move.uci(),
                score_before=before.score,
                score_after=current.score,
                classification=classify(
                    white_moved=white_moved,
                    played_best=move == before.best_move,
                    before=(before.score.cp, before.score.mate),
                    after=(current.score.cp, current.score.mate),
                ),
            )
        )
    return evaluations
