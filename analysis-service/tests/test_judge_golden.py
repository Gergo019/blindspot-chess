"""Reproducibility: POST /judge for fixed positions equals the checked-in golden files.

The positions are the two golden demo findings, each before the user's mistake ("Find a safe move")
and after it ("See the capture"), so both sides to move are covered (ADR 0008). Same image and
engine settings must give identical output (ADR 0002). Regenerate only on purpose
(tests/golden/regenerate.py) and explain the change in the devlog.
"""

import json
from pathlib import Path

import chess
import pytest
from fastapi.testclient import TestClient

from app.main import app

pytestmark = pytest.mark.engine

GOLDEN = sorted((Path(__file__).parent / "golden" / "judge").glob("*.json"))
client = TestClient(app)


def test_there_are_golden_positions_for_both_sides_to_move():
    sides = {json.loads(f.read_text())["response"]["sideToMove"] for f in GOLDEN}
    assert sides == {"WHITE", "BLACK"}


def drill_positions() -> set[str]:
    """Both drill positions of every golden /analyze finding: before the mistake and after it."""
    out = set()
    for f in (Path(__file__).parent / "golden").glob("*.json"):
        response = json.loads(f.read_text())["response"]
        for finding in response["findings"]:
            move = response["moves"][finding["ply"] - 1]
            board = chess.Board(move["fenBefore"])
            out.add(board.fen())
            board.push(chess.Move.from_uci(move["moveUci"]))
            out.add(board.fen())
    return out


def test_every_golden_position_is_a_drill_position_of_a_golden_finding():
    # The positions are derived, never typed: each one is a golden finding's position before or after the mistake.
    requested = {json.loads(f.read_text())["request"]["fen"] for f in GOLDEN}
    assert requested == drill_positions()


@pytest.mark.parametrize("golden", GOLDEN, ids=lambda f: f.stem)
def test_judge_output_equals_the_golden_file(golden):
    expected = json.loads(golden.read_text())
    response = client.post("/judge", json=expected["request"])
    assert response.status_code == 200
    actual = response.json()
    assert actual["engine"] == expected["response"]["engine"]
    for got, want in zip(actual["verdicts"], expected["response"]["verdicts"], strict=True):
        assert got == want, f"{want['uci']} differs"
    assert actual == expected["response"]
