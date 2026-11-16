"""PGN parsing and validation. No engine.

Product rule: completed games only. A game is accepted when it is one game of
standard chess from a valid start position, every move is legal (no null
moves), it has a final result (1-0, 0-1 or 1/2-1/2) that the movetext does not
contradict and that agrees with a checkmate on the board, no move is played
after checkmate, stalemate, insufficient material or a 75-move-rule
position, and it is not longer than the configured ply limit. Anything else
is an InvalidGameError (HTTP 422).

Fivefold repetition is different: real Lichess games continue after it (79
of 39,080 finished rapid games in the 2013-01 open database), so such a game
is not rejected. Its analysis ends at the fivefold position, where the game
is over by the rules (ParsedGame.ended_by_fivefold_at).
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
    # The half-move after which a fivefold repetition stood; later moves are not analysed.
    ended_by_fivefold_at: int | None = None


class _GameBuilder(chess.pgn.GameBuilder):
    """Keeps the termination marker of the movetext, which python-chess drops when a Result header exists."""

    def begin_game(self) -> None:
        super().begin_game()
        self.game.movetext_result = None

    def visit_result(self, result: str) -> None:
        self.game.movetext_result = result
        super().visit_result(result)


def _game_over_reason(board: chess.Board) -> str | None:
    """Why no move may follow in ``board``: checkmate, stalemate or insufficient material."""
    if board.is_checkmate():
        return "checkmate"
    if board.is_stalemate():
        return "stalemate"
    if board.is_insufficient_material():
        return "insufficient material"
    return None


def parse_finished_game(pgn: str, max_plies: int) -> ParsedGame:
    """Parses and validates one finished game. Raises InvalidGameError for anything that is not one."""
    try:
        return _parse(pgn, max_plies)
    except InvalidGameError:
        raise
    except Exception as exc:  # noqa: BLE001 - malformed input must never become a server error
        raise InvalidGameError(f"The PGN could not be read: {exc}") from exc


def _parse(pgn: str, max_plies: int) -> ParsedGame:
    handle = io.StringIO(pgn)
    game = chess.pgn.read_game(handle, Visitor=_GameBuilder)
    if game is None:
        raise InvalidGameError("No game found in the PGN.")
    if _another_game_follows(handle):
        raise InvalidGameError("The PGN contains more than one game; send one game per request.")

    start = game.board()
    if type(start) is not chess.Board or start.chess960:
        raise InvalidGameError("Only standard chess games can be analysed.")
    if not start.is_valid():
        raise InvalidGameError(f"The start position is not a valid chess position: {start.fen()}")

    if game.errors:
        played = game.end().board()
        reason = _game_over_reason(played)
        if reason is not None:
            raise InvalidGameError(f"A move is played after the game ended by {reason}.")
        raise InvalidGameError(f"The PGN is not a legal game: {game.errors[0]}")

    result = game.headers.get("Result", "*").strip()
    movetext_result = getattr(game, "movetext_result", None)
    if result not in FINISHED_RESULTS:
        raise InvalidGameError(
            f"Only completed games can be analysed; the result is {result!r}, "
            "expected 1-0, 0-1 or 1/2-1/2."
        )
    if movetext_result is not None and movetext_result != result:
        raise InvalidGameError(
            f"The movetext ends with {movetext_result!r} but the Result header says {result!r}."
        )

    moves = list(game.mainline_moves())
    if not moves:
        raise InvalidGameError("The game has no moves.")
    if len(moves) > max_plies:
        raise InvalidGameError(
            f"The game has {len(moves)} half-moves; the limit is {max_plies}."
        )

    board = start.copy()
    ended_by_fivefold_at = None
    for ply, move in enumerate(moves, start=1):
        if not move:
            raise InvalidGameError(f"Half-move {ply} is a null move ('--'); only real moves can be analysed.")
        reason = _game_over_reason(board)
        if reason is not None:
            raise InvalidGameError(f"Half-move {ply} is played after the game ended by {reason}.")
        if board.is_fivefold_repetition():
            # Over by the rules, but real games play on: analyse up to here only.
            ended_by_fivefold_at = ply - 1
            moves = moves[: ply - 1]
            break
        if board.is_seventyfive_moves():
            raise InvalidGameError(f"Half-move {ply} is played after the game ended by the 75-move rule.")
        board.push(move)

    if board.is_checkmate():
        winner = "0-1" if board.turn == chess.WHITE else "1-0"
        if result != winner:
            raise InvalidGameError(
                f"The game ends in checkmate, so the result must be {winner}, not {result}."
            )

    return ParsedGame(start=start, moves=moves, result=result, ended_by_fivefold_at=ended_by_fivefold_at)


def _another_game_follows(handle: io.StringIO) -> bool:
    """True if the rest of the PGN holds another game: moves or tags of its own, not just whitespace or text."""
    following = chess.pgn.read_game(handle)
    if following is None:
        return False
    default = chess.pgn.Game().headers
    return any(True for _ in following.mainline_moves()) or dict(following.headers) != dict(default)
