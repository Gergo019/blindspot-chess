# Changelog

All notable changes to this project are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- Analysis service: `POST /judge` (#78), a verdict for every legal move of a drill position, for answer checking by lookup (ADR 0008):
  - **Per move:** score, drop in winning chances against the best judged move, accepted (drop < 0.1), and the refutation line as board evidence.
  - **Search:** each move is searched on its own (`searchmoves`) at the pinned depth from a cleared state.
  - **Time budget** (`JUDGE_BUDGET_SECONDS`, default 60 s): searches are never shortened, and a request over budget is a 503 (`JUDGE_BUDGET_EXCEEDED`) with no verdicts. The default comes from measuring the 10 demo drill positions: 1.8 to 11.2 s, median 6.2 s on an Apple M4.
  - **Rejected with 422:** invalid or finished positions.
  - **Tests:** golden files for four positions, fuzzing, and `tests/measure_judge.py`.
- ADR 0008, the learning journey, slice 1 (Hanging Pieces) (#77):
  - the curriculum is defined in code; lesson membership and mastery are stored, per-puzzle progress is derived from drill attempts;
  - lessons fill up from findings, and the map says how many positions are missing;
  - "Find a safe move" comes first, "See the capture" second;
  - answers are checked in the backend against stored verdicts for every legal move;
  - mastery takes 2 check puzzles, both right first try, and a failed check can be retried right away;
  - all lessons are open, with a recommended order (soft locking).

### Changed

- ADR 0007 amended (#77):
  - the "Drills" navigation item becomes "Journey";
  - a `PuzzleBoard` (drag, click-click, a typed move, a promotion choice) joins the read-only `EvidenceBoard`;
  - "no gamification" is spelled out for the map: progress as evidence, no points, streaks, badges or locks.

## [0.2.0] - 2026-11-21

The web app: a read-only findings screen on a read-only API, tested end to end in a real browser, with the product rules written as executable features and the local stack hardened.

### Added

- Product rules as Cucumber features (`backend/src/test/resources/features/`): when a repeated mistake is a pattern, that only verified template explanations are shown, and that mistakes without a detector are not reported. 18 scenarios through the real HTTP API against PostgreSQL, run by Failsafe in the Backend job (Cucumber-JVM 8.0.4; JUnit aligned to 6.1.3)
- Playwright end-to-end tests (#52) against the real stack with the 20 demo games, in a pinned Playwright image: blind-spot card, findings list with URL/reload/Back, deep link, evidence line by chips and keys (checked square by square), best move and move list, narrow screen, theme, error state, and axe in both themes; every scenario fails on a CSP violation or console error. CI job "End-to-end (Playwright)" uploads the report, traces and stack logs on failure
- Findings screen (#24): the blind-spot report (count and sample size always shown; below the threshold it says so), the findings list (newest first, paged, `/findings/:id` deep links), the board before the mistake with your move as an orange arrow and the evidence in blue (squares as rings, arrows with a dark halo), the evidence line as SAN chips derived with chess.js and stepped with ← → Home End, "Show the best move", the facts (every fact type has a renderer; "Material lost" says whether it is measured against the best move or in the exchange on the square, ADR 0004), the explanation only when the API returns one, the game's moves with classifications, and the engine and detector in the footer; loading, empty and error states without server details. Animations follow `prefers-reduced-motion`
- Read-only API (#51): `GET /api/patterns` (blind-spot report, listing only motifs that have a detector: distinct games with each motif among the last 20 analysed rapid games, sample size, threshold of 3), `GET /api/findings` (newest first, paged, filter by motif), `GET /api/findings/{id}` (FEN, played and best move, evidence line, facts, engine, game, and only a verified TEMPLATE explanation), `GET /api/games/{id}/moves`. Response records only, fetch joins (a page costs 3 queries whatever its size), RFC 9457 problem details without internals, GET only, no CORS; OpenAPI at `/api/docs` (springdoc 3.2.0, no UI)
- Web app shell (#50): React 19 + TypeScript (strict) with Vite, ESLint, Prettier and Vitest; header with Findings / Games / Drills (disabled), the board (react-chessboard, starting position) with legend and keyboard hint, an empty side panel, and a dark (default) and light theme remembered per browser. A unit test checks WCAG AA contrast for every theme colour pair; board highlights are rings between dark halo rings because the meaning colours fail as fills (ADR 0007). Served by nginx (non-root, pinned by digest) on port 8081 with `/api` proxied to the backend; a `frontend` service in Docker Compose with a healthcheck; CI job "Frontend (lint + test + build)"; Dependabot for npm
- `THIRD_PARTY_NOTICES.md`: licences of the bundled piece set (Cburnett, BSD), font (Inter, OFL-1.1), libraries and Stockfish

### Changed

- The board is one labelled image for assistive technology and its inside is inert: react-chessboard had made every piece an unnamed, focusable "draggable" button (25 to 32 tab stops on a read-only board), found by axe (#52). The board caption is a `role="status"` live region
- Licence: the analysis service is GPL-3.0-or-later (`analysis-service/LICENSE`) because it imports python-chess (GPL-3.0-or-later); the backend, the frontend and the rest of the repository stay MIT ([ADR 0002, amendment](docs/decisions/0002-python-analysis-service.md#amendment-2026-11-20-licence-of-the-analysis-service))
- The backend's tests use JUnit 6.1.3 instead of Spring Boot's 6.0.3 (the Cucumber engine is built against it)
- Dependabot holds TypeScript major updates for the frontend: typescript-eslint supports only TypeScript below 6.1, so TypeScript 7 broke the grouped npm PR (#58)
- Roadmap: the read-only findings screen comes before Phase 2's detectors; frontend decisions (react-chessboard, licences, layout, theme) recorded in [ADR 0007](docs/decisions/0007-frontend-board-and-visual-identity.md) (#49)

### Security

- `SECURITY.md` (#60): private vulnerability reporting (enabled on the repository), scope, and the local-use, no-authentication status
- Docker Compose publishes every port on `127.0.0.1` only, and the analysis service publishes no port; `ANALYSIS_SERVICE_PORT` is gone from `.env.example` (#60)
- Security headers from nginx (#60): a Content-Security-Policy that allows only same-origin resources and the one inline script by its hash, with `frame-ancestors 'none'`; `X-Frame-Options`, `Permissions-Policy`; no nginx version. A unit test keeps the CSP hash and the nginx includes correct; CI checks the headers on the running container
- The read API (#51) answers errors as RFC 9457 problem details without exception messages or stack traces, accepts GET only, and has no CORS configuration (same-origin through nginx); all tested
- CI runs with `permissions: contents: read`, and every action is pinned by commit SHA with its version as a comment (#60)

## [0.1.0] - 2026-11-16

Phase 1: import, analysis, hanging-piece detection and demo data. No user interface yet.

### Added

- Golden-file reproducibility test (#33): the full `/analyze` output for two demo games (White and Black user) is compared exactly on every CI run; `tests/golden/regenerate.py` regenerates it only when run explicitly
- `/analyze` and `/health` report the engine's threads and hash size; Flyway `V4` stores them on `move_evaluations` (NULL for older rows), and the backend's `EngineRun` carries them (#33)
- Demo data (#23): the `demo` Spring profile (`SPRING_PROFILES_ACTIVE=demo`, the default in `.env.example`) loads 20 real rapid games from the Lichess open database (2013-01, CC0) for the fictional user `demo` on first start, through the normal importer, as PENDING games for the background analysis; 7 of them have a hanging-piece finding. It switches the live Lichess import off. The games are selected by `tools/demo-data/select_demo_games.py`, which is deterministic and documents its source, checksum and rules
- Background analysis of imported games (#22): `AnalysisJob` analyses PENDING games (oldest first; newest first since #30), a bounded batch per run, `ANALYSIS_WORKERS` at a time (default 1). A game stuck in RUNNING longer than `ANALYSIS_STUCK_AFTER` (default 30 min) goes back to PENDING once, then FAILED; recovery runs at startup and on every run. FAILED games are not retried automatically
- Flyway `V3`: `analysis_attempts` and `analysis_started_at` on `games`, with check constraints (#22)
- Lichess import (#5): a background job fetches each Lichess account's newest finished rapid games from the game export API (NDJSON), at most `LICHESS_IMPORT_BATCH_SIZE` per run, and stores them as PENDING; games in progress, other speeds and variants, and games already stored (by Lichess game id) are skipped. Sends an identifying `User-Agent` (`LICHESS_USER_AGENT`) and the optional `LICHESS_API_TOKEN`. `LICHESS_USERNAME` creates the account to import for at startup. Tested against recorded Lichess responses
- Backend analyses stored games through the analysis service: `AnalysisClient` (RestClient, connect timeout 2 s, configurable read timeout, default 180 s) and `AnalysisService.analyzeGame`, which stores move evaluations and findings with their facts and never leaves a game RUNNING (#4)
- Flyway `V2`: `facts` JSONB column on `mistake_findings` (#4)
- End-to-end test: a known game through the backend, the real analysis service (built from its Dockerfile) and Stockfish, asserting the hanging-piece finding and a legal evidence line; the Phase 1 exit criterion (#4)
- Hanging-piece detector (`hanging-0.2`): `POST /analyze` returns `findings` with the motif, an evidence line in UCI and facts (piece, square, capturing piece, material lost, and the moved piece with its from and to squares) for user moves that leave a knight, bishop, rook or queen to be won. The material window extends while captures continue (up to 8 plies), and a forced mate against the user, even beyond the window, means no finding ([ADR 0004](docs/decisions/0004-hanging-piece-detector.md)) (#3)
- Analysis service `POST /analyze`: evaluates every half-move of one finished game with Stockfish and classifies it as BEST, GOOD, INACCURACY, MISTAKE or BLUNDER by the drop in winning chances (Lichess formula and thresholds); rejects unfinished, illegal or over-long games with 422 (#2)
- Engine settings `ENGINE_DEPTH`, `ENGINE_THREADS`, `ENGINE_HASH_MB`, `STOCKFISH_PATH`; `GET /health` reports the engine name, version and depth (#2)
- Analysis-service test suite (pytest), run inside the Docker image in CI (#2)
- `docs/devlog.md`, an engineering log; every PR adds or updates an entry
- Monorepo layout: `backend/`, `analysis-service/`, `frontend/` (placeholder)
- Docker Compose with PostgreSQL 17, the backend and the analysis service (Stockfish installed in its image)
- Spring Boot 4.1 backend skeleton with `GET /health` reporting database status
- Flyway migration `V1`: users, chess accounts, games, move evaluations, mistake findings, explanations, drills and drill attempts
- Domain entities with their rules: analysis lifecycle, evidence-backed findings, explanation verification, own-game and transfer drills with SM-2 scheduling
- Unit tests for the domain rules and Testcontainers integration tests
- FastAPI analysis-service stub with `GET /health`, reporting Stockfish availability
- Pattern counting helper: a motif counts at most once per game
- Transfer drills record the puzzle rating, so transfer can be measured over puzzles of comparable difficulty
- GitHub Actions CI, Dependabot, contribution rules, PR and issue templates, decision records

### Changed

- Reproducible analysis image (#33): base images pinned by digest in both Dockerfiles; Python dependencies locked with hashes (`requirements.in` -> `requirements.txt` via pip-compile `--generate-hashes`) and installed with `--require-hashes`; backend build image back to `maven:3.9-eclipse-temurin-25`, and Dependabot no longer proposes tag changes of `maven` (the JDK hides in the tag suffix)
- CONTRIBUTING: pull requests are opened as drafts and marked ready only with the final report
- The analysis job takes the newest PENDING games first, not the oldest (#30)
- Only `TEMPLATE` explanations that passed verification are showable; a passed LLM text is not (ADR 0003) (#30)
- Docker Compose starts the backend once the analysis service is healthy (a `/health` check that requires a working engine) (#30)
- Analysis read timeout (`ANALYSIS_SERVICE_READ_TIMEOUT`, 180 s) is now backed by a measurement: 105 to 165 ms per half-move at depth 16, about 50 s for the longest accepted game (#22)
- The Spring scheduler has two threads, so a long analysis run does not hold up the Lichess import (#22)
- Lichess test fixture: the two players of the recorded game in progress are replaced with placeholder names (#22)
- Backend tests split: unit tests (`*Test`) with Surefire, integration tests (`*IT`) with Failsafe in `mvn verify`; `SchemaIntegrationTest` renamed to `SchemaIT` (#4)
- Backend on Java 25 LTS everywhere: compiler target, CI, Maven build image (`maven:3.9-eclipse-temurin-25`) and runtime image (`eclipse-temurin:25-jre`)
- Dependabot opens one grouped PR per ecosystem; Java and Python base-image upgrades are done by hand
- The analysis-service image uses one pinned, checksum-verified official Stockfish 19 release binary instead of the Debian package; Stockfish runs single-threaded with a fixed hash and depth for reproducible results (#2)

### Fixed

- Analysis service PGN validation (#32): 422 for null moves, an invalid start position, more than one game, a movetext result that contradicts the `Result` header, and moves after checkmate, stalemate, insufficient material or the 75-move rule. A game that continues after a fivefold repetition is analysed up to it (real Lichess games do this). Unreadable input is always a 422, never a 500, and the analysis loop never reads a missing best move. A property-based test (Hypothesis) checks this on random, truncated and junk PGNs
- Hanging-piece detector `hanging-0.4` (#31 follow-up): when the user's capture is recaptured on the same square, all captures on that square in the engine's line are summed, and a finding needs a net loss of at least 2 there. Nxd5 exd5 Qxd5 with pawns on d5 (net 1) no longer fires
- Hanging-piece detector `hanging-0.3` (#31, [ADR 0004](docs/decisions/0004-hanging-piece-detector.md)): the loss is measured against the engine's best line from the position before the move, over windows of equal length that run through captures, checks and replies to checks (4 to 8 plies); a line still forcing at the end of its window is not judged. When the user's move captures and the opponent recaptures on the same square, only that exchange is judged (a knight taking a defended pawn: loss 2; an even trade: no finding). Otherwise the played line itself must lose at least 2 points, so a missed win does not count as a hung piece. The demo now shows hanging-piece findings in 5 of its 20 games (was 7)
- Background analysis handles failures by kind ([ADR 0005](docs/decisions/0005-failure-handling-in-background-analysis.md)) (#30): a rejected game (422) or an unreadable response is FAILED at once; an unavailable analysis service (connection refused, host not found, 5xx, other error statuses) puts the game back to PENDING without using an attempt, stops the run and backs off (twice `ANALYSIS_INTERVAL`, doubling up to 10 minutes, reset when the service answers); a read timeout puts the game back while attempts remain (2), then fails it. An outage of any length no longer fails games
- Games are claimed for analysis with one conditional update (PENDING -> RUNNING), so two workers can never analyse the same game (#30)
- Analysis-service CI job ran leftover host steps with an unpinned Ubuntu Stockfish after a merge; it now only builds and tests the image
- Stray `FROM python:3.15-slim` line at the top of the analysis-service Dockerfile

[Unreleased]: https://github.com/Gergo019/blindspot-chess/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/Gergo019/blindspot-chess/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/Gergo019/blindspot-chess/releases/tag/v0.1.0
