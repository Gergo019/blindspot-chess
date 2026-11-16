"""Hanging-piece detector v0.3. Pure: no engine calls.

A HANGING_PIECE finding is reported for a user move M when all of these hold
(ADR 0004):

1. M is classified MISTAKE or BLUNDER. This excludes sacrifices the engine
   approves of.
2. The engine's principal variation (PV) from the position after M starts
   with the opponent capturing one of the user's pieces: a knight, bishop,
   rook or queen. Pawns are out of scope.

   Exchange on the square: if M is a capture and that first PV move
   recaptures on M's square, only that exchange is judged. The finding
   fires if the value of the user's piece on the square minus the value of
   the piece M captured is at least 2 (a knight taking a defended pawn: 2),
   with that difference as the loss and the evidence line the recapture.
   Rules 3 and 4 do not apply; material lost later elsewhere in the line is
   not attributed (a documented known miss).
3. Net material loss of at least 2 points (P=1, N=B=3, R=5, Q=9, from the
   user's side), measured against the engine's best line. Two lines start
   from the position before M: the best line (the engine's best move and
   its PV) and the played line (M and the PV after M). Both are cut to the
   same window: at least 4 plies, extended one ply at a time while the last
   ply of either line was forcing (a capture, a check, or the reply to a
   check), up to 8 plies. The loss is the balance at the end of the best
   line minus the balance at the end of the played line. If either window
   still ends on a forcing ply (the PV ran out or the cap was reached), the
   sequence is unfinished and there is no finding.
4. The played line itself loses material: its balance at the end of the
   window is at least 2 points below the balance right after M. Without this
   guard, a quiet move followed by an even trade would count as a loss just
   because the best line wins something: a missed win is not a hung piece.
5. No forced mate against the user: neither a checkmate inside the played
   window nor an engine score after M that is a mate against the user. A
   mating line will be its own motif later.

The evidence line is the played window without M: the opponent's capture and
the moves after it, in UCI.
"""

from dataclasses import dataclass

import chess

from app.classify import Classification
from app.models import Score

DETECTOR_VERSION = "hanging-0.3"
MOTIF = "HANGING_PIECE"
BASE_WINDOW_PLIES = 4
MAX_WINDOW_PLIES = 8
MIN_MATERIAL_LOSS = 2

PIECE_VALUES = {
    chess.PAWN: 1,
    chess.KNIGHT: 3,
    chess.BISHOP: 3,
    chess.ROOK: 5,
    chess.QUEEN: 9,
    chess.KING: 0,
}
CAPTURABLE = (chess.KNIGHT, chess.BISHOP, chess.ROOK, chess.QUEEN)
FLAGGED = (Classification.MISTAKE, Classification.BLUNDER)


@dataclass(frozen=True)
class HangingPieceFinding:
    evidence_line: tuple[chess.Move, ...]
    piece: str  # the user's piece that is won, e.g. "knight"
    square: str  # where it is captured, e.g. "d4"
    captured_by: str  # the opponent's capturing piece, e.g. "pawn"
    material_lost: int
    moved_piece: str  # the piece the user moved with M; differs from piece in a discovered loss
    moved_from: str
    moved_to: str
    motif: str = MOTIF
    detector_version: str = DETECTOR_VERSION

    @property
    def evidence_line_uci(self) -> str:
        return " ".join(move.uci() for move in self.evidence_line)


def material_balance(board: chess.Board, color: chess.Color) -> int:
    """Material of ``color`` minus the opponent's, kings counted as 0."""
    balance = 0
    for piece in board.piece_map().values():
        value = PIECE_VALUES[piece.piece_type]
        balance += value if piece.color == color else -value
    return balance


def is_mate_against(score: Score | None, user: chess.Color) -> bool:
    """True if ``score`` (White's point of view, position after M) is a forced mate against the user.

    After M the opponent is to move, so ``mate == 0`` (side to move is mated)
    would be the user mating, never a mate against them.
    """
    if score is None or score.mate is None or score.mate == 0:
        return False
    return score.mate < 0 if user == chess.WHITE else score.mate > 0


@dataclass(frozen=True)
class _Window:
    """A line cut to the window: the position at its end and whether it stops mid-sequence."""

    board: chess.Board
    plies: tuple[chess.Move, ...]
    unfinished: bool  # the last ply is forcing (capture, check or check reply): the sequence goes on
    mated: bool  # a checkmate occurred inside the window


def _replay(start: chess.Board, line: list[chess.Move], what: str) -> list[tuple[bool, chess.Board]]:
    """Plays ``line`` from ``start``; for each ply, whether it was forcing, and the board after it.

    Forcing: a capture, a check, or the reply to a check. A check and its
    reply are one unit, and what the check set up (often a recapture) comes
    after the reply, so the window must not stop there.
    """
    board = start.copy(stack=False)
    plies = []
    for move in line:
        if move not in board.legal_moves:
            raise ValueError(f"{what} move {move.uci()} is not legal in {board.fen()}")
        forcing = board.is_capture(move) or board.gives_check(move) or board.is_check()
        board.push(move)
        plies.append((forcing, board.copy(stack=False)))
    return plies


