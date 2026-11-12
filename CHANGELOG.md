# Changelog

All notable changes to this project are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

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
