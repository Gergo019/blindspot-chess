"""Engine tests on short, fixed games.

They assert facts that hold at any reasonable depth (a hung queen is a
blunder, a mate is a mate), never exact centipawn values.
"""

import os

import chess
import pytest

from app.classify import Classification
from app.models import Score
from tests.games import (
    BLACKBURNE_SHILLING,
    FOOLS_MATE,
    HANGS_QUEEN,
    HANGS_QUEEN_PLY,
    LOYD_STALEMATE,
)

pytestmark = pytest.mark.engine


def test_reports_the_engine_and_depth(analyze):
    info, _ = analyze(FOOLS_MATE)
    assert info.name == "Stockfish"
    assert info.depth == int(os.environ.get("ENGINE_DEPTH", "16"))
    pinned = os.environ.get("STOCKFISH_VERSION")
    if pinned:  # set by the Docker image
        assert info.version == pinned


def test_hanging_the_queen_is_a_blunder(analyze):
    _, moves = analyze(HANGS_QUEEN, user_is_white=True)
    hang = moves[HANGS_QUEEN_PLY - 1]
    assert hang.move_san == "Qg4"
    assert hang.by_user is True
    assert hang.classification == Classification.BLUNDER
    # The capture that wins the queen is the engine's best move.
    assert moves[HANGS_QUEEN_PLY].move_san == "Bxg4"
    assert moves[HANGS_QUEEN_PLY].classification == Classification.BEST


def test_every_half_move_of_both_sides_is_analysed(analyze):
    _, moves = analyze(BLACKBURNE_SHILLING, user_is_white=False)
    assert [m.ply for m in moves] == list(range(1, 15))
    assert [m.by_user for m in moves] == [ply % 2 == 0 for ply in range(1, 15)]
    board = chess.Board()
    for m in moves:
        assert m.fen_before == board.fen()
        assert chess.Move.from_uci(m.best_move_uci) in board.legal_moves
        assert board.san(chess.Move.from_uci(m.move_uci)) == m.move_san
        board.push_uci(m.move_uci)


def test_score_after_a_move_is_the_score_before_the_next(analyze):
    _, moves = analyze(BLACKBURNE_SHILLING)
    for current, following in zip(moves, moves[1:]):
        assert current.score_after == following.score_before


def test_checkmate_ends_with_mate_zero(analyze):
    _, moves = analyze(FOOLS_MATE, user_is_white=True)
    allows_mate, mates = moves[2], moves[3]
    assert allows_mate.move_san == "g4"
    assert allows_mate.classification == Classification.BLUNDER
    assert allows_mate.score_after == Score(mate=-1)
    assert mates.move_san == "Qh4#"
    assert mates.score_before == Score(mate=-1)
    assert mates.score_after == Score(mate=0)
    assert mates.classification == Classification.BEST
    assert mates.by_user is False


def test_stalemate_ends_with_a_draw_score(analyze):
    _, moves = analyze(LOYD_STALEMATE)
    final = moves[-1]
    assert final.move_san == "Qe6"
    assert final.score_after == Score(cp=0)
    # White was completely winning and stalemated instead: that throws the win away.
    assert final.classification == Classification.BLUNDER


def test_same_game_twice_gives_identical_output(analyze):
    first_info, first = analyze(BLACKBURNE_SHILLING)
    second_info, second = analyze(BLACKBURNE_SHILLING)
    assert first_info == second_info
    assert [m.model_dump() for m in first] == [m.model_dump() for m in second]
