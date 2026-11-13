# Engineering log

A chronological record of what was done, why, and what to change if something breaks. Newest entry at the top. Every pull request adds or updates an entry.

Entry format: Goal, Changes, Decisions (with the reason and the alternative rejected), Verification, Open points / risks, Links.

## 2026-11-13: POST /analyze with pinned Stockfish (#2 / PR pending)

**Goal:** The analysis service evaluates one finished game with a pinned, reproducible Stockfish and classifies every half-move.

**Changes:** In progress.

**Decisions:** In progress.

**Verification:** In progress.

**Open points / risks:** In progress.

**Links:** [#2](https://github.com/Gergo019/blindspot-chess/issues/2), [ADR 0002](decisions/0002-python-analysis-service.md)

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
