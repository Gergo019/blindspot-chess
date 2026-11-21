"""BlindSpot analysis service.

Owns everything that needs chess knowledge beyond bookkeeping:

* Engine analysis of finished games with a pinned Stockfish (python-chess
  drives the engine over UCI) and classification of each half-move.
* Later phases: motif detection with evidence lines, and explanations of
  mistakes that are verified before the backend may show them.

* Judging every legal move of a drill position (POST /judge), so the backend
  can check answers by lookup (#78, ADR 0008).

The Spring Boot backend is the only caller. POST /analyze is synchronous and
analyses one game per call; POST /judge judges one position per call. The
backend calls both from background jobs.
"""

import logging
import time
from datetime import datetime, timezone
from typing import Annotated

import chess
from fastapi import Depends, FastAPI, HTTPException

from app import judge
from app.config import Settings, get_settings
from app.engine import EngineUnavailableError, analyze_game, open_engine
from app.models import AnalyzeRequest, AnalyzeResponse, Color, EngineInfo, JudgeRequest, JudgeResponse
from app.pgn import InvalidGameError, parse_finished_game

log = logging.getLogger("app.judge")

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
        body.update(
            engineName=info.name,
            engineVersion=info.version,
            engineDepth=info.depth,
            engineThreads=info.threads,
            engineHashMb=info.hash_mb,
        )
    return body


@app.post("/analyze", response_model=AnalyzeResponse, response_model_exclude_none=True)
def analyze(request: AnalyzeRequest, settings: SettingsDep) -> AnalyzeResponse:
    try:
        game = parse_finished_game(request.pgn, settings.max_plies)
    except InvalidGameError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    try:
        with open_engine(settings) as engine:
            result = analyze_game(game, request.user_color == Color.WHITE, engine)
            return AnalyzeResponse(engine=engine.info, moves=result.moves, findings=result.findings)
    except InvalidGameError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    except EngineUnavailableError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc


@app.post("/judge", response_model=JudgeResponse, response_model_exclude_none=True)
def judge_moves(request: JudgeRequest, settings: SettingsDep) -> JudgeResponse:
    """Judges every legal move of a drill position (#78, ADR 0008).

    422: not a FEN, not a legal position, or the game is over. 503: Stockfish cannot start
    (``detail`` is a string), or the time budget was exceeded (``detail.reason`` is
    ``JUDGE_BUDGET_EXCEEDED``; no verdicts are returned).
    """
    try:
        board = judge.parse_position(request.fen)
    except judge.InvalidPositionError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    started = time.monotonic()
    try:
        with open_engine(settings) as engine:

            def search(position: chess.Board, move: chess.Move) -> judge.MoveResult:
                result = engine.search_move(position, move)
                return judge.MoveResult(move=move, score=result.score, pv=result.pv)

            verdicts = judge.judge(board, search, settings.judge_budget_seconds)
            info = engine.info
    except EngineUnavailableError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    except judge.JudgeBudgetExceeded as exc:
        log.warning("POST /judge over budget for %s: %s", board.fen(), exc)
        raise HTTPException(
            status_code=503,
            detail={
                "reason": "JUDGE_BUDGET_EXCEEDED",
                "message": str(exc),
                "budgetSeconds": exc.budget_seconds,
                "elapsedSeconds": round(exc.elapsed_seconds, 3),
                "movesJudged": exc.moves_judged,
                "legalMoves": exc.legal_moves,
            },
        ) from exc
    log.info("POST /judge: %d moves in %.2f s for %s", len(verdicts), time.monotonic() - started, board.fen())
    return JudgeResponse(
        engine=info,
        fen=board.fen(),
        side_to_move=Color.WHITE if board.turn == chess.WHITE else Color.BLACK,
        accept_below=judge.ACCEPT_BELOW,
        verdicts=verdicts,
    )
