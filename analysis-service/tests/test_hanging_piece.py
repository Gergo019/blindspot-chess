"""Hanging-piece detector v0.1: pure tests with hand-written positions and lines.

Each case gives the position before the user's move, the move, its
classification and a hand-written principal variation from the position after
it. No engine is involved.
"""

import chess
import pytest

from app.classify import Classification
from app.detectors.hanging_piece import (
    DETECTOR_VERSION,
    detect,
    material_balance,
)

BLUNDER = Classification.BLUNDER
MISTAKE = Classification.MISTAKE


def run(fen: str, move: str, classification: Classification, pv: str):
    board = chess.Board(fen)
    return detect(
        board,
        chess.Move.from_uci(move),
        classification,
        [chess.Move.from_uci(m) for m in pv.split()],
    )


# White's knight f3 goes to d4, attacked by the e5 pawn and not defended
# (the d2 pawn blocks the queen).
KNIGHT_TO_PAWN = ("rnbqkbnr/pppp1ppp/8/4p3/8/5N2/PPPPPPPP/RNBQKB1R w KQkq - 0 2", "f3d4")
KNIGHT_TO_PAWN_PV = "e5d4 d2d3 g8f6 c1g5"


class TestFires:
    def test_knight_moved_to_a_square_attacked_by_a_pawn(self):
        finding = run(*KNIGHT_TO_PAWN, BLUNDER, KNIGHT_TO_PAWN_PV)
        assert finding is not None
        assert (finding.piece, finding.square, finding.captured_by) == ("knight", "d4", "pawn")
        assert finding.material_lost == 3
        assert finding.evidence_line_uci == KNIGHT_TO_PAWN_PV
        assert finding.detector_version == DETECTOR_VERSION == "hanging-0.1"
        assert finding.motif == "HANGING_PIECE"

    def test_rook_defended_once_but_attacked_by_a_bishop_loses_the_exchange(self):
        # Rc4 is defended by the b3 pawn; Bxc4 bxc4 wins rook for bishop: 2 points.
        finding = run("4k3/8/4b3/8/8/1P6/8/2R1K3 w - - 0 1", "c1c4", MISTAKE, "e6c4 b3c4 e8d7 e1d2")
        assert finding is not None
        assert (finding.piece, finding.square, finding.captured_by) == ("rook", "c4", "bishop")
        assert finding.material_lost == 2

    def test_discovered_loss_moving_one_piece_exposes_another(self):
        # Ne4 shields the queen on e2 from the e8 rook; moving it loses the queen for the rook.
        finding = run("4r1k1/8/8/8/4N3/8/4Q3/4K3 w - - 0 1", "e4c5", BLUNDER, "e8e2 e1e2 g8f7 e2e3")
        assert finding is not None
        assert (finding.piece, finding.square, finding.captured_by) == ("queen", "e2", "rook")
        assert finding.material_lost == 4

    def test_black_user_is_judged_from_blacks_side(self):
        # Mirror of the knight case: Black's knight f6 goes to d5, attacked by the e4 pawn.
        fen = "rnbqkb1r/pppppppp/5n2/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 2"
        finding = run(fen, "f6d5", BLUNDER, "e4d5 d7d6 g1f3 c8g4")
        assert finding is not None
        assert (finding.piece, finding.square, finding.captured_by) == ("knight", "d5", "pawn")
        assert finding.material_lost == 3

    def test_short_line_uses_the_plies_available(self):
        finding = run(*KNIGHT_TO_PAWN, BLUNDER, "e5d4")
        assert finding is not None
        assert finding.evidence_line_uci == "e5d4"

    def test_only_the_first_four_plies_are_used(self):
        finding = run(*KNIGHT_TO_PAWN, BLUNDER, KNIGHT_TO_PAWN_PV + " b8c6 b1c3")
        assert finding.evidence_line_uci == KNIGHT_TO_PAWN_PV


class TestDoesNotFire:
    def test_even_trade_bishop_for_knight(self):
        # User BxN, opponent bxc6: material is level, even when the trade was judged a mistake.
        assert run("4k3/1p6/2n5/1B6/8/8/8/4K3 w - - 0 1", "b5c6", MISTAKE, "b7c6 e1e2 e8e7 e2e3") is None

    def test_engine_approved_sacrifice(self):
        # Same material story as the hanging knight, but the engine calls the move BEST.
        assert run(*KNIGHT_TO_PAWN, Classification.BEST, KNIGHT_TO_PAWN_PV) is None

    @pytest.mark.parametrize("classification", [Classification.GOOD, Classification.INACCURACY])
    def test_moves_below_mistake_are_not_checked(self, classification):
        assert run(*KNIGHT_TO_PAWN, classification, KNIGHT_TO_PAWN_PV) is None

    def test_hanging_pawn_only(self):
        assert run("4k3/8/8/3p4/8/8/4P3/4K3 w - - 0 1", "e2e4", MISTAKE, "d5e4 e1e2 e8e7 e2e3") is None

    def test_mistake_without_material_loss(self):
        # 1. f3 judged a mistake: the engine reply is a quiet developing move.
        assert run(chess.STARTING_FEN, "f2f3", MISTAKE, "e7e5 e2e4 b8c6 b1c3") is None

    def test_loose_looking_piece_whose_capture_the_engine_does_not_play(self):
        # The d4 knight can be taken, but the engine's reply is something else
        # (taking would lose to a tactic), so the PV does not start with the capture.
        assert run(*KNIGHT_TO_PAWN, MISTAKE, "d8g5 d2d3 g5g2 h1g1") is None

    def test_line_that_ends_in_forced_mate(self):
        # Qa2 abandons the e1 rook; Rxe1 is back-rank mate, so this is a mating motif, not a hanging piece.
        # (Without the mate rule this would be a 5-point loss.)
        assert run("4r1k1/5ppp/8/8/8/8/3Q1PPP/4R1K1 w - - 0 1", "d2a2", BLUNDER, "e8e1") is None

    def test_capture_then_mate_later_in_the_window(self):
        # Ra7 leaves the back rank: Qxe4 wins the knight (3 points), then Qe1 is mate on ply 3.
        assert run("6k1/5ppp/8/3q4/4N3/8/5PPP/R5K1 w - - 0 1", "a1a7", BLUNDER, "d5e4 a7b7 e4e1") is None

    def test_capture_of_the_opponents_own_piece_is_not_a_user_loss(self):
        # The PV starts with a capture, but of a piece that is not the user's: not this motif.
        assert run("4k3/8/8/3p4/4P3/8/8/4K3 w - - 0 1", "e1e2", MISTAKE, "d5e4 e2e3") is None

    def test_no_principal_variation(self):
        assert run(*KNIGHT_TO_PAWN, BLUNDER, "") is None


class TestInputs:
    def test_illegal_move_raises(self):
        with pytest.raises(ValueError):
            run(*KNIGHT_TO_PAWN[:1], "f3f5", BLUNDER, KNIGHT_TO_PAWN_PV)

    def test_illegal_line_raises(self):
        with pytest.raises(ValueError):
            run(*KNIGHT_TO_PAWN, BLUNDER, "e5d4 d2d3 d4d3 c1g5")

    def test_material_balance(self):
        board = chess.Board()
        assert material_balance(board, chess.WHITE) == 0
        board.remove_piece_at(chess.D8)
        assert material_balance(board, chess.WHITE) == 9
        assert material_balance(board, chess.BLACK) == -9
