"""Hanging-piece detector: pure tests with hand-written positions and lines.

Each case gives the position before the user's move, the move, its
classification, a hand-written principal variation from the position after it
(the played line, after the move), the engine's best line from the position
before it and, where it matters, the engine score after it. Lines marked
"Stockfish 19" are the engine's own lines at depth 16. No engine is involved.
"""

import chess
import pytest

from app.classify import Classification
from app.detectors import hanging_piece
from app.detectors.hanging_piece import (
    DETECTOR_VERSION,
    detect,
    is_mate_against,
    material_balance,
)
from app.models import Score

BLUNDER = Classification.BLUNDER
MISTAKE = Classification.MISTAKE


def moves(line: str) -> list[chess.Move]:
    return [chess.Move.from_uci(m) for m in line.split()]


def run(fen: str, move: str, classification: Classification, pv: str, score: Score | None = None, *, best: str):
    """``pv`` follows the move; ``best`` is the best line before it; ``score`` is the engine score after
    the move (White's view), None = not a mate."""
    board = chess.Board(fen)
    return detect(board, chess.Move.from_uci(move), classification, moves(pv), score, moves(best))


# White's knight f3 goes to d4, attacked by the e5 pawn and not defended
# (the d2 pawn blocks the queen).
KNIGHT_TO_PAWN = ("rnbqkbnr/pppp1ppp/8/4p3/8/5N2/PPPPPPPP/RNBQKB1R w KQkq - 0 2", "f3d4")
KNIGHT_TO_PAWN_PV = "e5d4 d2d3 g8f6 c1g5"
# A quiet developing line that keeps the material: what White should have played.
KNIGHT_TO_PAWN_BEST = "b1c3 b8c6 e2e3 g8f6 f1e2 f8e7 e1g1 e8g8"


class TestFires:
    def test_knight_moved_to_a_square_attacked_by_a_pawn(self):
        finding = run(*KNIGHT_TO_PAWN, BLUNDER, KNIGHT_TO_PAWN_PV, best=KNIGHT_TO_PAWN_BEST)
        assert finding is not None
        assert (finding.piece, finding.square, finding.captured_by) == ("knight", "d4", "pawn")
        assert finding.material_lost == 3
        # The window is 4 plies from the position before the move: Nd4 and three PV plies. The evidence
        # line is the PV part.
        assert finding.evidence_line_uci == "e5d4 d2d3 g8f6"
        assert finding.detector_version == DETECTOR_VERSION == "hanging-0.4"
        assert finding.motif == "HANGING_PIECE"
        # The moved piece is the one that is lost.
        assert (finding.moved_piece, finding.moved_from, finding.moved_to) == ("knight", "f3", "d4")

    def test_rook_defended_once_but_attacked_by_a_bishop_loses_the_exchange(self):
        # Rc4 is defended by the b3 pawn; Bxc4 bxc4 wins rook for bishop: 2 points.
        finding = run("4k3/8/4b3/8/8/1P6/8/2R1K3 w - - 0 1", "c1c4", MISTAKE, "e6c4 b3c4 e8d7 e1d2",
                      best="e1d2 e8d7 d2d3 d7d6")
        assert finding is not None
        assert (finding.piece, finding.square, finding.captured_by) == ("rook", "c4", "bishop")
        assert finding.material_lost == 2

    def test_discovered_loss_moving_one_piece_exposes_another(self):
        # Ne4 shields the queen on e2 from the e8 rook; moving it loses the queen for the rook.
        finding = run("4r1k1/8/8/8/4N3/8/4Q3/4K3 w - - 0 1", "e4c5", BLUNDER, "e8e2 e1e2 g8f7 e2e3",
                      best="e1f1 g8f7 f1g1 f7g8")
        assert finding is not None
        assert (finding.piece, finding.square, finding.captured_by) == ("queen", "e2", "rook")
        assert finding.material_lost == 4
        # The piece that moved is not the piece that is lost.
        assert (finding.moved_piece, finding.moved_from, finding.moved_to) == ("knight", "e4", "c5")
        assert finding.moved_piece != finding.piece

    def test_black_user_is_judged_from_blacks_side(self):
        # Mirror of the knight case: Black's knight f6 goes to d5, attacked by the e4 pawn.
        fen = "rnbqkb1r/pppppppp/5n2/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 2"
        finding = run(fen, "f6d5", BLUNDER, "e4d5 d7d6 g1f3 c8g4", best="b8c6 g1f3 e7e6 b1c3")
        assert finding is not None
        assert (finding.piece, finding.square, finding.captured_by) == ("knight", "d5", "pawn")
        assert finding.material_lost == 3

    def test_a_short_line_that_ends_quietly_uses_the_plies_available(self):
        finding = run(*KNIGHT_TO_PAWN, BLUNDER, "e5d4 d2d3", best=KNIGHT_TO_PAWN_BEST)
        assert finding is not None
        assert finding.evidence_line_uci == "e5d4 d2d3"

    def test_only_four_plies_are_used_when_the_fourth_is_quiet(self):
        finding = run(*KNIGHT_TO_PAWN, BLUNDER, KNIGHT_TO_PAWN_PV + " b8c6 b1c3", best=KNIGHT_TO_PAWN_BEST)
        assert finding.evidence_line_uci == "e5d4 d2d3 g8f6"

    def test_a_mate_score_for_the_user_does_not_block_the_finding(self):
        finding = run(*KNIGHT_TO_PAWN, BLUNDER, KNIGHT_TO_PAWN_PV, Score(mate=5), best=KNIGHT_TO_PAWN_BEST)
        assert finding is not None


