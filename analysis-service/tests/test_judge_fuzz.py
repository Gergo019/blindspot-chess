"""Property-based: whatever arrives as a FEN, /judge answers 200 or 422, never 500.

Inputs: random text, mutations of real FENs (characters dropped, replaced or inserted), and legal
positions reached by random play. Positions that survive are judged, so this needs Stockfish
(Docker image). The property is about errors and completeness, not judging quality, so the engine
searches at depth 1 here, like test_api_fuzz.py.
"""

import dataclasses

import chess
import pytest
from fastapi.testclient import TestClient
from hypothesis import HealthCheck, given, settings
from hypothesis import strategies as st

from app.config import Settings, get_settings
from app.main import app

pytestmark = pytest.mark.engine

client = TestClient(app, raise_server_exceptions=False)

FENS = [
    chess.STARTING_FEN,
    "r1b1kbnr/pppp1ppp/2n5/8/3NP2q/8/PPP2PPP/RNBQKB1R w KQkq - 1 5",  # golden finding, 008j6z3b
    "1r2r1k1/p2b1p1p/Q1n1qnp1/4p3/1PN5/P2P1N2/4BPPP/R4RK1 b - - 4 18",  # golden finding, 008pkspi
    "rnbqkbnr/ppp1p1pp/8/3pPp2/8/8/PPPP1PPP/RNBQKBNR w KQkq f6 0 3",  # en passant
    "8/P6k/8/8/8/8/8/K7 w - - 0 1",  # promotion
]
FUZZ = settings(max_examples=40, deadline=None, derandomize=True, suppress_health_check=[HealthCheck.too_slow])


def setup_module():
    shallow = dataclasses.replace(Settings.from_env(), depth=1)
    app.dependency_overrides[get_settings] = lambda: shallow


def teardown_module():
    app.dependency_overrides.clear()


def assert_never_500(fen: str) -> None:
    response = client.post("/judge", json={"fen": fen})
    assert response.status_code in (200, 422), (response.status_code, fen, response.text[:300])
    if response.status_code == 200:
        board = chess.Board(fen)
        verdicts = response.json()["verdicts"]
        assert len(verdicts) == board.legal_moves.count()
        assert verdicts[0]["drop"] == 0


@FUZZ
@given(st.text(max_size=120))
def test_random_text(fen):
    assert_never_500(fen)


@FUZZ
@given(
    st.sampled_from(FENS),
    st.lists(
        st.tuples(st.sampled_from(["drop", "replace", "insert"]), st.integers(0, 99), st.sampled_from("pnbrqkPNBRQK12345678/ -wKQkqabcdefgh0")),
        min_size=1,
        max_size=4,
    ),
)
def test_mutated_fens(fen, edits):
    chars = list(fen)
    for kind, index, char in edits:
        i = index % (len(chars) + 1)
        if kind == "drop" and i < len(chars):
            del chars[i]
        elif kind == "replace" and i < len(chars):
            chars[i] = char
        else:
            chars.insert(i, char)
    assert_never_500("".join(chars))


@FUZZ
@given(st.lists(st.integers(0, 10_000), max_size=60))
def test_positions_reached_by_random_play(choices):
    board = chess.Board()
    for choice in choices:
        moves = sorted(board.legal_moves, key=lambda move: move.uci())
        if not moves or board.outcome(claim_draw=False):
            break
        board.push(moves[choice % len(moves)])
    assert_never_500(board.fen())