def _window_length(*lines: list[tuple[bool, chess.Board]]) -> int:
    """At least BASE_WINDOW_PLIES; one more while the last ply of either line was forcing; capped."""
    length = BASE_WINDOW_PLIES
    while length < MAX_WINDOW_PLIES and any(
        len(line) >= length and line[length - 1][0] for line in lines
    ):
        length += 1
    return length


def _cut(start: chess.Board, line: list[chess.Move], replayed, length: int) -> _Window:
    plies = replayed[:length]
    if not plies:
        return _Window(start, (), False, False)
    forcing, end = plies[-1]
    # A finished game (checkmate, stalemate) is not an unfinished exchange.
    unfinished = forcing and not end.is_game_over(claim_draw=False)
    mated = any(board.is_checkmate() for _, board in plies)
    return _Window(end, tuple(line[:length]), unfinished, mated)


def _exchange_on_the_square(
    board: chess.Board,
    move: chess.Move,
    recapture: chess.Move,
    lost: chess.Piece,
    capturer: chess.Piece,
    moved: chess.Piece,
) -> HangingPieceFinding | None:
    """M captured and the opponent recaptures on the same square: judge only that exchange.

    ``lost`` is the user's piece on the square after M (the moved piece, or what a pawn promoted to).
    """
    taken = chess.PAWN if board.is_en_passant(move) else board.piece_type_at(move.to_square)
    material_lost = PIECE_VALUES[lost.piece_type] - PIECE_VALUES[taken]
    if material_lost < MIN_MATERIAL_LOSS:
        return None
    return HangingPieceFinding(
        evidence_line=(recapture,),
        piece=chess.piece_name(lost.piece_type),
        square=chess.square_name(move.to_square),
        captured_by=chess.piece_name(capturer.piece_type),
        material_lost=material_lost,
        moved_piece=chess.piece_name(moved.piece_type),
        moved_from=chess.square_name(move.from_square),
        moved_to=chess.square_name(move.to_square),
    )


def detect(
    board: chess.Board,
    move: chess.Move,
    classification: Classification,
    principal_variation: list[chess.Move] | tuple[chess.Move, ...],
    score_after: Score | None,
    best_line: list[chess.Move] | tuple[chess.Move, ...],
) -> HangingPieceFinding | None:
    """Check one user move.

    ``board`` is the position before ``move`` (the user is to move);
    ``principal_variation`` and ``score_after`` are the engine's PV and score
    (White's point of view) for the position after it; ``best_line`` is the
    engine's PV for the position before it, starting with its best move.
    Raises ValueError if the move or a move of either line is illegal.
    """
    if classification not in FLAGGED:
        return None
    user = board.turn
    if move not in board.legal_moves:
        raise ValueError(f"{move.uci()} is not legal in {board.fen()}")
    if is_mate_against(score_after, user):
        return None

    moved = board.piece_at(move.from_square)
    after = board.copy(stack=False)
    after.push(move)
    if not principal_variation or not best_line:
        return None

    first = principal_variation[0]
    target = after.piece_at(first.to_square)
    if (
        first not in after.legal_moves
        or not after.is_capture(first)
        or target is None
        or target.color != user
        or target.piece_type not in CAPTURABLE
    ):
        return None
    capturer = after.piece_at(first.from_square)
    if board.is_capture(move) and first.to_square == move.to_square:
        return _exchange_on_the_square(board, move, first, target, capturer, moved)

    played = [move, *principal_variation]
    best = list(best_line)
    played_plies = _replay(board, played, "Played-line")
    best_plies = _replay(board, best, "Best-line")
    length = _window_length(played_plies, best_plies)
    played_window = _cut(board, played, played_plies, length)
    best_window = _cut(board, best, best_plies, length)

    if played_window.mated:
        return None
    if played_window.unfinished or best_window.unfinished:
        # Never judge in the middle of an exchange or a run of checks.
        return None

    played_end = material_balance(played_window.board, user)
    if material_balance(after, user) - played_end < MIN_MATERIAL_LOSS:
        # The played line does not lose material itself (rule 4).
        return None
    material_lost = material_balance(best_window.board, user) - played_end
    if material_lost < MIN_MATERIAL_LOSS:
        return None

    return HangingPieceFinding(
        evidence_line=played_window.plies[1:],
        piece=chess.piece_name(target.piece_type),
        square=chess.square_name(first.to_square),
        captured_by=chess.piece_name(capturer.piece_type),
        material_lost=material_lost,
        moved_piece=chess.piece_name(moved.piece_type),
        moved_from=chess.square_name(move.from_square),
        moved_to=chess.square_name(move.to_square),
    )
