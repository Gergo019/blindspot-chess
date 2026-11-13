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
from app.detectors import hanging_piece
from app.models import EngineInfo, Finding, FindingFacts, MoveEvaluation, Score
from app.pgn import ParsedGame

# Plies of the principal variation kept per position; detectors use them as
# evidence lines (the hanging-piece window is 4 plies, extended up to 8 while
# captures continue).
PV_PLIES = 8


class EngineUnavailableError(RuntimeError):
    """The Stockfish binary could not be started."""


@dataclass(frozen=True)
class PositionEval:
    score: Score  # White's point of view
    best_move: chess.Move | None  # None when the game is over in this position
    pv: tuple[chess.Move, ...] = ()  # first PV_PLIES plies of the principal variation


@dataclass(frozen=True)
class GameAnalysis:
    moves: list[MoveEvaluation]
    findings: list[Finding]


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
        pv = tuple(info.get("pv", ()))
        if not pv:
            raise RuntimeError(f"Stockfish returned no best move for {board.fen()}")
        return PositionEval(score, pv[0], pv[:PV_PLIES])


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


def analyze_game(game: ParsedGame, user_is_white: bool, engine: Engine) -> GameAnalysis:
    """Evaluate every half-move of the game, for both sides, and run the
    detectors on the user's moves."""
    board = game.start.copy()
    current = engine.evaluate(board)
    evaluations: list[MoveEvaluation] = []
    findings: list[Finding] = []
    for ply, move in enumerate(game.moves, start=1):
        white_moved = board.turn == chess.WHITE
        by_user = white_moved == user_is_white
        position_before = board.copy(stack=False)
        san = board.san(move)
        before = current
        board.push(move)
        current = engine.evaluate(board)
        evaluation = MoveEvaluation(
            ply=ply,
            by_user=by_user,
            fen_before=position_before.fen(),
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
        evaluations.append(evaluation)
        if by_user:
            hanging = hanging_piece.detect(
                position_before, move, evaluation.classification, current.pv, current.score
            )
            if hanging is not None:
                findings.append(_finding(ply, hanging))
    return GameAnalysis(moves=evaluations, findings=findings)


def _finding(ply: int, hanging: hanging_piece.HangingPieceFinding) -> Finding:
    return Finding(
        ply=ply,
        motif=hanging.motif,
        evidence_line_uci=hanging.evidence_line_uci,
        detector_version=hanging.detector_version,
        facts=FindingFacts(
            piece=hanging.piece,
            square=hanging.square,
            captured_by=hanging.captured_by,
            material_lost=hanging.material_lost,
            moved_piece=hanging.moved_piece,
            moved_from=hanging.moved_from,
            moved_to=hanging.moved_to,
        ),
    )