# Review A2 (2026-11-14): Black has just taken a knight on f3. Qxf3 instead of gxf3 drops the guard of d4.
# Stockfish 19, depth 16: before +617 (best gxf3), after Qxf3 0.0, a BLUNDER.
QXF3 = ("3q2k1/ppp2ppp/8/8/3B4/4Qb2/PPP2PPP/6K1 w - - 0 1", "e3f3")
QXF3_PV = "d8d4 g2g3 d4b2 f3e3 g7g6 e3a7 b2c2 a7b7"
QXF3_BEST = "g2f3 h7h6 e3e5 f7f6 e5e4 d8d7 h2h4 g8h8"


class TestBaseline:
    """hanging-0.3: the loss is measured against the engine's best line, not the balance before the move."""

    def test_a_piece_lost_inside_an_exchange_is_found(self):
        # The balance before Qxf3 is level and so is the balance after Qxf3 Qxd4: hanging-0.2 saw no
        # loss. The best line wins the f3 bishop (+3); the played line ends a pawn down (-1).
        finding = run(*QXF3, BLUNDER, QXF3_PV, best=QXF3_BEST)
        assert finding is not None
        assert (finding.piece, finding.square, finding.captured_by) == ("bishop", "d4", "queen")
        # Bishop and the b2 pawn: the window runs to ply 5 (Qxb2 on ply 4 is a capture).
        assert finding.material_lost == 4
        assert finding.evidence_line_uci == "d8d4 g2g3 d4b2 f3e3"
        assert (finding.moved_piece, finding.moved_from, finding.moved_to) == ("queen", "e3", "f3")

    def test_the_hung_bishop_alone_is_a_loss_of_three(self):
        # The same position with a played line in which Black does not take the b2 pawn.
        finding = run(*QXF3, BLUNDER, "d8d4 g2g3 g7g6 f3e3", best=QXF3_BEST)
        assert finding is not None
        assert finding.material_lost == 3

    def test_a_piece_lost_in_every_line_is_not_blamed_on_the_move(self):
        # The c4 rook is attacked by the e6 bishop. In this hand-written best line it is lost for the
        # bishop as well (2 points), so a move that loses the same material is not blamed for it.
        # Against the balance before the move (hanging-0.2) this was a loss of 2 and fired.
        fen = "4k3/8/4b3/8/2R5/1P6/8/4K3 w - - 0 1"
        assert run(fen, "e1d2", MISTAKE, "e6c4 b3c4 e8d7 d2d3", best="e1e2 e6c4 b3c4 e8d7") is None


# Nd5 starts a long exchange on d5: cxd5 exd5 Nxd5 Bxd5 leaves material level
# after 4 plies, but Rxd5 on ply 5 wins the bishop and White cannot recapture.
LONG_EXCHANGE = ("3r3k/8/2p2n2/8/4P3/1BN5/8/6K1 w - - 0 1", "c3d5")
LONG_EXCHANGE_PV = "c6d5 e4d5 f6d5 b3d5 d8d5 g1f2 h8g7 f2e3"


LONG_EXCHANGE_BEST = "g1f2 h8g7 f2e3 g7g6 b3c2 g6g5 c2b3"


