"""Request and response models for POST /analyze.

They mirror the backend's move_evaluations table (V1 migration): scores are
from White's point of view and exactly one of cp / mate is set per score.
"""

from enum import Enum
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator
from pydantic.alias_generators import to_camel

from app.classify import Classification

# Generous upper bound for one PGN (a 300-ply game with headers and clock
# comments is well under this); stops oversized bodies before parsing.
MAX_PGN_CHARS = 100_000


class _CamelModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


class Color(str, Enum):
    WHITE = "WHITE"
    BLACK = "BLACK"


class AnalyzeRequest(_CamelModel):
    pgn: str = Field(min_length=1, max_length=MAX_PGN_CHARS)
    user_color: Color


class Score(_CamelModel):
    """An engine score from White's point of view.

    ``mate`` > 0: White mates in that many moves; < 0: Black does.
    ``mate`` == 0: the side to move is checkmated (only after the final,
    mating move of a game).
    """

    cp: int | None = None
    mate: int | None = None

    @model_validator(mode="after")
    def _exactly_one(self) -> "Score":
        if (self.cp is None) == (self.mate is None):
            raise ValueError("exactly one of cp / mate must be set")
        return self


class EngineInfo(_CamelModel):
    """The engine and the settings that decide its output; all of them are needed to reproduce it (ADR 0002)."""

    name: str
    version: str
    depth: int
    threads: int
    hash_mb: int


class MoveEvaluation(_CamelModel):
    ply: int = Field(ge=1)
    by_user: bool
    fen_before: str
    move_uci: str
    move_san: str
    best_move_uci: str
    score_before: Score
    score_after: Score
    classification: Classification


class FindingFacts(_CamelModel):
    """Checked facts behind a finding: the start of the typed claims (ADR 0003)."""

    piece: str  # the user's piece that is won, e.g. "knight"
    square: str  # where it is captured, e.g. "d4"
    captured_by: str  # the opponent's capturing piece, e.g. "pawn"
    material_lost: int = Field(ge=1)
    moved_piece: str  # the piece the user moved; differs from piece in a discovered loss
    moved_from: str
    moved_to: str


class Finding(_CamelModel):
    """A detected motif behind one of the user's moves, with board evidence."""

    ply: int = Field(ge=1)  # the user's move the finding is about
    motif: Literal["HANGING_PIECE"]
    evidence_line_uci: str  # the follow-up after that move, space-separated UCI
    detector_version: str
    facts: FindingFacts


class AnalyzeResponse(_CamelModel):
    engine: EngineInfo
    moves: list[MoveEvaluation]
    findings: list[Finding]


# Longest FEN accepted by POST /judge; the backend stores FENs in VARCHAR(100).
MAX_FEN_CHARS = 100


class JudgeRequest(_CamelModel):
    """A drill position (#78). The side to move is the side whose moves are judged."""

    fen: str = Field(min_length=1, max_length=MAX_FEN_CHARS)


class Verdict(_CamelModel):
    """The engine's judgement of one legal move, in the shape the backend stores (#36).

    ``score`` is the position's value after the move, from White's point of view. ``drop`` is
    the loss in winning chances against the best judged move, from the mover's side, on the
    classification's scale (rounded to 4 decimals, so 0 for the best move); ``accepted`` is
    ``drop < 0.1``. ``refutation_uci`` is the engine's line after the move (space-separated UCI,
    empty when the move ends the game), the board evidence for a wrong answer.
    """

    uci: str
    san: str
    score: Score
    drop: float = Field(ge=0)
    accepted: bool
    refutation_uci: str


class JudgeResponse(_CamelModel):
    engine: EngineInfo
    fen: str  # the position as parsed, normalised by python-chess
    side_to_move: Color
    accept_below: float  # the drop threshold: accepted means drop < accept_below
    verdicts: list[Verdict]  # one per legal move: best first (lowest drop), ties by UCI
