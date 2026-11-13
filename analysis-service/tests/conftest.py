import pytest

from app.config import MAX_PLIES, get_settings
from app.engine import analyze_game, open_engine
from app.pgn import parse_finished_game


@pytest.fixture
def analyze():
    """Analyse a PGN with the configured engine; returns (engine info, moves)."""

    def run(pgn: str, user_is_white: bool = True):
        game = parse_finished_game(pgn, MAX_PLIES)
        with open_engine(get_settings()) as engine:
            return engine.info, analyze_game(game, user_is_white, engine)

    return run
