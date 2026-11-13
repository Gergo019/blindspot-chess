# BlindSpot

**Your chess blind spots: found in your own games, tested on new ones.**

BlindSpot finds a mistake you keep making in your own games, lets you practise it on the positions where it happened, and then checks whether the lesson carries over to positions you have never seen.

> Status: **Phase 1, foundations** (schema, services, CI). No coaching features yet.

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
- **Data sources**: the [Lichess API](https://lichess.org/api) for live imports, and the [Lichess open database](https://database.lichess.org/) (CC0) for a seeded demo and the held-out evaluation set, so a demo never depends on live imports.

## What this project demonstrates

| Area | Where |
|---|---|
| Java, Spring Boot, Hibernate/JPA | `backend/`: entities with domain behaviour, Spring Data repositories, schema validation |
| Database design | Flyway migrations with check constraints, partial indexes, JSONB |
| REST and service boundaries | backend calls the analysis service over REST; Lichess API integration |
| Domain-Driven Design | bounded contexts, entities that guard their own invariants |
| Testing | JUnit 5 unit tests, Testcontainers integration tests on real PostgreSQL; planned: WireMock for Lichess, Cucumber acceptance tests, Playwright end-to-end tests |
| React + TypeScript | `frontend/` (Phase 3) |
| CI/CD and containers | GitHub Actions, Docker multi-stage builds, Docker Compose; planned: Kubernetes manifests tested on kind |
| Applied AI, measured | validated typed claims, and a measured experiment comparing templates with AI explanations (planned) |
| Team workflow | [golden rules](CONTRIBUTING.md), PR templates, decision records in [`docs/decisions`](docs/decisions) |

## Getting started

Prerequisites: Docker with Docker Compose v2. For working on the backend outside Docker: Java 25 and Maven 3.9+.

```bash
cp .env.example .env
docker compose up --build
```

| Service | URL |
|---|---|
| Backend | http://localhost:8080/health |
| Analysis service | http://localhost:8000/health (reports the pinned Stockfish version and depth); `POST /analyze`, see [analysis-service](analysis-service/README.md) |
| PostgreSQL | `localhost:5432` (db/user/password from `.env`) |

Run only the database in Docker and the backend from your IDE:

```bash
docker compose up -d db
cd backend && mvn spring-boot:run
```

Tests (Docker must be running, Testcontainers starts a throwaway PostgreSQL):

```bash
cd backend && mvn verify
```

Analysis-service tests run inside its Docker image, with the same pinned Stockfish as production:

```bash
cd analysis-service && docker build --target test -t analysis-test . && docker run --rm analysis-test
```

## Data model

```
users ──< chess_accounts ──< games ──< move_evaluations ──< mistake_findings ──< explanations
  │                                                              │
  └───────────────────────────< drills >─────────────────────────┘
                                  │      (OWN_GAME from a finding, or TRANSFER from a puzzle)
                                  └──< drill_attempts
```

## Roadmap

- [ ] **Phase 1, pipeline**: background import of recent completed Lichess rapid games (bounded, deduplicated by game id), `POST /analyze` with pinned Stockfish, seeded demo data from the open database. **Done when** CI sends one known finished game through backend → analysis service → Stockfish and asserts the expected motif comes back with a replayable line.
- [ ] **Phase 2, findings**: detectors for the two motifs with evidence lines, the pattern report and its evidence threshold
- [ ] **Phase 3, practice**: board-first React screen, own-game and transfer drills, Playwright tests
- [ ] **Phase 4, explanations**: typed claims and templates, then the `LLM_FREE` / `LLM_FACTS` experiment and its evaluation report
- [ ] **Phase 5, operations**: Cucumber acceptance suite, Kubernetes manifests, hosted demo

## Contributing

All work follows the [golden rules](CONTRIBUTING.md).

## License

[MIT](LICENSE). Stockfish is GPL-3.0 and runs as a separate program; it is not linked into this code.
