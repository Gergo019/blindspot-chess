# Engineering log

A chronological record of what was done, why, and what to change if something breaks. Newest entry at the top. Every pull request adds or updates an entry.

Entry format: Goal, Changes, Decisions (with the reason and the alternative rejected), Verification, Open points / risks, Links.

## 2026-11-13: POST /analyze with pinned Stockfish (#2 / PR #16)

**Goal:** The analysis service evaluates one finished game with a pinned, reproducible Stockfish and classifies every half-move.

**Changes:**
- `analysis-service/app/config.py`: settings from `ENGINE_DEPTH` (16), `ENGINE_THREADS` (1), `ENGINE_HASH_MB` (64), `STOCKFISH_PATH`; 300-ply limit.
- `analysis-service/app/classify.py`: winning chances and classification, pure functions.
- `analysis-service/app/pgn.py`: PGN parsing and the completed-games-only validation (added beside the planned modules so it can be tested without an engine).
- `analysis-service/app/engine.py`: python-chess UCI wrapper, one engine per request, and the per-ply analysis loop.
- `analysis-service/app/models.py`: Pydantic request and response models (camelCase JSON), mirroring the `move_evaluations` table.
- `analysis-service/app/main.py`: `POST /analyze`; `GET /health` now reports engine name, version and depth.
- `analysis-service/Dockerfile`: pinned Stockfish 19 release binary with SHA-256 check (stage `stockfish`), `test` stage with pytest, `runtime` as the default target; `.dockerignore`.
- `analysis-service/tests/`: 55 tests; `requirements-dev.txt`, `pytest.ini`.
- `.github/workflows/ci.yml`: the analysis-service job builds the `test` image and runs pytest in it; the apt Stockfish on the runner is gone. The job name is unchanged because it is a required check on `main`.
- `docker-compose.yml`, `.env.example`: engine settings.
- Docs: `analysis-service/README.md`, `README.md`, `CHANGELOG.md`, [ADR 0002](decisions/0002-python-analysis-service.md).

