# analysis-service

Python (FastAPI) service for everything that needs chess knowledge: engine analysis with [Stockfish](https://stockfishchess.org/) through [python-chess](https://python-chess.readthedocs.io/), and later the AI explanations of mistakes. The Spring Boot backend is its only caller.

| Phase | Endpoint | What it does |
|---|---|---|
| now | `GET /health` | Service status, plus whether the Stockfish binary is available |
| 2 | `POST /analyze` | Takes a PGN, returns per-move evaluations and classifications |
| 4 | `POST /explain` | Explains a mistake in plain language; every claim is checked against the engine before it is returned |

Why Python here and not Java: python-chess is the most complete chess library for move generation, PGN parsing and UCI engine control. See [ADR 0002](../docs/decisions/0002-python-analysis-service.md).

## Run locally

```bash
cd analysis-service
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --reload
```

Stockfish must be on your `PATH` for `"engine": "UP"` (macOS: `brew install stockfish`, Debian/Ubuntu: `apt install stockfish`). The Docker image installs it for you.
