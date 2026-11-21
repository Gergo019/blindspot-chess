"""POST /judge with the pinned Stockfish: complete, reproducible, and consistent with the findings.

The consistency checks read the golden /judge files (tests/golden/judge) instead of judging the
same positions again: test_judge_golden.py already proves that the live output equals them. The
user's mistake in a finding must be rejected, and the opponent's capture in the evidence line
must be accepted.
"""

import json
from pathlib import Path

import chess
import pytest
from fastapi.testclient import TestClient

from app.main import app

pytestmark = pytest.mark.engine

client = TestClient(app)
GOLDEN = Path(__file__).parent / "golden"


def judged(fen: str) -> dict:
    response = client.post("/judge", json={"fen": fen})
    assert response.status_code == 200, response.text
    return response.json()


def finding_positions(game: str) -> tuple[chess.Board, chess.Move, chess.Move]:
    """The position before the finding's move, the move, and the opponent's first evidence move."""
    response = json.loads((GOLDEN / f"{game}.json").read_text())["response"]
    (finding,) = response["findings"]
    move = response["moves"][finding["ply"] - 1]
    capture = chess.Move.from_uci(finding["evidenceLineUci"].split()[0])
    return chess.Board(move["fenBefore"]), chess.Move.from_uci(move["moveUci"]), capture


def test_every_legal_move_gets_one_verdict_and_the_best_drops_nothing():
    board = chess.Board()
    body = judged(board.fen())
    assert sorted(v["uci"] for v in body["verdicts"]) == sorted(m.uci() for m in board.legal_moves)
    assert body["verdicts"][0]["drop"] == 0
    assert body["sideToMove"] == "WHITE"
    assert body["acceptBelow"] == 0.1
    assert body["engine"]["depth"] == 16


def test_a_second_safe_move_is_accepted():
    accepted = {v["uci"] for v in judged(chess.STARTING_FEN)["verdicts"] if v["accepted"]}
    assert {"e2e4", "d2d4"} <= accepted


def test_the_same_position_gives_the_same_verdicts_twice():
    fen = "6k1/5ppp/8/8/8/2n5/5PPP/R5K1 w - - 0 1"
    assert judged(fen) == judged(fen)


def golden_verdicts(fen: str) -> dict[str, dict]:
    for f in (GOLDEN / "judge").glob("*.json"):
        golden = json.loads(f.read_text())
        if golden["request"]["fen"] == fen:
            return {v["uci"]: v for v in golden["response"]["verdicts"]}
    raise AssertionError(f"no golden /judge file for {fen}")


@pytest.mark.parametrize("game", ["008j6z3b", "008pkspi"])
def test_the_mistake_of_a_finding_is_rejected(game):
    board, mistake, _ = finding_positions(game)
    verdict = golden_verdicts(board.fen())[mistake.uci()]
    assert not verdict["accepted"]
    assert verdict["drop"] >= 0.2  # it was classified at least MISTAKE
    assert verdict["refutationUci"]  # a wrong answer has board evidence


@pytest.mark.parametrize("game", ["008j6z3b", "008pkspi"])
def test_after_the_mistake_the_opponents_capture_is_accepted(game):
    # The "See the capture" task (ADR 0008): the opponent to move, the evidence line's first move.
    board, mistake, capture = finding_positions(game)
    board.push(mistake)
    assert golden_verdicts(board.fen())[capture.uci()]["accepted"]


def test_a_mating_move_is_best_with_an_empty_refutation():
    body = judged("6k1/5ppp/8/8/8/8/5PPP/R5K1 w - - 0 1")
    best = body["verdicts"][0]
    assert best["uci"] == "a1a8"
    assert best["score"] == {"mate": 1}
    assert best["refutationUci"] == ""
