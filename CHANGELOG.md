# Changelog

All notable changes to this project are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

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

- Backend on Java 25 LTS everywhere: compiler target, CI, Maven build image (`maven:3.9-eclipse-temurin-25`) and runtime image (`eclipse-temurin:25-jre`)
- Dependabot opens one grouped PR per ecosystem; Java and Python base-image upgrades are done by hand
- The analysis-service image uses one pinned, checksum-verified official Stockfish 19 release binary instead of the Debian package; Stockfish runs single-threaded with a fixed hash and depth for reproducible results (#2)

### Fixed

- Analysis-service CI job ran leftover host steps with an unpinned Ubuntu Stockfish after a merge; it now only builds and tests the image
- Stray `FROM python:3.15-slim` line at the top of the analysis-service Dockerfile
