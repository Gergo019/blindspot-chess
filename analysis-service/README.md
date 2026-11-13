# analysis-service

Python (FastAPI) service for everything that needs chess knowledge: engine analysis with a pinned [Stockfish](https://stockfishchess.org/) through [python-chess](https://python-chess.readthedocs.io/), motif detection with evidence lines, and later verified explanations. The Spring Boot backend is its only caller.

| Phase | Endpoint | What it does |
|---|---|---|
| 1 | `GET /health` | Service status, plus the engine name, version and depth (`"engine": "MISSING"` if Stockfish cannot start) |
| 1 | `POST /analyze` | Takes one finished game as PGN, returns an evaluation and classification for every half-move, and findings (motifs) for the user's moves |
| 4 | `POST /explain` | Explains a mistake from validated claims ([ADR 0003](../docs/decisions/0003-facts-first-explanations.md)) |

Why Python here and not Java: python-chess is the most complete chess library for move generation, PGN parsing and UCI engine control. See [ADR 0002](../docs/decisions/0002-python-analysis-service.md).

## POST /analyze

Synchronous: one game per call. The backend calls it from a background job.

Request:

```json
{ "pgn": "1. f3 e5 2. g4 Qh4# 0-1", "userColor": "WHITE" }
```

Response (shortened):

```json
{
  "engine": { "name": "Stockfish", "version": "19", "depth": 16 },
  "moves": [
    { "ply": 3, "byUser": true,
      "fenBefore": "rnbqkbnr/pppp1ppp/8/4p3/8/5P2/PPPPP1PP/RNBQKBNR w KQkq - 0 2",
      "moveUci": "g2g4", "moveSan": "g4", "bestMoveUci": "e2e3",
      "scoreBefore": { "cp": -67 }, "scoreAfter": { "mate": -1 },
      "classification": "BLUNDER" }
  ],
  "findings": [
    { "ply": 5, "motif": "HANGING_PIECE",
      "evidenceLineUci": "e5d4 c2c3 d6d5 c3d4 d5e4 d1b3",
      "detectorVersion": "hanging-0.2",
      "facts": { "piece": "knight", "square": "d4", "capturedBy": "pawn", "materialLost": 3,
                 "movedPiece": "knight", "movedFrom": "f3", "movedTo": "d4" } }
  ]
}
```

(The finding is from a different game, `1. e4 e5 2. Nf3 d6 3. Nd4 exd4 0-1`, analysed for White.)

- **Every half-move of both sides** is analysed; `byUser` marks the user's moves. Detectors need the opponent's replies as evidence.
- **Scores are from White's point of view**, with exactly one of `cp` / `mate`. `mate` > 0 means White mates in that many moves, < 0 Black does.
- **The final move:** after checkmate, `scoreAfter` is `{ "mate": 0 }`, meaning the side to move is mated (the Lichess convention). After stalemate or another draw by the rules (insufficient material, 75-move rule, fivefold repetition) it is `{ "cp": 0 }`. No search runs on a finished position.
- **Classification** is by the drop in the mover's winning chances, not raw centipawns, so moves in already-won or already-lost positions are not flagged. Formula and thresholds follow Lichess (links in [`app/classify.py`](app/classify.py)): a drop of at least 0.1 is an `INACCURACY`, 0.2 a `MISTAKE`, 0.3 a `BLUNDER`. `BEST` means the engine's best move was played; `GOOD` means a drop below 0.1. Mate scores count as winning chances of +1 / -1.
- **Findings** are motifs behind the user's own moves, each with an evidence line the player can replay: the engine's follow-up after the move, in UCI. `facts` holds the checked facts the finding rests on (the start of the typed claims in [ADR 0003](../docs/decisions/0003-facts-first-explanations.md)); `detectorVersion` says which rules produced it. `moves` is unchanged by detection.
- **`HANGING_PIECE`** ([ADR 0004](../docs/decisions/0004-hanging-piece-detector.md)): the user's move is a `MISTAKE` or `BLUNDER`, the engine's reply captures one of the user's knights, bishops, rooks or queens, the user is at least 2 points down (P=1, N=B=3, R=5, Q=9) at the end of the window (4 plies of the engine's line, extended while captures continue, up to 8), and there is no forced mate against the user (no mate in the window and no mate score after the move). `piece` is the captured piece, `square` where it is captured, `capturedBy` the capturing piece, `materialLost` the net loss in points; `movedPiece`, `movedFrom` and `movedTo` describe the user's move, so in a discovered loss `movedPiece` differs from `piece`.
- **Rejected with 422** (completed games only): a result of `*` or no result, illegal moves, no moves, non-standard variants, a result that contradicts a checkmate on the board, and games longer than 300 half-moves.
- **503** if Stockfish cannot be started.

## Reproducibility

Every evaluation must be reproducible (product rule 4), so:

- The Docker image contains one official Stockfish release binary, pinned by version and verified by SHA-256 (see the `Dockerfile`).
- Stockfish runs with `Threads=1` (multi-threaded search is not deterministic), a fixed hash size and a fixed depth.
- Each request starts its own engine process, and every position is searched from a cleared state (`ucinewgame` before each search), so an evaluation does not depend on what was searched before.
- Every response reports the engine name, version and depth.

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `ENGINE_DEPTH` | `16` | Fixed search depth per position |
| `ENGINE_THREADS` | `1` | Search threads; keep 1 for reproducible results |
| `ENGINE_HASH_MB` | `64` | Hash table size in MB |
| `STOCKFISH_PATH` | `/usr/local/bin/stockfish` in the image, otherwise `stockfish` on the `PATH` | Engine binary |

## Run and test with Docker (recommended)

Tests run inside the image, so they use the same pinned engine as production and CI:

```bash
cd analysis-service
docker build --target test -t analysis-test . && docker run --rm analysis-test
docker build -t analysis-service . && docker run --rm -p 8000:8000 analysis-service
```

## Run locally without Docker

```bash
cd analysis-service
python -m venv .venv && source .venv/bin/activate
pip install -r requirements-dev.txt
pytest -m "not engine"            # tests that need no engine
uvicorn app.main:app --reload
```

The engine tests and `POST /analyze` need a Stockfish binary: download the Stockfish 19 build for your platform from the [release page](https://github.com/official-stockfish/Stockfish/releases/tag/sf_19) and set `STOCKFISH_PATH`. A different Stockfish version gives different evaluations; the response always says which one was used.
