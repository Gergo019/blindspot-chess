import pytest

from app.config import MAX_PLIES
from app.pgn import InvalidGameError, parse_finished_game
from tests.games import FOOLS_MATE, HANGS_QUEEN, LOYD_STALEMATE, knight_shuffle


def test_parses_a_finished_game():
    game = parse_finished_game(HANGS_QUEEN, MAX_PLIES)
    assert [m.uci() for m in game.moves] == ["e2e4", "d7d5", "d1g4", "c8g4"]
    assert game.result == "0-1"


def test_accepts_a_draw():
    assert parse_finished_game(LOYD_STALEMATE, MAX_PLIES).result == "1/2-1/2"


@pytest.mark.parametrize("pgn", ["1. e4 e5 2. Nf3 *", "1. e4 e5 2. Nf3"])
def test_rejects_an_unfinished_game(pgn):
    with pytest.raises(InvalidGameError, match="completed games"):
        parse_finished_game(pgn, MAX_PLIES)


def test_rejects_an_illegal_move():
    with pytest.raises(InvalidGameError, match="not a legal game"):
        parse_finished_game("1. e4 e5 2. Qxe7 1-0", MAX_PLIES)


def test_rejects_text_without_moves():
    with pytest.raises(InvalidGameError):
        parse_finished_game("this is not chess", MAX_PLIES)


def test_rejects_a_game_longer_than_the_limit():
    parse_finished_game(knight_shuffle(MAX_PLIES), MAX_PLIES)
    with pytest.raises(InvalidGameError, match="limit is 300"):
        parse_finished_game(knight_shuffle(MAX_PLIES + 1), MAX_PLIES)


def test_rejects_a_result_that_contradicts_checkmate():
    with pytest.raises(InvalidGameError, match="must be 0-1"):
        parse_finished_game(FOOLS_MATE.replace("0-1", "1-0"), MAX_PLIES)


def test_rejects_chess960():
    pgn = (
        '[Variant "Chess960"]\n'
        '[FEN "bbqnnrkr/pppppppp/8/8/8/8/PPPPPPPP/BBQNNRKR w HFhf - 0 1"]\n'
        '[SetUp "1"]\n\n1. e4 e5 1-0'
    )
    with pytest.raises(InvalidGameError, match="standard chess"):
        parse_finished_game(pgn, MAX_PLIES)


# --- Review A6 (2026-11-14) and issue #32: each case is a 422 with a clear message ---


def test_rejects_null_moves():
    # The review's example: accepted before, and "--" was even classified as an INACCURACY.
    with pytest.raises(InvalidGameError, match="null move"):
        parse_finished_game("1. e4 -- 2. d4 -- 3. Nf3 e5 1-0", MAX_PLIES)


def test_rejects_an_invalid_start_position():
    # No white king: accepted before, and it killed the engine.
    pgn = '[SetUp "1"]\n[FEN "4k3/8/8/8/8/8/8/8 b - - 0 1"]\n[Result "1-0"]\n\n1... Kd7 1-0'
    with pytest.raises(InvalidGameError, match="start position is not a valid"):
        parse_finished_game(pgn, MAX_PLIES)


def test_rejects_a_second_game():
    pgn = '[Result "1-0"]\n\n1. e4 e5 1-0\n\n[Result "0-1"]\n\n1. d4 d5 0-1'
    with pytest.raises(InvalidGameError, match="more than one game"):
        parse_finished_game(pgn, MAX_PLIES)


def test_text_after_the_game_that_is_not_a_game_is_ignored():
    assert parse_finished_game("1. e4 e5 1-0\n\nanalysed by hand", MAX_PLIES).result == "1-0"


@pytest.mark.parametrize("movetext_result", ["*", "0-1", "1/2-1/2"])
def test_rejects_a_movetext_result_that_contradicts_the_header(movetext_result):
    # The header used to win silently over a "*" in the movetext.
    pgn = f'[Result "1-0"]\n\n1. e4 e5 {movetext_result}'
    with pytest.raises(InvalidGameError, match="Result header"):
        parse_finished_game(pgn, MAX_PLIES)


def test_rejects_a_move_after_checkmate():
    with pytest.raises(InvalidGameError, match="after the game ended by checkmate"):
        parse_finished_game(FOOLS_MATE.replace("0-1", "3. a3 0-1"), MAX_PLIES)


def test_rejects_a_move_after_stalemate():
    with pytest.raises(InvalidGameError, match="after the game ended by stalemate"):
        parse_finished_game(LOYD_STALEMATE.replace("10. Qe6", "10. Qe6 Kh8"), MAX_PLIES)


def test_rejects_a_move_after_insufficient_material():
    # Kxd2 leaves bare kings; Kd7 is played after the game is over.
    pgn = '[SetUp "1"]\n[FEN "4k3/8/8/8/8/8/3p4/4K3 w - - 0 1"]\n[Result "1/2-1/2"]\n\n1. Kxd2 Kd7 1/2-1/2'
    with pytest.raises(InvalidGameError, match="after the game ended by insufficient material"):
        parse_finished_game(pgn, MAX_PLIES)


def test_a_game_that_ends_by_insufficient_material_is_accepted():
    pgn = '[SetUp "1"]\n[FEN "4k3/8/8/8/8/8/3p4/4K3 w - - 0 1"]\n[Result "1/2-1/2"]\n\n1. Kxd2 1/2-1/2'
    assert len(parse_finished_game(pgn, MAX_PLIES).moves) == 1


def test_unexpected_parser_failures_become_invalid_game_errors(monkeypatch):
    def boom(*args, **kwargs):
        raise RuntimeError("parser exploded")

    monkeypatch.setattr("app.pgn.chess.pgn.read_game", boom)
    with pytest.raises(InvalidGameError, match="could not be read"):
        parse_finished_game(HANGS_QUEEN, MAX_PLIES)


def test_a_game_that_continues_after_a_fivefold_repetition_ends_there_for_analysis():
    # Nf3 Nf6 Ng1 Ng8 brings back the start position every 4 half-moves; the fifth time is after 16.
    # Real Lichess games play on after this (79 of 39,080 rapid games in 2013-01), so it is not an
    # error: the moves after it are not analysed.
    game = parse_finished_game(knight_shuffle(24), MAX_PLIES)
    assert game.ended_by_fivefold_at == 16
    assert len(game.moves) == 16
    assert game.result == "1/2-1/2"


def test_a_game_without_a_fivefold_repetition_is_not_cut():
    game = parse_finished_game(HANGS_QUEEN, MAX_PLIES)
    assert game.ended_by_fivefold_at is None
    assert len(game.moves) == 4


def test_rejects_a_move_after_the_75_move_rule():
    # Ra2 makes it 150 half-moves without a capture or pawn move: the game is over, Kd7 comes after it.
    # No finished 2013-01 game reaches such a position, so this stays a rejection.
    pgn = '[SetUp "1"]\n[FEN "4k3/8/8/8/8/8/8/R3K3 w - - 149 100"]\n[Result "1/2-1/2"]\n\n100. Ra2 Kd7 1/2-1/2'
    with pytest.raises(InvalidGameError, match="75-move rule"):
        parse_finished_game(pgn, MAX_PLIES)


def test_a_game_that_ends_at_the_75_move_rule_is_accepted():
    pgn = '[SetUp "1"]\n[FEN "4k3/8/8/8/8/8/8/R3K3 w - - 149 100"]\n[Result "1/2-1/2"]\n\n100. Ra2 1/2-1/2'
    assert len(parse_finished_game(pgn, MAX_PLIES).moves) == 1