class TestWindow:
    def test_exchange_is_judged_after_the_captures_end(self):
        finding = run(*LONG_EXCHANGE, MISTAKE, LONG_EXCHANGE_PV, best=LONG_EXCHANGE_BEST)
        assert finding is not None
        # From the position before Nd5: Nd5 and plies 2 to 6 are captures, ply 7 (Kf2) is quiet.
        assert finding.evidence_line_uci == "c6d5 e4d5 f6d5 b3d5 d8d5 g1f2"
        assert finding.material_lost == 3

    def test_a_line_that_runs_out_mid_exchange_is_not_judged(self):
        # Review A7: the PV ends on a capture, so the exchange may not be over. hanging-0.2 judged it
        # anyway (and asserted that as intended); hanging-0.3 reports nothing.
        assert run(*LONG_EXCHANGE, MISTAKE, "c6d5 e4d5 f6d5 b3d5", best=LONG_EXCHANGE_BEST) is None

    def test_a_hand_written_line_cut_mid_exchange_does_not_fire(self):
        # Review A7: Ke2 with the one-ply line Nxb5 fired with loss 3 in hanging-0.2. The PV ran out on a
        # capture, so the recapture a4xb5 is unknown: no finding.
        position = ("6k1/2n5/8/1B6/P7/8/8/4K3 w - - 0 1", "e1e2")
        best = "b5c4 g8f8 e1f2 f8e7 f2g3 e7d6 g3h4 d6c6"  # Stockfish 19
        assert run(*position, BLUNDER, "c7b5", best=best) is None
        # With the recapture the exchange is even: still no finding.
        assert run(*position, BLUNDER, "c7b5 a4b5 g8f7 e2d3", best=best) is None

    def test_a_check_and_its_reply_are_one_unit(self):
        # Ra2 drops the e4 knight; ply 4 is the check Re1+ and ply 5 the reply Bf1. The window goes on
        # past the reply, reaches Rxf1# on ply 6 and, a mate against the user, reports nothing, even
        # without a mate score.
        position = ("4r1k1/5ppp/b7/8/2B1N3/8/4RPPP/6K1 w - - 0 1", "e2a2")
        assert run(*position, BLUNDER, "e8e4 a2a3 e4e1 c4f1 e1f1", Score(cp=-350), best="h2h3 h7h6 g1h2 g8h7") is None

    def test_the_best_line_is_not_cut_between_a_check_reply_and_the_recapture(self):
        # Demo game 008pkspi, ply 36, Black (Stockfish 19 lines). The best line Nd4 Qxe6 Nxe2+ Kh1 Bxe6
        # recaptures the queen on ply 5, after the reply Kh1. Cut at Kh1 (capture-or-check only), the
        # best line looked 7 points down and the finding vanished.
        fen = "1r2r1k1/p2b1p1p/Q1n1qnp1/4p3/1PN5/P2P1N2/4BPPP/R4RK1 b - - 4 18"
        played = "c4b6 a7b6 f1e1 f6d5 e2f1 e6f6 a1c1 g8g7"
        best = "c6d4 a6e6 d4e2 g1h1 d7e6 c4e5 b8c8 f1e1"
        finding = run(fen, "b8b6", BLUNDER, played, best=best)
        assert finding is not None
        assert (finding.piece, finding.square, finding.captured_by) == ("rook", "b6", "knight")
        # Played line: rook for knight (2). Best line: a pawn up at the end (+1 from -1). Loss 4.
        assert finding.material_lost == 4
        assert finding.evidence_line_uci == "c4b6 a7b6 f1e1 f6d5 e2f1 e6f6"

    def test_the_extension_stops_at_the_cap_and_a_capture_there_means_no_finding(self, monkeypatch):
        # The cap is 8 plies; lower it to keep the line short. At 6 plies the window ends on Rxd5, a
        # capture, so the exchange is unfinished: no finding. At 7 it ends on the quiet Kf2.
        assert hanging_piece.MAX_WINDOW_PLIES == 8
        monkeypatch.setattr(hanging_piece, "MAX_WINDOW_PLIES", 6)
        assert run(*LONG_EXCHANGE, MISTAKE, LONG_EXCHANGE_PV, best=LONG_EXCHANGE_BEST) is None
        monkeypatch.setattr(hanging_piece, "MAX_WINDOW_PLIES", 7)
        assert run(*LONG_EXCHANGE, MISTAKE, LONG_EXCHANGE_PV, best=LONG_EXCHANGE_BEST) is not None

    def test_a_check_in_the_best_line_lengthens_both_windows(self):
        # Equal length: the best line's ply 4 is the check ...Bb4+ and ply 5 its reply c3, so the window
        # grows past 4 plies for the played line too (which has five plies here, the last one quiet).
        best = "e2e4 g8f6 d2d3 f8b4 c2c3 e8g8 f1e2 d7d6"
        finding = run(*KNIGHT_TO_PAWN, BLUNDER, KNIGHT_TO_PAWN_PV, best=best)
        assert finding.evidence_line_uci == "e5d4 d2d3 g8f6 c1g5"
        assert finding.material_lost == 3

    def test_qc2_is_a_known_miss(self):
        # Review A4: Qc2 loses the queen after Rxf1+ Kxf1 Ne3+ Ke2 Nxc2 (Stockfish 19 lines). The first
        # PV move captures a rook, so the case belongs to HANGING_PIECE. But both lines are forcing to
        # the 8-ply cap: the played line ends on the check Ne1+, the best line on the reply Bc8 to
        # Qb8+. Unfinished, so no finding. A known miss of hanging-0.3 (ADR 0004): it needs a longer
        # window and more PV plies (#41).
        fen = "5rk1/pb4pp/8/3n4/8/8/PP4PP/3Q1RK1 w - - 0 1"
        played = "f8f1 g1f1 d5e3 f1e2 e3c2 e2d3 c2e1 d3d2"
        best = "f1f8 g8f8 d1g4 d5e7 g4f4 f8e8 f4b8 b7c8"
        assert run(fen, "d1c2", BLUNDER, played, best=best) is None


