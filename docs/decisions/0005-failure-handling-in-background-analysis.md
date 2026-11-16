# 0005: Failure handling in background analysis

- Status: accepted
- Date: 2026-11-16 (#30)

## Context

The background job sends PENDING games to the analysis service one at a time. Until #30, every failed call marked the game FAILED, and FAILED is never retried. A minute-long outage of the analysis container (a restart, a deploy, Compose starting the backend first) therefore failed a whole batch of games for good, within milliseconds. A failure can be the game's fault or the service's, and the backend has to tell them apart without parsing error messages.

## Decision

`AnalysisClient` turns every failed call into an `AnalysisFailedException` with a typed `Kind`, decided from the exception type and the status code. `AnalysisService` acts on the kind:

| Kind | When | The game | Attempt |
|---|---|---|---|
| `REJECTED` | HTTP 422: the service rejects the game (unfinished, illegal, too long) | FAILED at once, the reason logged | counted |
| `UNAVAILABLE` | connection refused or connect timeout, host not found, HTTP 5xx, any other error status | back to PENDING | **not counted** (given back) |
| `TIMEOUT` | connected, but no response within the read timeout | back to PENDING while attempts remain, then FAILED | counted |
| `INVALID_RESPONSE` | a 2xx with no body or an unreadable body | FAILED at once | counted |

- **An outage of any length never fails a game.** `UNAVAILABLE` gives the attempt back, so `analysis_attempts` counts only attempts that said something about the game.
- **A timeout may be the game's fault** (a pathological game), so it counts. With `MAX_ANALYSIS_ATTEMPTS` = 2, a game that times out twice is FAILED. The same limit applies to a game left RUNNING by a crash (#22).
- **Other error statuses** (400, 404, 405, ...) count as `UNAVAILABLE`: they come from a wrong URL or a contract mismatch, which would hit every game the same way.
- **The run stops at the first `UNAVAILABLE`** instead of draining the batch. With several workers, games not yet started are left alone.
- **Back-off.** After an `UNAVAILABLE` run, the job waits twice the interval, doubling after each further failure, capped at 10 minutes (never below the interval). With the default 30 s, the waits are 60 s, 120 s, 240 s, 480 s, then 600 s. The first run the service answers (a game ends DONE or FAILED) resets the wait to the interval. A run with only timeouts leaves the back-off as it is.
- **Atomic claim.** A worker claims a game with one conditional update: `analysis_status` RUNNING, `analysis_attempts` + 1, `analysis_started_at` now, `WHERE id = ? AND analysis_status = 'PENDING'`. It analyses the game only if exactly one row changed. PostgreSQL re-checks the condition on the locked row, so of two concurrent claims exactly one wins, even if both workers read the game as PENDING.
- **No retry of FAILED.** FAILED stays terminal, as decided in #22. A FAILED game is never claimed, because the claim requires PENDING.

## Consequences

- Restarting or redeploying the analysis service is safe at any time. The backend stops, waits, and resumes where it was.
- During a long outage the backend tries once every 10 minutes and logs one warning per try.
- `TIMEOUT` cannot tell a slow game from an overloaded service. If timeouts start to cluster across games, the read timeout or the number of workers is wrong, and the log shows the kind for each case.
- The claim is a repository query, not an entity method, so the PENDING -> RUNNING transition exists twice: in the query, and in `Game.startAnalysis` (used in memory). Both are documented to stay in step.
- Docker Compose starts the backend only once the analysis service reports a working engine (`healthcheck` on `/health`), so the first run does not meet a service that is still starting. The back-off covers every later outage.

Rejected alternatives:
- **Retrying FAILED games on a timer:** a bad game would be retried forever.
- **Optimistic locking with `@Version`:** it needs a retry path and still lets the losing worker log a failure.
- **`SELECT ... FOR UPDATE SKIP LOCKED`:** the lock would have to be held across a minutes-long HTTP call, or released before it, which is the conditional update with extra steps.
