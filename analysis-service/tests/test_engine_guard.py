"""The analysis loop never indexes a missing best move. No Stockfish: the positions are already over."""

from types import SimpleNamespace

import chess
import pytest

from app.engine import Engine, analyze_game
from app.pgn import InvalidGameError, ParsedGame


def terminal_only_engine():
    # Engine.evaluate answers a finished position without searching, so no process is needed.
    return SimpleNamespace(evaluate=lambda board: Engine.evaluate(None, board))


@pytest.mark.parametrize(
    "fen",
    [
        "rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3",  # checkmated (fool's mate)
        "7k/5Q2/6K1/8/8/8/8/8 b - - 0 1",  # stalemate
    ],
)
def test_a_move_from_a_finished_position_is_an_invalid_game_not_a_crash(fen):
    game = ParsedGame(start=chess.Board(fen), moves=[chess.Move.from_uci("a2a3")], result="1/2-1/2")
    with pytest.raises(InvalidGameError, match="after the game ended"):
        analyze_game(game, True, terminal_only_engine())