class TestDoesNotFire:
    def test_even_trade_bishop_for_knight(self):
        # User BxN, opponent bxc6: material is level, even when the trade was judged a mistake.
        assert run("4k3/1p6/2n5/1B6/8/8/8/4K3 w - - 0 1", "b5c6", MISTAKE, "b7c6 e1e2 e8e7 e2e3",
                   best="e1e2 e8e7 e2e3 e7e6") is None

    def test_engine_approved_sacrifice(self):
        # Same material story as the hanging knight, but the engine calls the move BEST.
        assert run(*KNIGHT_TO_PAWN, Classification.BEST, KNIGHT_TO_PAWN_PV, best=KNIGHT_TO_PAWN_BEST) is None

    @pytest.mark.parametrize("classification", [Classification.GOOD, Classification.INACCURACY])
    def test_moves_below_mistake_are_not_checked(self, classification):
        assert run(*KNIGHT_TO_PAWN, classification, KNIGHT_TO_PAWN_PV, best=KNIGHT_TO_PAWN_BEST) is None

    def test_hanging_pawn_only(self):
        assert run("4k3/8/8/3p4/8/8/4P3/4K3 w - - 0 1", "e2e4", MISTAKE, "d5e4 e1e2 e8e7 e2e3", best="e1d2") is None

    def test_mistake_without_material_loss(self):
        # 1. f3 judged a mistake: the engine reply is a quiet developing move.
        assert run(chess.STARTING_FEN, "f2f3", MISTAKE, "e7e5 e2e4 b8c6 b1c3", best="e2e4") is None

    def test_loose_looking_piece_whose_capture_the_engine_does_not_play(self):
        # The d4 knight can be taken, but the engine's reply is something else
        # (taking would lose to a tactic), so the PV does not start with the capture.
        assert run(*KNIGHT_TO_PAWN, MISTAKE, "d8g5 d2d3 g5g2 h1g1", best=KNIGHT_TO_PAWN_BEST) is None

    def test_line_that_ends_in_forced_mate(self):
        # Qa2 abandons the e1 rook; Rxe1 is back-rank mate, so this is a mating motif, not a hanging piece.
        # (Without the mate rule this would be a 5-point loss.)
        assert run("4r1k1/5ppp/8/8/8/8/3Q1PPP/4R1K1 w - - 0 1", "d2a2", BLUNDER, "e8e1", best="h2h3") is None

    def test_capture_then_mate_later_in_the_window(self):
        # Ra7 leaves the back rank: Qxe4 wins the knight (3 points), then Qe1 is mate on ply 3.
        assert run("6k1/5ppp/8/3q4/4N3/8/5PPP/R5K1 w - - 0 1", "a1a7", BLUNDER, "d5e4 a7b7 e4e1",
                   best="h2h3 h7h6 g1h2 g8h7") is None

    def test_a_mate_score_against_the_user_blocks_a_quiet_window(self):
        # The window itself ends quietly and would show a lost knight; only the engine score after the
        # move (Black mates in 5) shows the mate. That is why the score is needed.
        assert run(*KNIGHT_TO_PAWN, BLUNDER, KNIGHT_TO_PAWN_PV, Score(mate=-5), best=KNIGHT_TO_PAWN_BEST) is None
        assert run(*KNIGHT_TO_PAWN, BLUNDER, KNIGHT_TO_PAWN_PV, Score(cp=-350), best=KNIGHT_TO_PAWN_BEST) is not None

    def test_mate_against_a_black_user(self):
        fen = "rnbqkb1r/pppppppp/5n2/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 2"
        assert run(fen, "f6d5", BLUNDER, "e4d5 d7d6 g1f3 c8g4", Score(mate=4), best="b8c6") is None

    def test_capture_of_the_opponents_own_piece_is_not_a_user_loss(self):
        # The PV starts with a capture, but of a piece that is not the user's: not this motif.
        assert run("4k3/8/8/3p4/4P3/8/8/4K3 w - - 0 1", "e1e2", MISTAKE, "d5e4 e2e3", best="e1d2") is None

    def test_no_principal_variation(self):
        assert run(*KNIGHT_TO_PAWN, BLUNDER, "", best=KNIGHT_TO_PAWN_BEST) is None

    def test_no_best_line(self):
        assert run(*KNIGHT_TO_PAWN, BLUNDER, KNIGHT_TO_PAWN_PV, best="") is None