**Decisions:**
- **Pinned binary:** Stockfish 19 (latest stable, released 2026-09-05), asset `stockfish-linux-x86-64-universal.tar.gz`, SHA-256 `9defc0d4e55d49c65a6d042f3e571a39fcea499ade6dbe741b53b8c65e03611f`, verified in the Dockerfile. Since Stockfish 19 the official Linux binaries are "universal" (CPU features detected at runtime); no plain non-AVX x86-64 asset exists any more, and the universal one meets the goal of running on any runner. Rejected: Stockfish 18's separate x86-64 build (an older engine) and the Debian package (version tied to the base image). The arm64 universal binary (SHA-256 `fe26cfd1d9db4c8af3d21e24d9ff34cacb31c1f940085a7583da11796f2bac01`) is pinned as well, only so the image builds natively on Apple Silicon; CI uses x86-64.
- **Reproducible search:** `Threads=1`, `Hash=64` MB, fixed depth 16. Reason: multi-threaded search is not deterministic. Rejected: time-based limits (depend on machine speed).
- **One engine process per request, `ucinewgame` before every position** (clears hash and history). Reason: a position's evaluation must not depend on what was searched before, in this request or an earlier one. Rejected: a pooled engine (would carry its hash between requests). Cost: one engine start per request.
- **Classification by drop in winning chances** with the Lichess formula `2 / (1 + exp(-0.00368208 * cp)) - 1` ([scalachess eval.scala L106-110](https://github.com/lichess-org/scalachess/blob/fbb8f2c75d25b64cb4c9995d867e46a877e1f418/core/src/main/scala/eval.scala#L106-L110)) and thresholds 0.1 / 0.2 / 0.3, inclusive, from the mover's side ([lila Advice.scala L45-58](https://github.com/lichess-org/lila/blob/3e79a4579cb02b83d6fc7fe2e855cf8ff5949e20/modules/tree/src/main/Advice.scala#L45-L58)). Reason: moves in already-won or already-lost positions are not flagged. Rejected: raw centipawn thresholds. Like Lichess's move judgements, centipawns are not capped at ±1000 before the formula.
- **Mate scores count as winning chances ±1.** Lichess handles moves that create or lose a forced mate with separate rules (`MateAdvice`, same file). Rejected for now to keep one rule; differences are small (for example, Lichess calls losing a mate while still +10 an inaccuracy; here it is GOOD).
- **BEST** = the engine's best move was played (takes precedence); **GOOD** = drop below 0.1.
- **Final move:** after checkmate, `scoreAfter` is `{"mate": 0}` = the side to move is mated (the Lichess convention, `Score.mated` in scalachess); after stalemate or any other draw by the rules, `{"cp": 0}`. No search runs on a finished position. Reason: the schema requires a score after every move.
- **Completed games only:** 422 for result `*` or missing, illegal moves, no moves, non-standard variants (Chess960), a result contradicting a checkmate on the board, more than 300 plies, and request bodies over 100,000 characters (Pydantic).
- **Analyse every half-move of both sides.** Reason: detectors need the opponent's reply as evidence.
- **Synchronous, one game per call.** The backend will call it from a background job.
- **Test client:** `httpx2`, which current Starlette requires for `TestClient` (`httpx` is deprecated there).

**Verification:**
- Locally in the `test` image: 55 passed on arm64 and 55 passed on amd64 (emulated). Classification unit tests (formula, inclusive thresholds, mate handling, BEST vs GOOD, won and lost positions); PGN tests; engine tests (hung queen is BLUNDER, Fool's mate ends with mate 0 and `g4` is BLUNDER, Loyd's stalemate ends with cp 0, determinism: same game twice gives identical output); API tests (422 for `*`, an illegal PGN, a 301-ply game and an unknown colour; 503 without engine; response contract; health).
- UCI traffic logged in the image: `setoption name Hash value 64`, then `ucinewgame`, `isready`, `position`, `go depth 16` for every position. `Threads` is not sent because python-chess skips options equal to the engine default (1).
- The Blackburne Shilling game analysed with the x86-64 and the arm64 binary gave byte-identical JSON.
- Runtime image smoke test: `/health` reports Stockfish 19, depth 16; `POST /analyze` returns the contract; an unfinished game returns 422.
- CI on PR #16, first run green: the runner's image build verified the x86-64 checksum (`/tmp/stockfish.tar.gz: OK`); `Analysis service (install + smoke test)` ran 55 passed in the image; `Backend (build + tests)` passed; `Docker Compose stack` started and `/health` reported Stockfish 19, depth 16.

**Open points / risks:**
- Not yet measured: time per position at depth 16, single-threaded. The whole test suite, including nine engine analyses of short games, runs in about 12 s in the image; a long game will take much longer. Fine for a background job; the backend client needs a generous timeout.
- Concurrent requests each start an engine process; there is no limit on concurrency yet. Worth a cap when the backend job runs in parallel.
- The mate rule differs slightly from Lichess's `MateAdvice` (see Decisions).
- The first move from the starting position can already be a MISTAKE (1. f3 drops about 0.2 winning chances at depth 16). That is the formula working as designed, but worth reviewing with real games.
- Upgrading Stockfish changes evaluations; stored rows keep their engine version.

**Links:** [#2](https://github.com/Gergo019/blindspot-chess/issues/2), [PR #16](https://github.com/Gergo019/blindspot-chess/pull/16), [ADR 0002](decisions/0002-python-analysis-service.md)

## 2026-11-12: Project foundation (PR #1)

**Goal:** Lay the foundations for a chess coach that finds one recurring mistake in a player's own games, drills it, and tests whether the lesson transfers.

**Changes:**
- Pivot from the earlier idea, an achievement and trophy tracker, to a chess coach. The project was renamed from OneMore% to BlindSpot; the GitHub repository `onemore-percent` was renamed to `blindspot-chess`.
- Monorepo: `backend/` (Java 21, Spring Boot 4.1), `analysis-service/` (Python, FastAPI, python-chess, Stockfish), `frontend/` (placeholder for Phase 3), `docker-compose.yml` with PostgreSQL 17.
- Backend skeleton with `GET /health` reporting database status.
- Flyway migration `backend/src/main/resources/db/migration/V1__initial_schema.sql`: users, chess accounts, games, move evaluations, mistake findings, explanations, drills, drill attempts.
- Domain entities with their rules (analysis lifecycle, evidence-backed findings, explanation verification, own-game and transfer drills with SM-2 scheduling) and repositories.
- Analysis service stub with `GET /health`.
- CI (`.github/workflows/ci.yml`): backend build and tests, analysis-service smoke test, full Docker Compose stack; Dependabot; PR and issue templates; `CONTRIBUTING.md`; `CHANGELOG.md`.
- Decision records `docs/decisions/0001` to `0003`.

**Decisions:**
- **The coaching loop:** import completed games → engine analysis → detect motifs → pattern report → drills on the player's own positions → transfer test on unseen puzzles with the same motif. Reason: replaying your own mistakes already exists elsewhere; the transfer test checks whether the pattern was learned, not whether one move was memorised. Rejected: a generic puzzle trainer.
- **v1 scope:** Lichess rapid games only, two motifs that code detects reliably (allowing a knight fork, leaving a piece hanging), no opening analysis. Reason: a narrow loop that works end to end beats broad, shallow coverage. Rejected: multiple platforms and time controls from the start.
- **Product rules** (in `CONTRIBUTING.md`): completed games only; every coaching claim has board evidence the player can play through; only verified explanations are shown; analysis is reproducible (engine version and depth are recorded).
- **Explanations from validated typed claims** ([ADR 0003](decisions/0003-facts-first-explanations.md)): code produces typed claims, validates them by replaying the evidence line, and templates render them. Free-form AI text is only an experiment (`LLM_FREE`, `LLM_FACTS`) measured on held-out positions. Reason: a checker that only matches moves, squares and motifs misses sentences that reverse who threatens whom. Rejected: showing LLM text with a keyword check.
- **Service split** ([ADR 0001](decisions/0001-monorepo-and-service-split.md)) and **chess computation in Python** ([ADR 0002](decisions/0002-python-analysis-service.md)): python-chess is the most complete chess library. Rejected: move generation in Java.
- **Schema rules in the database:** check constraints (one score per position, valid classifications), unique keys (one evaluation per game and ply), cascades; Hibernate in `validate` mode only.

**Verification:**
- CI green on the first run of PR #1: backend `mvn -B verify` with 15 tests, 0 failures, 0 skipped, including Testcontainers on PostgreSQL 17 (Flyway applied V1); analysis-service install and health check; Docker Compose stack started and both health endpoints answered.
- A follow-up commit removed leftovers from the earlier idea (issue template wording, a test username); CI green again with the same 15 tests.

**Open points / risks:**
- The analysis service was a stub with Stockfish from Debian's package repository, so the engine version depended on the base image. Addressed in #2.
- Branch protection on `main`: PR required, the three CI checks required, force pushes and deletion blocked, squash merge only, head branches deleted automatically. Admins can still bypass; no approvals required (solo project).

**Links:** [PR #1](https://github.com/Gergo019/blindspot-chess/pull/1), [ADR 0001](decisions/0001-monorepo-and-service-split.md), [ADR 0002](decisions/0002-python-analysis-service.md), [ADR 0003](decisions/0003-facts-first-explanations.md)
