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
