import math

import pytest

from app.classify import (
    BLUNDER_DROP,
    INACCURACY_DROP,
    MISTAKE_DROP,
    Classification,
    classify,
    cp_winning_chances,
    judge_drop,
    winning_chances,
)


class TestWinningChances:
    def test_equal_position_is_zero(self):
        assert cp_winning_chances(0) == 0

    def test_matches_the_lichess_formula(self):
        # 2 / (1 + exp(-0.00368208 * 100)) - 1
        assert cp_winning_chances(100) == pytest.approx(0.18206, abs=1e-5)
        assert cp_winning_chances(300) == pytest.approx(2 / (1 + math.exp(-0.00368208 * 300)) - 1)

    def test_is_symmetric(self):
        for cp in (35, 120, 480, 2500):
            assert cp_winning_chances(-cp) == pytest.approx(-cp_winning_chances(cp))

    def test_increases_with_the_score_and_stays_within_bounds(self):
        values = [cp_winning_chances(cp) for cp in range(-5000, 5001, 250)]
        assert values == sorted(values)
        assert all(-1 <= v <= 1 for v in values)

    @pytest.mark.parametrize("mate, expected", [(1, 1.0), (12, 1.0), (-1, -1.0), (-7, -1.0)])
    def test_mate_scores_map_to_plus_or_minus_one(self, mate, expected):
        assert winning_chances(None, mate, white_to_move=True) == expected
        assert winning_chances(None, mate, white_to_move=False) == expected

    def test_mate_zero_means_the_side_to_move_is_mated(self):
        assert winning_chances(None, 0, white_to_move=True) == -1.0
        assert winning_chances(None, 0, white_to_move=False) == 1.0

    @pytest.mark.parametrize("cp, mate", [(None, None), (10, 2)])
    def test_exactly_one_of_cp_and_mate(self, cp, mate):
        with pytest.raises(ValueError):
            winning_chances(cp, mate, white_to_move=True)


class TestThresholds:
    def test_threshold_values_are_the_lichess_ones(self):
        assert (INACCURACY_DROP, MISTAKE_DROP, BLUNDER_DROP) == (0.1, 0.2, 0.3)

    @pytest.mark.parametrize(
        "drop, expected",
        [
            (-0.5, Classification.GOOD),
            (0.0, Classification.GOOD),
            (0.0999, Classification.GOOD),
            (0.1, Classification.INACCURACY),
            (0.1999, Classification.INACCURACY),
            (0.2, Classification.MISTAKE),
            (0.2999, Classification.MISTAKE),
            (0.3, Classification.BLUNDER),
            (2.0, Classification.BLUNDER),
        ],
    )
    def test_thresholds_are_inclusive(self, drop, expected):
        assert judge_drop(drop) == expected


def cp(value):
    return (value, None)


def mate(value):
    return (None, value)


class TestClassify:
    def test_best_move_is_best_whatever_the_scores(self):
        result = classify(white_moved=True, played_best=True, before=cp(50), after=cp(-400))
        assert result == Classification.BEST

    def test_small_loss_is_good(self):
        assert classify(white_moved=True, played_best=False, before=cp(30), after=cp(10)) == Classification.GOOD

    def test_white_losing_a_piece_in_a_level_position_is_a_blunder(self):
        assert classify(white_moved=True, played_best=False, before=cp(20), after=cp(-300)) == Classification.BLUNDER

    def test_black_is_judged_from_its_own_side(self):
        # Scores are from White's point of view: +400 after Black's move is bad for Black.
        assert classify(white_moved=False, played_best=False, before=cp(0), after=cp(400)) == Classification.BLUNDER
        # The same numbers after a White move are an improvement for White.
        assert classify(white_moved=True, played_best=False, before=cp(0), after=cp(400)) == Classification.GOOD

    def test_threshold_band_from_scores(self):
        # 0 -> -120 cp for White: drop of about 0.22, a mistake.
        assert classify(white_moved=True, played_best=False, before=cp(0), after=cp(-120)) == Classification.MISTAKE
        # 0 -> -60 cp: drop of about 0.11, an inaccuracy.
        assert classify(white_moved=True, played_best=False, before=cp(0), after=cp(-60)) == Classification.INACCURACY

    def test_large_loss_in_an_already_won_position_is_not_flagged(self):
        # 600 centipawns lost, but White stays completely winning.
        assert classify(white_moved=True, played_best=False, before=cp(1500), after=cp(900)) == Classification.GOOD

    def test_further_loss_in_an_already_lost_position_is_not_flagged(self):
        assert classify(white_moved=True, played_best=False, before=cp(-1500), after=cp(-2100)) == Classification.GOOD

    def test_allowing_a_forced_mate_is_a_blunder(self):
        assert classify(white_moved=True, played_best=False, before=cp(50), after=mate(-1)) == Classification.BLUNDER

    def test_losing_a_forced_mate_into_a_small_edge_is_a_blunder(self):
        assert classify(white_moved=True, played_best=False, before=mate(3), after=cp(150)) == Classification.BLUNDER

    def test_slower_mate_is_not_flagged(self):
        assert classify(white_moved=True, played_best=False, before=mate(2), after=mate(5)) == Classification.GOOD

    def test_delivering_checkmate_scores_as_won(self):
        # After White mates, Black is to move and mated: mate 0.
        assert classify(white_moved=True, played_best=False, before=mate(1), after=mate(0)) == Classification.GOOD
        assert classify(white_moved=False, played_best=False, before=mate(-1), after=mate(0)) == Classification.GOOD
