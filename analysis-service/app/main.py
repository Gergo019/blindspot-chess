"""BlindSpot analysis service.

Owns everything that needs chess knowledge beyond bookkeeping:

* Phase 2: engine analysis of games with Stockfish (python-chess drives the
  engine over UCI) and classification of each move.
* Phase 4: AI explanations of mistakes, verified against the engine before the
  backend may show them.

The Spring Boot backend is the only caller. For now the service exposes a
health check that also reports whether the Stockfish binary is available.
"""

import shutil
from datetime import datetime, timezone

import chess
from fastapi import FastAPI

app = FastAPI(title="BlindSpot analysis service", version="0.1.0")

STOCKFISH_PATH = shutil.which("stockfish") or "/usr/games/stockfish"


@app.get("/health")
def health() -> dict[str, str]:
    engine = "UP" if shutil.which(STOCKFISH_PATH) else "MISSING"
    return {
        "status": "UP",
        "engine": engine,
        "pythonChess": chess.__version__,
        "timestamp": datetime.now(timezone.utc).isoformat(),
    }
