"""Hanging-piece detector with the real engine, on short fixed games.

Assertions hold at any reasonable depth: which move hangs which piece, never
exact centipawns or exact engine lines beyond the capture.
"""

import chess
import pytest

from tests.games import FOOLS_MATE, HANGS_QUEEN, HANGS_QUEEN_PLY

pytestmark = pytest.mark.engine

# 3. Nd4?? puts the f3 knight where the e5 pawn takes it; d2 blocks the queen.
HANGS_KNIGHT = "1. e4 e5 2. Nf3 d6 3. Nd4 exd4 0-1"
HANGS_KNIGHT_PLY = 5


def replay(moves, ply: int, evidence: str) -> chess.Board:
    """Play the game up to and including ``ply``, then the evidence line; every move must be legal."""
    board = chess.Board()
    for m in moves[:ply]:
        board.push_uci(m.move_uci)
    for uci in evidence.split():
        move = chess.Move.from_uci(uci)
        assert move in board.legal_moves, f"{uci} is not legal in {board.fen()}"
        board.push(move)
    return board


def test_knight_moved_into_a_pawn_capture(analyze_full):
    result = analyze_full(HANGS_KNIGHT, user_is_white=True)
    assert len(result.findings) == 1
    finding = result.findings[0]
    assert finding.ply == HANGS_KNIGHT_PLY
    assert finding.motif == "HANGING_PIECE"
    assert finding.detector_version == "hanging-0.2"
    assert (finding.facts.piece, finding.facts.square, finding.facts.captured_by) == ("knight", "d4", "pawn")
    assert finding.facts.material_lost >= 2
    assert (finding.facts.moved_piece, finding.facts.moved_from, finding.facts.moved_to) == ("knight", "f3", "d4")
    assert finding.evidence_line_uci.split()[0] == "e5d4"
    assert 1 <= len(finding.evidence_line_uci.split()) <= 8
    replay(result.moves, finding.ply, finding.evidence_line_uci)


def test_queen_moved_into_a_bishop_capture(analyze_full):
    result = analyze_full(HANGS_QUEEN, user_is_white=True)
    [finding] = result.findings
    assert finding.ply == HANGS_QUEEN_PLY
    assert (finding.facts.piece, finding.facts.square, finding.facts.captured_by) == ("queen", "g4", "bishop")
    assert finding.facts.material_lost >= 6  # a queen for at most a minor piece within four plies
    replay(result.moves, finding.ply, finding.evidence_line_uci)


def test_only_the_users_moves_are_checked(analyze_full):
    # Same game seen from Black: White's blunder is the opponent's, not a finding.
    assert analyze_full(HANGS_QUEEN, user_is_white=False).findings == []


def test_allowing_mate_is_not_a_hanging_piece(analyze_full):
    # 2. g4?? is a blunder, but the engine's reply is Qh4#, not a capture.
    result = analyze_full(FOOLS_MATE, user_is_white=True)
    assert result.moves[2].classification == "BLUNDER"
    assert result.findings == []


def test_findings_are_deterministic(analyze_full):
    first = analyze_full(HANGS_KNIGHT)
    second = analyze_full(HANGS_KNIGHT)
    assert [f.model_dump() for f in first.findings] == [f.model_dump() for f in second.findings]
