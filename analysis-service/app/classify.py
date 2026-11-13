"""Move classification by the drop in winning chances. Pure functions, no engine.

Classifying by winning chances instead of raw centipawns means a move that
loses 300 centipawns in a position that stays completely won (or completely
lost) is not flagged as a mistake.

Formula and thresholds follow Lichess:

* Winning chances: ``2 / (1 + exp(-0.00368208 * cp)) - 1``, in [-1, +1].
  https://github.com/lichess-org/scalachess/blob/fbb8f2c75d25b64cb4c9995d867e46a877e1f418/core/src/main/scala/eval.scala#L106-L110
* Judgements: a drop of at least 0.3 is a blunder, 0.2 a mistake, 0.1 an
  inaccuracy, measured from the side that moved.
  https://github.com/lichess-org/lila/blob/3e79a4579cb02b83d6fc7fe2e855cf8ff5949e20/modules/tree/src/main/Advice.scala#L45-L58

Deliberate differences from Lichess:

* Mate scores map straight to winning chances of +1 / -1. Lichess judges
  moves that create or lose a forced mate with separate rules (MateAdvice in
  the same Advice.scala file).
* BEST (played the engine's best move) and GOOD (below the inaccuracy
  threshold) are BlindSpot's own labels; Lichess only labels the three
  judgements.
"""

import math
from enum import Enum

MULTIPLIER = -0.00368208
INACCURACY_DROP = 0.1
MISTAKE_DROP = 0.2
BLUNDER_DROP = 0.3


class Classification(str, Enum):
    BEST = "BEST"
    GOOD = "GOOD"
    INACCURACY = "INACCURACY"
    MISTAKE = "MISTAKE"
    BLUNDER = "BLUNDER"


def cp_winning_chances(cp: int) -> float:
    """Winning chances in [-1, +1] for a centipawn score (Lichess formula)."""
    chances = 2 / (1 + math.exp(MULTIPLIER * cp)) - 1
    return max(-1.0, min(1.0, chances))


def winning_chances(cp: int | None, mate: int | None, white_to_move: bool) -> float:
    """Winning chances from White's point of view for one score.

    Exactly one of ``cp`` / ``mate`` is set; both are from White's point of
    view. ``mate == 0`` means the side to move is checkmated (the Lichess
    convention), which is why the side to move is needed.
    """
    if (cp is None) == (mate is None):
        raise ValueError("exactly one of cp / mate must be set")
    if mate is None:
        return cp_winning_chances(cp)
    if mate == 0:
        return -1.0 if white_to_move else 1.0
    return 1.0 if mate > 0 else -1.0


def judge_drop(drop: float) -> Classification:
    """Map a drop in the mover's winning chances to a classification."""
    if drop >= BLUNDER_DROP:
        return Classification.BLUNDER
    if drop >= MISTAKE_DROP:
        return Classification.MISTAKE
    if drop >= INACCURACY_DROP:
        return Classification.INACCURACY
    return Classification.GOOD


def classify(
    *,
    white_moved: bool,
    played_best: bool,
    before: tuple[int | None, int | None],
    after: tuple[int | None, int | None],
) -> Classification:
    """Classify one half-move.

    ``before`` and ``after`` are ``(cp, mate)`` pairs from White's point of
    view, for the position before and after the move.
    """
    if played_best:
        return Classification.BEST
    # Before the move the mover is to move; after it, the opponent is.
    chances_before = winning_chances(*before, white_to_move=white_moved)
    chances_after = winning_chances(*after, white_to_move=not white_moved)
    drop = chances_before - chances_after if white_moved else chances_after - chances_before
    return judge_drop(drop)
