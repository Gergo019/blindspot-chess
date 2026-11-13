"""PGN parsing and validation. No engine.

Product rule: completed games only. A game is accepted when it is standard
chess, every move is legal, it has a final result (1-0, 0-1 or 1/2-1/2), the
result does not contradict a checkmate on the board, and it is not longer
than the configured ply limit.
"""

import io
from dataclasses import dataclass

import chess
import chess.pgn

FINISHED_RESULTS = {"1-0", "0-1", "1/2-1/2"}


class InvalidGameError(ValueError):
    """The PGN is not a legal, finished game that can be analysed."""


@dataclass(frozen=True)
class ParsedGame:
    start: chess.Board
    moves: list[chess.Move]
    result: str


def parse_finished_game(pgn: str, max_plies: int) -> ParsedGame:
    game = chess.pgn.read_game(io.StringIO(pgn))
    if game is None:
        raise InvalidGameError("No game found in the PGN.")
    if game.errors:
        raise InvalidGameError(f"The PGN is not a legal game: {game.errors[0]}")

    start = game.board()
    if type(start) is not chess.Board or start.chess960:
        raise InvalidGameError("Only standard chess games can be analysed.")

    result = game.headers.get("Result", "*").strip()
    if result not in FINISHED_RESULTS:
        raise InvalidGameError(
            f"Only completed games can be analysed; the result is {result!r}, "
            "expected 1-0, 0-1 or 1/2-1/2."
        )

    moves = list(game.mainline_moves())
    if not moves:
        raise InvalidGameError("The game has no moves.")
    if len(moves) > max_plies:
        raise InvalidGameError(
            f"The game has {len(moves)} half-moves; the limit is {max_plies}."
        )

    board = start.copy()
    for move in moves:
        board.push(move)
    if board.is_checkmate():
        winner = "0-1" if board.turn == chess.WHITE else "1-0"
        if result != winner:
            raise InvalidGameError(
                f"The game ends in checkmate, so the result must be {winner}, not {result}."
            )

    return ParsedGame(start=start, moves=moves, result=result)
