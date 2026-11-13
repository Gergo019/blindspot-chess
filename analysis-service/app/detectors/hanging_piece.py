"""Hanging-piece detector v0.1. Pure: no engine calls.

A HANGING_PIECE finding is reported for a user move M when all of these hold
(ADR 0004):

1. M is classified MISTAKE or BLUNDER. This excludes sacrifices the engine
   approves of.
2. The engine's principal variation (PV) from the position after M starts
   with the opponent capturing one of the user's pieces: a knight, bishop,
   rook or queen. Pawns are out of scope in v0.1.
3. Net material loss of at least 2 points (P=1, N=B=3, R=5, Q=9, from the
   user's side): the balance before M compared with the balance after M plus
   the first up to 4 PV plies. Even trades (BxN, PxB) do not count.
4. No checkmate occurs within that window; a mating line will be its own
   motif later.

The evidence line is the PV plies used for the material check, in UCI.
"""

from dataclasses import dataclass

import chess

from app.classify import Classification

DETECTOR_VERSION = "hanging-0.1"
MOTIF = "HANGING_PIECE"
WINDOW_PLIES = 4
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


def detect(
    board: chess.Board,
    move: chess.Move,
    classification: Classification,
    principal_variation: list[chess.Move] | tuple[chess.Move, ...],
) -> HangingPieceFinding | None:
    """Check one user move.

    ``board`` is the position before ``move`` (the user is to move);
    ``principal_variation`` is the engine's PV from the position after it.
    Raises ValueError if the move or a PV move is illegal.
    """
    if classification not in FLAGGED:
        return None
    user = board.turn
    if move not in board.legal_moves:
        raise ValueError(f"{move.uci()} is not legal in {board.fen()}")

    after = board.copy(stack=False)
    after.push(move)
    window = list(principal_variation[:WINDOW_PLIES])
    if not window:
        return None

    first = window[0]
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

    line = after.copy(stack=False)
    for ply in window:
        if ply not in line.legal_moves:
            raise ValueError(f"PV move {ply.uci()} is not legal in {line.fen()}")
        line.push(ply)
        if line.is_checkmate():
            return None

    material_lost = material_balance(board, user) - material_balance(line, user)
    if material_lost < MIN_MATERIAL_LOSS:
        return None

    return HangingPieceFinding(
        evidence_line=tuple(window),
        piece=chess.piece_name(target.piece_type),
        square=chess.square_name(first.to_square),
        captured_by=chess.piece_name(capturer.piece_type),
        material_lost=material_lost,
    )