class TestGuards:
    """hanging-0.3: a missed win is not a hung piece."""

    def test_a_quiet_move_followed_by_an_even_trade_does_not_fire(self):
        # Best: Bxh8 wins the rook (+5). Played: Kd2, then Nxd4 exd4, bishop for knight. Against the best
        # line that is a loss of 5, but the played line itself loses nothing (rule 4).
        fen = "4k2r/8/2n5/8/3B4/4P3/8/4K3 w - - 0 1"
        assert run(fen, "e1d2", BLUNDER, "c6d4 e3d4 e8e7 d2e3", best="d4h8 e8f7 e1e2 f7g8") is None

    def test_an_even_queen_trade_is_not_a_hung_piece(self):
        # Demo game 001c7wqq, ply 19, White (Stockfish 19 lines): Qxd8+ Bxd8 trades queens, the best move
        # Qxa8 wins a rook. Exchange on the square: queen for queen, 0. No finding.
        fen = "rn1qk2r/p1p1bppp/1p2b3/3Q4/3P4/8/PPP2PPP/R1B1KBNR w KQkq - 1 10"
        played = "e7d8 c1f4 b8c6 e1c1 c6e7 g1f3 e8g8 f1d3"
        best = "d5a8 e6d5 a8a7 e8g8 a7a4 c7c5 g1f3 b8c6"
        assert run(fen, "d5d8", BLUNDER, played, best=best) is None


