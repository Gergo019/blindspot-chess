"""BlindSpot analysis service.

Owns everything that needs chess knowledge beyond bookkeeping:

* Engine analysis of finished games with a pinned Stockfish (python-chess
  drives the engine over UCI) and classification of each half-move.
* Later phases: motif detection with evidence lines, and explanations of
  mistakes that are verified before the backend may show them.

The Spring Boot backend is the only caller. POST /analyze is synchronous and
analyses one game per call; the backend calls it from a background job.
"""

from datetime import datetime, timezone
from typing import Annotated

import chess
from fastapi import Depends, FastAPI, HTTPException

from app.config import Settings, get_settings
from app.engine import EngineUnavailableError, analyze_game, open_engine
from app.models import AnalyzeRequest, AnalyzeResponse, Color, EngineInfo
from app.pgn import InvalidGameError, parse_finished_game

app = FastAPI(title="BlindSpot analysis service", version="0.2.0")

SettingsDep = Annotated[Settings, Depends(get_settings)]

# Engine name and version per settings, read once from the engine's UCI id.
# Only successful probes are cached, so a missing binary is retried.
_engine_info_cache: dict[Settings, EngineInfo] = {}


def _engine_info(settings: Settings) -> EngineInfo | None:
    if settings not in _engine_info_cache:
        try:
            with open_engine(settings) as engine:
                _engine_info_cache[settings] = engine.info
        except EngineUnavailableError:
            return None
    return _engine_info_cache[settings]


@app.get("/health")
def health(settings: SettingsDep) -> dict[str, str | int]:
    info = _engine_info(settings)
    body: dict[str, str | int] = {
        "status": "UP",
        "engine": "UP" if info else "MISSING",
        "pythonChess": chess.__version__,
        "timestamp": datetime.now(timezone.utc).isoformat(),
    }
    if info:
        body.update(engineName=info.name, engineVersion=info.version, engineDepth=info.depth)
    return body


@app.post("/analyze", response_model=AnalyzeResponse, response_model_exclude_none=True)
def analyze(request: AnalyzeRequest, settings: SettingsDep) -> AnalyzeResponse:
    try:
        game = parse_finished_game(request.pgn, settings.max_plies)
    except InvalidGameError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    try:
        with open_engine(settings) as engine:
            moves = analyze_game(game, request.user_color == Color.WHITE, engine)
            return AnalyzeResponse(engine=engine.info, moves=moves)
    except EngineUnavailableError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
