# BlindSpot

**Your chess blind spots: found in your own games, tested on new ones.**

BlindSpot finds a mistake you keep making in your own games, lets you practise it on the positions where it happened, and then checks whether the lesson carries over to positions you have never seen.

> Status: **v0.1.0: Phase 1 complete (import, analysis, hanging-piece detection, demo data).** Since then: the web app with a read-only findings screen (#50, #51, #24).

## The coaching loop

```
 your Lichess games ──► engine analysis ──► recurring mistake ──► practise your positions ──► transfer test
   (completed rapid)     (pinned Stockfish)   (with board evidence)   (spaced repetition)       (unseen puzzles)
```

1. **Import** a bounded batch of your recent, completed Lichess rapid games in the background.
2. **Analyse** every move with a pinned Stockfish version and a fixed search budget, so results are reproducible.
3. **Detect** two motifs that code can recognise reliably: *allowing a knight fork* and *leaving a piece hanging*. Each finding stores the line of moves that proves it.
4. **Report** a pattern only with enough evidence: the motif in at least **3 distinct games among your last 20 eligible rapid games**, counted at most once per game, with the sample size shown. For example: *"You allowed a knight fork in 3 of your last 20 rapid games. Here are the three positions."* Below the threshold, show the individual examples and do not call them a pattern. The threshold is a starting heuristic, to be tuned after reviewing where the detector fires (for example in positions that were already lost, or after intentional sacrifices).
5. **Drill** your own positions ("find a better move") with spaced repetition.
6. **Test transfer** on several unseen puzzles with the same motif and a comparable rating from the Lichess puzzle database. Solving your old position might only mean you remember the move. Recognising the same threat on new boards is a better signal that you learned it; one puzzle makes a demo, several make a measurement.

Openings are deliberately out of scope for v1: leaving "your" line is often fine, and the opponent may be the one who deviated.

## Explanations: validated claims first, AI as an experiment

Checking that an explanation mentions the right moves and squares is not enough: a sentence can use every correct term and still reverse who threatens whom. So in v1 no free-form text reaches a player:

1. **Code produces typed claims** for each finding, such as *the black knight on d4 attacks c2 and e2 after Nxe5*, and validates each one by replaying the evidence line with python-chess.
2. **Templates turn the validated claims into text** (`TEMPLATE` mode). Who attacks whom is part of the claim, so the text cannot get it backwards.
3. **AI explanations are an experiment**, measured on a held-out set from the Lichess open database:

| Mode | How the text is made | Status |
|---|---|---|
| `TEMPLATE` | Rendered from validated claims | Shipped in v1 |
| `LLM_FREE` | An LLM sees only the position and the move: the baseline | Experiment |
| `LLM_FACTS` | An LLM is given the validated claims | Experiment |

The evaluation reports, per mode, unsupported claims (including examples that reuse the right terms with the roles reversed), how often the template fallback would have been needed, and which explanation players found clearer. Details: [ADR 0003](docs/decisions/0003-facts-first-explanations.md).

## Product rules

- **Completed games only.** Never analyse a game in progress.
- **Every coaching claim shown to a player has board evidence** they can play through.
- **Only verified explanations are shown** (`Explanation.isShowable()`, `ExplanationRepository.findShowable`).

## Architecture

```
┌──────────────────────┐   REST   ┌──────────────────────────┐   REST   ┌────────────────────────────┐
│ frontend (Phase 3)   ├─────────►│ backend                  ├─────────►│ analysis-service           │
│ React + TypeScript   │          │ Java 25, Spring Boot 4.1 │          │ Python, FastAPI            │
│ board-first UI       │          │ import, findings, drills │          │ python-chess + Stockfish   │
└──────────────────────┘          └───────────┬──────────────┘          │ motif detection, verifier  │
                                              │ JPA + Flyway            └────────────────────────────┘
                     Lichess API ────────────►│
                                  ┌───────────▼──────────────┐
                                  │ PostgreSQL 17            │
                                  └──────────────────────────┘
```

- **backend** owns the domain and all persistence, organised by bounded context: `account`, `game`, `analysis`, `training`. Domain rules live in the entities (analysis lifecycle, verification, SM-2 scheduling), not in services.
- **analysis-service** owns chess knowledge: engine analysis, motif detection, fact extraction and explanation verification. It is stateless; the backend is its only caller. Why Python: [ADR 0002](docs/decisions/0002-python-analysis-service.md).
- **backend → analysis-service** is real and end-to-end tested: `AnalysisService.analyzeGame` claims a PENDING game atomically (a conditional update to RUNNING), calls `POST /analyze` outside any transaction (`AnalysisClient`, connect timeout 2 s, read timeout `ANALYSIS_SERVICE_READ_TIMEOUT`, default 180 s), then stores one move evaluation per half-move and one finding per detected motif (with its facts) and marks the game DONE. Failures are handled by kind ([ADR 0005](docs/decisions/0005-failure-handling-in-background-analysis.md)): a rejected game (422) is FAILED at once; an unavailable service (connection refused, host not found, 5xx) puts the game back to PENDING without using an attempt; a read timeout puts it back while attempts remain, then fails it. A game is never left RUNNING.
- **Lichess → backend** is real: `LichessImportJob` runs every `LICHESS_IMPORT_INTERVAL` (default 1 h) for each Lichess account and asks the [game export](https://lichess.org/api#tag/games/GET/api/games/user/{username}) for the newest `LICHESS_IMPORT_BATCH_SIZE` (default 20) finished rapid games as NDJSON. Games in progress, other speeds, other variants and games already stored (same Lichess game id) are skipped; the rest are stored as PENDING for analysis. Until there is sign-up, `LICHESS_USERNAME` names the one account to import for.
- **Background analysis**: `AnalysisJob` runs every `ANALYSIS_INTERVAL` (default 30 s). It takes the newest PENDING games (the pattern report reads the last 20), at most `ANALYSIS_BATCH_SIZE` (10) per run, and analyses them `ANALYSIS_WORKERS` at a time (default 1: each analysis is one single-threaded Stockfish). If the analysis service is unavailable, the run stops and the job backs off: twice the interval, doubling up to 10 minutes, back to the interval once the service answers. An outage never fails a game. A game stuck in RUNNING for longer than `ANALYSIS_STUCK_AFTER` (30 min; checked at startup and on each run) goes back to PENDING once, and is marked FAILED if it gets stuck again. FAILED games stay FAILED, with the reason in the log.
- **Data sources**: the [Lichess API](https://lichess.org/api) for live imports, and the [Lichess open database](https://database.lichess.org/) (CC0) for a seeded demo and the held-out evaluation set, so a demo never depends on live imports.

## What this project demonstrates

| Area | Where |
|---|---|
| Java, Spring Boot, Hibernate/JPA | `backend/`: entities with domain behaviour, Spring Data repositories, schema validation |
| Database design | Flyway migrations with check constraints, partial indexes, JSONB |
| REST and service boundaries | backend calls the analysis service over REST; Lichess API integration |
| Domain-Driven Design | bounded contexts, entities that guard their own invariants |
| Testing | JUnit 5 unit tests (Surefire), Testcontainers integration tests (`*IT`, Failsafe) on real PostgreSQL, an end-to-end test that runs a known game through the backend, the real analysis service and Stockfish, and the Lichess import tested against recorded API responses (never the live API); Playwright end-to-end tests against the real stack with axe accessibility checks; planned: Cucumber acceptance tests |
| React + TypeScript | `frontend/`: Vite, strict TypeScript, ESLint, Vitest + Testing Library, a WCAG contrast test over every theme colour pair, served by nginx as a non-root container |
| CI/CD and containers | GitHub Actions, Docker multi-stage builds, Docker Compose; planned: Kubernetes manifests tested on kind |
| Applied AI, measured | validated typed claims, and a measured experiment comparing templates with AI explanations (planned) |
| Team workflow | [golden rules](CONTRIBUTING.md), PR templates, decision records in [`docs/decisions`](docs/decisions) |

## Getting started

Prerequisites: Docker with Docker Compose v2. For working on the backend outside Docker: Java 25 and Maven 3.9+.

```bash
cp .env.example .env
docker compose up --build
```

**The stack is for local use** ([SECURITY.md](SECURITY.md)): there is no authentication yet, every published port is bound to `127.0.0.1`, and the analysis service publishes none. The database password in `.env.example` is a local default: **change `POSTGRES_PASSWORD` before any deployment.**

| Service | URL |
|---|---|
| Web app | http://localhost:8081 (React, served by nginx; `/api` is proxied to the backend) |
| Backend | http://localhost:8080/health |
| Analysis service | no published port; only the backend calls it, on the Compose network. Its health (the pinned Stockfish version, depth, threads and hash size): `docker compose exec analysis-service python -c "import urllib.request; print(urllib.request.urlopen('http://localhost:8000/health').read().decode())"`. `POST /analyze`: see [analysis-service](analysis-service/README.md) |
| PostgreSQL | `localhost:5432` (db/user/password from `.env`) |

**Demo (the default in `.env.example`):** `SPRING_PROFILES_ACTIVE=demo` loads 20 real rapid games from the [Lichess open database](https://database.lichess.org/) (January 2013, CC0) for a fictional player `demo` on the first start. The background analysis then works through them one at a time, in about 3 to 4 minutes. Afterwards the database holds 20 analysed games, with hanging-piece findings in 5 of them (`hanging-0.4`). No Lichess account or token is needed, and the demo profile never calls the Lichess API. To look at the findings:

```bash
docker compose exec db psql -U blindspot -d blindspot -c \
  "select g.external_id, g.user_color, me.ply, me.move_san, mf.evidence_line_uci
   from mistake_findings mf join move_evaluations me on me.id = mf.move_evaluation_id
   join games g on g.id = me.game_id order by g.external_id"
```

The demo is loaded only if the user `demo` does not exist yet; `docker compose down -v` starts over. How the games were chosen: [tools/demo-data](tools/demo-data/README.md).

**Your own games:** set `SPRING_PROFILES_ACTIVE=` (empty) and `LICHESS_USERNAME` in `.env` before starting: about 10 seconds after startup the backend fetches your newest finished rapid games from Lichess and stores them, then repeats every hour. The analysis job then analyses them one at a time; a rapid game takes 5 to 10 seconds. No token is needed for public games; `LICHESS_API_TOKEN` only raises the rate limit.

Run only the database in Docker and the backend from your IDE:

```bash
docker compose up -d db
cd backend && mvn spring-boot:run
```

The backend then expects the analysis service at `http://localhost:8000` (`ANALYSIS_SERVICE_URL`). Compose does not publish that port, so to analyse games from the IDE, run the service on its own, reachable only from this machine:

```bash
docker build -t analysis-service analysis-service
docker run --rm -p 127.0.0.1:8000:8000 analysis-service
```

Backend tests: `mvn test` runs the unit tests; `mvn verify` also runs the integration tests (`*IT`). Docker must be running: Testcontainers starts a throwaway PostgreSQL and builds the analysis service from `analysis-service/Dockerfile` for the end-to-end test (the first build takes a minute or two).

```bash
cd backend && mvn verify
```

Analysis-service tests run inside its Docker image, with the same pinned Stockfish as production:

```bash
cd analysis-service && docker build --target test -t analysis-test . && docker run --rm analysis-test
```

### Web app

![The findings screen in the dark theme: a demo game seen from Black, the move 18… Rb6 as an orange arrow, the capturing knight marked in blue; on the right the blind-spot report (hanging piece in 5 of the last 20 rapid games, a pattern), the list of findings and the selected finding's facts](docs/images/findings-screen-dark.png)

The findings screen (#24): the blind-spot report, your findings, and for the selected one the board before your move, the evidence line to step through (chips, arrow keys), the engine's best move on request, the game's moves and how the finding was produced. Each finding has its own address (`/findings/:id`). Dark is the default; the toggle in the header switches to the light theme and the choice is remembered. For frontend development without Docker (Node 26, the version in `frontend/.nvmrc`), see [frontend](frontend/README.md).

### End-to-end tests

Playwright tests (`frontend/e2e/`) drive the real stack with the 20 demo games in Chromium: the blind-spot card, the findings list and its URLs, deep links, stepping through the evidence line on the board, the best move and the move list, a narrow screen, the theme, the error state and an axe accessibility check in both themes. Every scenario also fails on a CSP violation or a console error. They run in the Playwright image pinned in CI (same Chromium, Node 26), so nothing needs installing:

```bash
cp .env.example .env && docker compose up -d --build      # the demo profile is the default
frontend/e2e/wait-for-demo.sh                               # until all 20 demo games are analysed (a few minutes)
cd frontend && docker run --rm --ipc=host -e E2E_BASE_URL=http://host.docker.internal:8081 \
  -v "$PWD:/app" -w /app \
  mcr.microsoft.com/playwright:v1.65.0-noble@sha256:9beb546099e11f52d62e505537914870e01a67e0e6b00ae41da4775324e73dbe \
  sh -c "npm ci && npx playwright test"
```

On Linux, use `--network host -e E2E_BASE_URL=http://localhost:8081` instead of `host.docker.internal`. With Node 26 and `npx playwright install chromium` on the machine, `npm run e2e` in `frontend/` works too. A failed run leaves a trace per test in `frontend/test-results/` (`npx playwright show-trace <file>`); in CI the job "End-to-end (Playwright)" uploads the report, the traces and the stack's logs as an artifact.

### Product rules as features

The business rules are written as Cucumber features in plain product language in [`backend/src/test/resources/features/`](backend/src/test/resources/features): when a repeated mistake counts as a pattern (3 distinct games among the last 20 analysed rapid games, once per game, the sample size always reported), that only verified template explanations are shown, and that kinds of mistake without a detector are not reported. The steps seed data through the repositories and check everything through the real HTTP API against PostgreSQL (Testcontainers). They run with the other integration tests: `cd backend && mvn verify` (or only them: `mvn verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=BusinessRulesIT`).

### Read API

The backend serves a read-only JSON API under `/api` ([#51](https://github.com/Gergo019/blindspot-chess/issues/51)). The web app calls it on its own origin through nginx (http://localhost:8081/api/...), so there is no CORS configuration; the backend answers directly on http://localhost:8080/api/... too. The OpenAPI description is at [`/api/docs`](http://localhost:8081/api/docs).

| Endpoint | Returns |
|---|---|
| `GET /api/patterns` | The blind-spot report: for every motif that has a detector (motifs without one, such as the knight fork until #26, are left out rather than shown as 0), in how many distinct games among the last 20 analysed rapid games it occurred, the sample size (always given, also below the threshold) and whether it reaches the pattern threshold of 3 games |
| `GET /api/findings?motif=&page=&size=` | Findings, newest game first. `motif` is `HANGING_PIECE` or `ALLOWED_KNIGHT_FORK` (optional); `page` from 0; `size` 1 to 50 (default 20) |
| `GET /api/findings/{id}` | One finding with what the board needs: `fenBefore`, the move `played` (UCI and SAN) and the engine's `best` move (UCI), the `evidenceLineUci` (continuing after the played move), the checked `facts`, the engine settings, the game (opponent, date, colour, Lichess link) and `explanation`, which is null unless a TEMPLATE explanation passed verification |
| `GET /api/games/{id}/moves` | The game's half-moves with the engine's classification, for the move list |

```bash
curl -s localhost:8081/api/patterns
curl -s "localhost:8081/api/findings?motif=HANGING_PIECE&size=5"
```

- **Moves are UCI plus the FEN they are played from.** SAN is included only where the analysis service stored it (the played move); the frontend derives notation for display.
- **Whose data:** until there is sign-up, the configured player: the `demo` user with the demo profile, otherwise `LICHESS_USERNAME`. With neither, the report and the list are empty.
- **Errors** are [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem details (`application/problem+json`): 400 for a bad parameter, 404 for an unknown id (or one of another player), 405 for anything but GET. They never contain exception messages or stack traces.

## Data model

```
users ──< chess_accounts ──< games ──< move_evaluations ──< mistake_findings ──< explanations
  │                                                              │
  └───────────────────────────< drills >─────────────────────────┘
                                  │      (OWN_GAME from a finding, or TRANSFER from a puzzle)
                                  └──< drill_attempts
```

## Roadmap

**Next, in this order** (decided 2026-11-16, [ADR 0007](docs/decisions/0007-frontend-board-and-visual-identity.md)): the read-only findings screen comes before Phase 2's detectors, so the existing findings can be seen and checked on a board, and the React frontend exists in the repo. ADR 0007 ([#49](https://github.com/Gergo019/blindspot-chess/issues/49)) → frontend scaffold and theme ([#50](https://github.com/Gergo019/blindspot-chess/issues/50)) → findings and patterns API ([#51](https://github.com/Gergo019/blindspot-chess/issues/51)) → findings screen ([#24](https://github.com/Gergo019/blindspot-chess/issues/24)) → Playwright tests ([#52](https://github.com/Gergo019/blindspot-chess/issues/52)) → one or two Cucumber scenarios → knight-fork detector ([#26](https://github.com/Gergo019/blindspot-chess/issues/26)).

- [x] **Phase 1, pipeline** (v0.1.0): background import of recent completed Lichess rapid games (bounded, deduplicated by game id) ✓, `POST /analyze` with pinned Stockfish ✓, background analysis of imported games ✓, seeded demo data from the open database ✓. **Done when** CI sends one known finished game through backend → analysis service → Stockfish and asserts the expected motif comes back with a replayable line.
- [ ] **Phase 2, findings**: detectors for the two motifs with evidence lines, the pattern report and its evidence threshold
- [ ] **Phase 3, practice**: board-first React screen (started early, see above), own-game and transfer drills, Playwright tests
- [ ] **Phase 4, explanations**: typed claims and templates, then the `LLM_FREE` / `LLM_FACTS` experiment and its evaluation report
- [ ] **Phase 5, operations**: Cucumber acceptance suite (first scenarios after the Playwright tests), Kubernetes manifests, hosted demo

## Contributing

All work follows the [golden rules](CONTRIBUTING.md).

## License

- `backend/`, `frontend/` and everything else: [MIT](LICENSE).
- `analysis-service/`: [GPL-3.0-or-later](analysis-service/LICENSE), because it imports python-chess (GPL-3.0-or-later). The backend and the frontend only call it over HTTP, so they stay MIT ([ADR 0002, amendment](docs/decisions/0002-python-analysis-service.md#amendment-2026-11-20-licence-of-the-analysis-service)).
- Stockfish is GPL-3.0 and runs as a separate program inside the analysis service's image.

Third-party software, the chess pieces and the font are listed with their licences in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