class TestExchangeOnTheSquare:
    """M captures and the opponent recaptures on the same square: only that exchange is judged."""

    def test_a_knight_that_takes_a_defended_pawn_is_lost(self):
        # Nxd5 exd5: knight for pawn. The finding names the knight, the loss is 3 - 1 = 2.
        fen = "4k3/8/4p3/3p4/8/2N5/8/4K3 w - - 0 1"
        finding = run(fen, "c3d5", MISTAKE, "e6d5 e1e2 e8e7 e2e3", best="e1e2 e8e7 e2e3 e7d6")
        assert finding is not None
        assert (finding.piece, finding.square, finding.captured_by) == ("knight", "d5", "pawn")
        assert finding.material_lost == 2
        assert (finding.moved_piece, finding.moved_from, finding.moved_to) == ("knight", "c3", "d5")
        # The evidence is the played window: the recapture and the quiet moves after it.
        assert finding.evidence_line_uci == "e6d5 e1e2 e8e7"

    def test_an_even_trade_on_the_square_does_not_fire_even_if_material_is_lost_later(self):
        # Demo game 00hjmrjd, ply 20, Black: Nxd5 exd5 trades knight for bishop (0). The material Black
        # loses later (Nxc5 takes the c5 bishop) is not attributed: a known miss, not this finding.
        # hanging-0.2 reported "knight lost on d5", which named the wrong piece.
        fen = "r1bqkr2/2pp1pp1/p1n2n1p/2bBp3/Np2P3/3P1N2/PPP2PPP/R1BQ1RK1 b q - 1 10"
        played = "e4d5 c6e7 a4c5 d7d6 c5b3 c8g4 f1e1 f7f5"
        best = "d8e7 d5c6 d7c6 a4c5 e7c5 a2a3 c8g4 c1e3"
        assert run(fen, "f6d5", MISTAKE, played, best=best) is None

    def test_a_retake_on_the_square_counts(self):
        # Nxd5 exd5 Qxd5: the knight bought two pawns. Net 1 from White's side: no finding. (hanging-0.3
        # stopped at the recapture and reported 2.)
        fen = "4k3/8/4p3/3p4/8/2N5/8/3QK3 w - - 0 1"
        assert run(fen, "c3d5", MISTAKE, "e6d5 d1d5 e8e7 e1e2", best="e1e2 e8e7 e2e3 e7d6") is None

    def test_a_longer_exchange_on_one_square_is_summed(self):
        # Nxd5 exd5 Rxd5 Rxd5: White gives knight and rook for two pawns. +1 -3 +1 -5: net loss 6.
        fen = "3rk3/8/4p3/3p4/8/2N5/8/3RK3 w - - 0 1"
        finding = run(fen, "c3d5", MISTAKE, "e6d5 d1d5 d8d5 e1e2", best="e1e2 e8e7 e2e3 e7d6")
        assert finding is not None
        assert (finding.piece, finding.square, finding.captured_by) == ("knight", "d5", "pawn")
        assert finding.material_lost == 6
        assert finding.evidence_line_uci == "e6d5 d1d5 d8d5 e1e2"

    def test_an_exchange_still_going_at_the_end_of_the_line_is_not_judged(self):
        # The line stops right after Rxd5: whether Black can retake again is unknown. No finding.
        fen = "3rk3/8/4p3/3p4/8/2N5/8/3RK3 w - - 0 1"
        assert run(fen, "c3d5", MISTAKE, "e6d5 d1d5", best="e1e2 e8e7 e2e3 e7d6") is None

    def test_losing_the_exchange_on_the_square_fires(self):
        # Rxe5 dxe5: rook for knight, 5 - 3 = 2.
        fen = "4k3/8/3p4/4n3/8/8/8/4RK2 w - - 0 1"
        finding = run(fen, "e1e5", MISTAKE, "d6e5 f1e2 e8e7 e2e3", best="f1e2 e8e7 e2e3 e7e6")
        assert (finding.piece, finding.captured_by, finding.material_lost) == ("rook", "pawn", 2)

    def test_the_rest_of_the_line_does_not_count(self):
        # Nxd5 exd5 and then White also loses the loose g1 rook: only the exchange on d5 is judged (2).
        fen = "4k3/8/4p3/3p4/8/2N4K/8/r5R1 w - - 0 1"
        finding = run(fen, "c3d5", MISTAKE, "e6d5 h3h4 a1g1 h4h5", best="h3h4 e8e7 g1g7 e7d6")
        assert finding.material_lost == 2


class TestMateScore:
    @pytest.mark.parametrize(
        "score, user, expected",
        [
            (Score(mate=-3), chess.WHITE, True),
            (Score(mate=3), chess.WHITE, False),
            (Score(mate=3), chess.BLACK, True),
            (Score(mate=-3), chess.BLACK, False),
            (Score(mate=0), chess.WHITE, False),
            (Score(cp=-900), chess.WHITE, False),
            (None, chess.WHITE, False),
        ],
    )
    def test_is_mate_against(self, score, user, expected):
        assert is_mate_against(score, user) is expected


class TestInputs:
    def test_illegal_move_raises(self):
        with pytest.raises(ValueError):
            run(*KNIGHT_TO_PAWN[:1], "f3f5", BLUNDER, KNIGHT_TO_PAWN_PV, best=KNIGHT_TO_PAWN_BEST)

    def test_illegal_line_raises(self):
        with pytest.raises(ValueError):
            run(*KNIGHT_TO_PAWN, BLUNDER, "e5d4 d2d3 d4d3 c1g5", best=KNIGHT_TO_PAWN_BEST)

    def test_illegal_best_line_raises(self):
        with pytest.raises(ValueError):
            run(*KNIGHT_TO_PAWN, BLUNDER, KNIGHT_TO_PAWN_PV, best="b1c3 b8c6 c3c5")

    def test_material_balance(self):
        board = chess.Board()
        assert material_balance(board, chess.WHITE) == 0
        board.remove_piece_at(chess.D8)
        assert material_balance(board, chess.WHITE) == 9
        assert material_balance(board, chess.BLACK) == -9
