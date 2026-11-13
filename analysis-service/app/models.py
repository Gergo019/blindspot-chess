"""Request and response models for POST /analyze.

They mirror the backend's move_evaluations table (V1 migration): scores are
from White's point of view and exactly one of cp / mate is set per score.
"""

from enum import Enum

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
    name: str
    version: str
    depth: int


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


class AnalyzeResponse(_CamelModel):
    engine: EngineInfo
    moves: list[MoveEvaluation]
