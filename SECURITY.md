# Security policy

## Reporting a vulnerability

Please report security problems **privately**, not in a public issue or pull request:

1. Go to the repository's **Security** tab and choose **Report a vulnerability** ([direct link](https://github.com/Gergo019/blindspot-chess/security/advisories/new)). This is GitHub's private vulnerability reporting; only the maintainer sees the report.
2. Describe what you found, how to reproduce it (steps, requests, a minimal PGN if relevant), and what an attacker could do with it.

You can expect an acknowledgement within a week. This is a one-person project, so a fix may take longer; you will be kept informed in the advisory, and credited in it if you like.

## What this project is

BlindSpot is a **portfolio project meant to run locally** (`docker compose up`). It has **no authentication yet**: anyone who can reach the backend can read all imported games and findings. That is acceptable only because the stack is local:

- Every published port is bound to `127.0.0.1`, and the analysis service publishes no port at all ([docker-compose.yml](docker-compose.yml)).
- The default database password in `.env.example` is for local use. **Change `POSTGRES_PASSWORD` (and review every setting in `.env`) before any deployment.**
- Do not expose the stack to a network or the internet as it is. Authentication (Spring Security) comes with sign-up; the plan is tracked in [#60](https://github.com/Gergo019/blindspot-chess/issues/60).

## Scope

In scope, in this repository:

- `backend/` (Spring Boot): for example injection, data exposure beyond what is documented above, unsafe handling of imported Lichess data.
- `analysis-service/` (FastAPI): for example a PGN or request that crashes the service, makes it hang, or runs code. Malformed input must always give a 422 (ADR 0005, #32).
- `frontend/` and its nginx configuration: for example XSS, missing or weak security headers, CSP bypasses.
- The CI workflows, Dockerfiles and dependency pinning (supply chain).

Out of scope:

- The missing authentication described above, and anything that requires exposing the local stack to a network.
- Vulnerabilities in Stockfish, python-chess, Lichess or other third-party software themselves: please report those to their maintainers (see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)). A way in which BlindSpot *uses* them unsafely is in scope.
- Denial of service by sending very large volumes of traffic to a local instance.

## Supported versions

Only the latest release and `main` receive fixes.
