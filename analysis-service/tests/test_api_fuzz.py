"""Property-based: whatever text arrives as a PGN, /analyze answers 200 or 422, never 500.

Inputs: random text, every truncation of real games, huge (and unclosed) comments, junk headers.
Valid games that survive the mutation are analysed, so this needs Stockfish (Docker image). The
property is about errors, not analysis quality, so the engine searches at depth 1 here.
"""

import dataclasses

import pytest
from fastapi.testclient import TestClient
from hypothesis import HealthCheck, given, settings
from hypothesis import strategies as st

from app.config import Settings, get_settings
from app.main import app
from app.models import MAX_PGN_CHARS
from tests.games import BLACKBURNE_SHILLING, FOOLS_MATE, HANGS_QUEEN, LOYD_STALEMATE

pytestmark = pytest.mark.engine

client = TestClient(app, raise_server_exceptions=False)


def setup_module():
    shallow = dataclasses.replace(Settings.from_env(), depth=1)
    app.dependency_overrides[get_settings] = lambda: shallow


def teardown_module():
    app.dependency_overrides.clear()
GAMES = [FOOLS_MATE, HANGS_QUEEN, BLACKBURNE_SHILLING, LOYD_STALEMATE]
FUZZ = settings(
    max_examples=60,
    deadline=None,
    derandomize=True,
    suppress_health_check=[HealthCheck.too_slow, HealthCheck.data_too_large],
)


def assert_never_500(pgn: str) -> None:
    response = client.post("/analyze", json={"pgn": pgn, "userColor": "WHITE"})
    assert response.status_code in (200, 422), (response.status_code, pgn[:200], response.text[:300])


@FUZZ
@given(st.text(max_size=400))
def test_random_text(pgn):
    assert_never_500(pgn)


@FUZZ
@given(st.sampled_from(GAMES).flatmap(lambda game: st.integers(0, len(game)).map(lambda n: game[:n])))
def test_truncated_games(pgn):
    assert_never_500(pgn)


@FUZZ
@given(
    st.sampled_from(GAMES),
    # Mostly below the request limit, so the parser sees them; some above it (pydantic answers 422).
    st.integers(0, MAX_PGN_CHARS + 1_000),
    st.booleans(),
    st.text(alphabet="x{};\n\\\"[]", max_size=20),
)
def test_huge_comments(game, size, closed, junk):
    first_move_end = game.index(" ", game.index(".") + 2)
    comment = "{" + "x" * size + junk + ("}" if closed else "")
    assert_never_500(game[:first_move_end] + " " + comment + game[first_move_end:])


@FUZZ
@given(
    st.sampled_from(GAMES),
    st.lists(st.tuples(st.text(max_size=20), st.text(max_size=40)), max_size=8),
)
def test_junk_headers(game, headers):
    tags = "".join(f'[{key} "{value}"]\n' for key, value in headers)
    assert_never_500(tags + "\n" + game)


@pytest.mark.parametrize(
    "pgn",
    [
        "1. e4 -- 2. d4 -- 3. Nf3 e5 1-0",
        '[SetUp "1"]\n[FEN "4k3/8/8/8/8/8/8/8 b - - 0 1"]\n[Result "1-0"]\n\n1... Kd7 1-0',
        '[SetUp "1"]\n[FEN "not a fen"]\n[Result "1-0"]\n\n1. e4 1-0',
        '[Result "1-0"]\n\n1. e4 e5 1-0\n\n[Result "0-1"]\n\n1. d4 d5 0-1',
        '[Result "1-0"]\n\n1. e4 e5 *',
        FOOLS_MATE.replace("0-1", "3. a3 0-1"),
    ],
)
def test_the_reviews_examples_are_422(pgn):
    response = client.post("/analyze", json={"pgn": pgn, "userColor": "WHITE"})
    assert response.status_code == 422, response.text
