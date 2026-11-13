# 0001: Monorepo with a Java backend and a Python analysis service

- Status: accepted
- Date: 2026-11-12

## Context

BlindSpot needs a web application (accounts, imports, drills, REST API) and chess computation (engine analysis, motif detection, explanation checks). It is built by one person, as a portfolio project for full-stack Java roles, with an applied-AI component.

## Decision

- One repository with `backend/` (Java 25, Spring Boot), `analysis-service/` (Python, FastAPI) and `frontend/` (React, TypeScript).
- The backend owns the domain and all persistence. The analysis service is stateless and is called only by the backend, over REST.
- Docker Compose runs the whole stack locally and in CI.

## Consequences

- One clone, one CI pipeline, one place for decisions and issues; cross-service changes land in a single PR.
- Only one service writes to the database, so data rules have one owner.
- Two languages to maintain. Accepted because each side uses the strongest ecosystem for its job (see 0002).
- The REST boundary has to be versioned with care once the frontend depends on the backend.
