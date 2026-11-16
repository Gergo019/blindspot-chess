# Engineering log

A chronological record of what was done, why, and what to change if something breaks. Newest entry at the top. Every pull request adds or updates an entry.

Entry format: Goal, Changes, Decisions (with the reason and the alternative rejected), Verification, Open points / risks, Links.

## 2026-11-14: Seeded demo data from the Lichess open database (#23 / PR #29)

**Goal:** A demo that works without live Lichess imports and shows the pipeline on real games: `cp .env.example .env && docker compose up --build` gives a reviewer analysed games with findings, with no Lichess account.

**Changes:**
- `tools/demo-data/select_demo_games.py`, `requirements.txt` (`chess==1.11.2`), `README.md` (source URL, checksum, CC0, selection rules, exact steps, the result) and `selection-2013-01.json` (the run's report). The downloaded file lives in the git-ignored `tools/demo-data/cache/`.
- `backend/src/main/resources/demo/games.ndjson`: the 20 selected games in the Lichess export format.
- `backend/.../demo/DemoDataLoader.java`: with the `demo` profile and no user `demo`, it creates the user and a Lichess account `demo`, then imports the file through `LichessImportService.importGames`. That is the importer's own mapping, skip rules and storing, so the games are PENDING and the normal `AnalysisJob` analyses them. It runs in one transaction, and fails startup if any demo game is not importable.
- `LichessImportService.importGames(accountId, games)` (public; `importRecentGames` now uses it) and `LichessNdjson.parse` (shared with `LichessClient`).
- `application-demo.yml`: switches the live Lichess import off.
- `docker-compose.yml`: `SPRING_PROFILES_ACTIVE` passed to the backend. `.env.example`: `SPRING_PROFILES_ACTIVE=demo` with a comment.
- `DemoDataIT` (demo profile, PostgreSQL): 20 games for `demo`, PENDING, rapid, no duplicate ids, both colours, opponents are placeholders; loading again adds nothing; the Lichess import job does not exist in the profile.
- `README.md` (status, Getting started, roadmap), `CHANGELOG.md`.
- PR #28 entry: clarified that startup recovery uses the stuck timeout (below).

**Decisions:**
- **Month 2013-01** (`lichess_db_standard_rated_2013-01.pgn.zst`, 17.8 MB, SHA-256 `aa40b367…3fd1635`, matching Lichess's `sha256sums.txt`). It is small, and the earliest months are as real as any. Downloaded once locally; CI never touches the database.
- **Rapid by time control,** `480 ≤ initial + 40 × increment < 1500` seconds, Lichess's definition. The 2013 `Event` tags only know Bullet, Blitz and Classical, so 5+5 games say "Classical" there. Finished = result 1-0/0-1/½-½ and termination `Normal` or `Time forfeit`. 20 to 300 half-moves.
- **Deterministic selection:**
  - candidates sorted by Lichess game id;
  - the user's colour alternates White/Black along that order;
  - games analysed in order by our own analysis-service image (depth 16);
  - the first 7 games with a hanging-piece finding on the user's side, and the first 13 with no finding, taken; the run stops when both quotas are full.
  
  Seven, so the pattern threshold (3 distinct games) is met with room to spare; thirteen, so most games show that the detector stays quiet. Rejected: random sampling (not reproducible), and analysing both colours of every game (twice the engine time for no gain).
- **Anonymisation:** players become `demo` and `opponent_01`…`opponent_20`. The original Lichess game id stays as `id`, and in the `Site` and `GameId` PGN tags, so every game is traceable; `source` names the database month. Ratings, date, time control, opening and termination are kept. Comments and clocks are dropped. Keeping the original id as `id`, rather than a separate field, also makes it the external id that deduplication uses.
- **No special analysis path:** the loader only stores PENDING games through the importer, and `AnalysisJob` does the rest. Rejected: shipping precomputed evaluations, which would bypass the pipeline the demo is meant to show and would go stale with a new engine or detector version.
- **The demo profile switches the live import off.** The demo account is a Lichess account named `demo`, and the job must never ask Lichess for that name's games.
- **`SPRING_PROFILES_ACTIVE=demo` is the default in `.env.example`, not in `docker-compose.yml`.** A fresh clone following the README gets the demo. The CI Compose job, which has no `.env`, keeps the plain profile, and so does anyone who leaves the variable empty.

**Selection run:** 121,332 games in the file; 39,080 passed the header rules; 21 analysed, 7 with a hanging-piece finding and 14 without. 20 selected: 10 games each with `demo` as White and as Black; the findings are 5 as Black, 2 as White. **194 s** for the whole run, 191 s of it engine analysis (3.7 to 14.6 s per game). Per-game table: `tools/demo-data/README.md`.

**Verification:**
- `mvn verify` in Docker: 44 unit tests and 11 integration tests (`DemoDataIT` 2 new), all green.
- Full stack with `SPRING_PROFILES_ACTIVE=demo`, built from scratch:
  - "Demo data: user 'demo' with 20 games"; two analysis runs of 10; all 20 games DONE **247 s** after `docker compose up` (image build included).
  - The stored findings are exactly the 7 from the selection run, on the same plies (9, 36, 66, 20, 22, 16, 47), all `hanging-0.2`.
  - A backend restart logged "user 'demo' exists, nothing loaded", and the game count stayed 20.
- CI on PR #29, first run green, **2m54s** in all. Backend job 1m35s: 44 unit tests; `DemoDataIT` 2.7 s, `LichessImportIT` 2.8 s, `SchemaIT` 14.9 s, `AnalysisEndToEndIT` 53.8 s (includes the analysis image build). Analysis-service job 58s; Compose stack 1m08s.

**Clarification for PR #28 (crash recovery):** startup recovery does not ignore the stuck timeout; it applies the same `stuck-after` rule as every run. The live test in #28 used `ANALYSIS_STUCK_AFTER=30s` (and a 20 s read timeout) and restarted 32 s after the kill, which is why the game was recovered at startup. With the default 30 min, a crashed game is recovered by the first check after 30 minutes.

**Open points / risks:**
- The demo and the findings depend on the engine and the detector. A Stockfish or detector change can move findings; then rerun the tool and update the file, `DemoDataIT.DEMO_GAMES` and the numbers in its README.
- Switching a database that holds the demo to live imports would make the import job ask Lichess for the user `demo`. Use a fresh database (`docker compose down -v`) or keep the demo profile.
- No screen shows the findings yet (#24); the README gives a SQL query.

**Links:** [#23](https://github.com/Gergo019/blindspot-chess/issues/23), [PR #29](https://github.com/Gergo019/blindspot-chess/pull/29), [Lichess open database](https://database.lichess.org/), [tools/demo-data](../tools/demo-data/README.md)

## 2026-11-13: Background analysis of imported games (#22 / PR #28)

**Goal:** Every imported game gets analysed without anyone triggering it: one game at a time, oldest first, recovering games left RUNNING by a crash, and never retrying failures forever.

**Changes:**
- `backend/.../analysis/AnalysisJob.java`: `runOnce()` recovers stuck games, then takes the oldest PENDING games (by `played_at`, at most `batch-size`) and analyses them with `workers` threads (one: the job's own thread, in order). `recoverStuckGames()` handles RUNNING games whose attempt started more than `stuck-after` ago. A game that throws (for example, no longer PENDING) is logged and skipped; the batch goes on.
- `AnalysisJobScheduler.java`: recovery at `ApplicationReadyEvent`, then `runOnce()` every `interval` after the previous run ends. Switched off with `blindspot.analysis.job.enabled=false` (the integration tests do this and call the job directly).
- `AnalysisJobProperties.java`: `ANALYSIS_WORKERS` (1), `ANALYSIS_BATCH_SIZE` (10), `ANALYSIS_INTERVAL` (30s), `ANALYSIS_STUCK_AFTER` (30m), `ANALYSIS_JOB_ENABLED`. Startup fails if `stuck-after` is not longer than the read timeout, or workers or batch size is below 1.
- `Game`: `startAnalysis(Instant)` counts the attempt and records its start; `isAnalysisStuck(now, timeout)`; `recoverStuckAnalysis()` (PENDING while attempts < `MAX_ANALYSIS_ATTEMPTS` = 2, otherwise FAILED). `AnalysisService` reads the time from the `Clock` bean.
- `V3__analysis_attempts.sql`: `analysis_attempts INTEGER NOT NULL DEFAULT 0` and `analysis_started_at TIMESTAMPTZ` on `games`. Games already RUNNING get one attempt, started at `updated_at`. Check constraints: attempts ≥ 0, and RUNNING requires a start time. V1 and V2 untouched.
- `GameRepository.findByAnalysisStatusAndAnalysisStartedAtBefore`.
- `application.yml`: the job settings; `spring.task.scheduling.pool.size: 2`. `docker-compose.yml`, `.env.example`.
- Tests:
  - `AnalysisJobTest` (6): order, batch, the worker cap, one failure not stopping the batch, recovery once then FAILED, settings checks.
  - `DomainRulesTest` (+4) and `AnalysisServiceTest`: attempt and start time.
  - `SchemaIT` (+3): the stuck query on PostgreSQL, and the V3 constraints.
  - `AnalysisEndToEndIT` (+1): import fixture → PENDING → `AnalysisJob.runOnce()` → DONE, with the HANGING_PIECE finding.
- Fixtures:
  - `lichess/knight-game.ndjson`: a hand-written record in the export format, labelled as not recorded.
  - The players of the recorded game in progress are replaced with `tv_white_player` and `tv_black_player`, and their flair is removed.

**Decisions:**
- **Concurrency is capped in the backend, at one game at a time by default.** Reason: Stockfish runs single-threaded on purpose (reproducible results), each `/analyze` call starts its own engine, and the analysis container should not be overloaded. The backend is the analysis service's only caller and already owns the queue (PENDING games), so a cap there is enough today. Rejected for now: a cap inside the analysis service (a semaphore or a 429). It would also protect against other callers, but there are none, and the backend would then need retry logic for refused calls. Revisit if the service gets a second caller or the backend runs as several instances. The single-worker case runs on the job thread, so no thread pool is created.
- **Read timeout stays at 180 s, now from a measurement** (below). At about 165 ms per half-move in the worst observed case, the longest game the service accepts (300 half-moves) takes about 50 s. 180 s leaves a margin of about 3.6× for slower hardware such as CI runners.
- **Stuck = RUNNING longer than `stuck-after` (30 min), checked at startup and on every run,** as specified. A crashed attempt sends the game back to PENDING once (attempt 1), and a second stuck attempt marks it FAILED. Because `stuck-after` must exceed the read timeout, a live call has always timed out (and marked the game FAILED) before recovery could touch it. Rejected: resetting every RUNNING game at startup. That is simpler with one instance, but wrong as soon as two instances share the database.
- **FAILED stays FAILED.** The job only selects PENDING games, and the reason is in the log (`AnalysisService` logs the status code and the service's message). The domain still allows a manual retry from FAILED, but nothing does it automatically.
- **Oldest first by `played_at`,** the game's own date, using the existing `findByAnalysisStatusOrderByPlayedAtAsc`. Rejected: import order (`created_at`). Within one import the two differ only by the batch's internal order.
- **Two scheduler threads.** Spring's default scheduler has one thread, so a ten-game analysis run (about a minute or more) would delay the Lichess import.
- **The end-to-end test calls the job directly** with scheduling switched off, instead of waiting for the schedule. Reason: a deterministic test; the scheduling itself is a single annotation.

**Timing (one real game at depth 16):** the analysis service's runtime image (Stockfish 19, Threads=1, Hash 64 MB, depth 16), on Docker Desktop on an Apple M4 (arm64). The four finished fixture games, each analysed twice:

| Game | Half-moves | Run 1 | Run 2 | ms per half-move |
|---|---|---|---|---|
| `aDN8u0zd` (rapid) | 40 | 6.6 s | 6.6 s | 165 |
| `ZlkPN7gH` (rapid) | 56 | 6.2 s | 7.0 s | 110 to 125 |
| `k8OVq8Y5` (rapid) | 77 | 9.0 s | 9.0 s | 117 |
| `v31fmkvX` (blitz, longest available) | 226 | 26.7 s | 23.6 s | 105 to 118 |

Through the backend in the Compose stack, the same machine gave 6.9 s, 9.2 s and 6.2 s for the first three. Not measured on the CI runner (x86-64, slower per core); the end-to-end test's knight game is too short to tell.

**Verification:**
- `mvn verify` in `maven:3.9-eclipse-temurin-25` with the Docker socket, green on the first run. Surefire: 44 unit tests (10 new). Failsafe: 9 integration tests (`AnalysisEndToEndIT` 2, `SchemaIT` 6, `LichessImportIT` 1). Flyway applied V1 to V3.
- `AnalysisEndToEndIT`, new test: the knight fixture was imported as `knightE2` (White, LOSS, PENDING); one `runOnce()` analysed 1 game, DONE, attempt 1; 6 evaluations; one `HANGING_PIECE` finding on ply 5 with `hanging-0.2`, evidence starting `e5d4`; a second run analysed nothing.
- Manual, full Compose stack against live Lichess (`LICHESS_USERNAME=thibault`, import batch 3, then 6; for the test only: read timeout 20 s, `stuck-after` 30 s):
  - Three games were imported and analysed one after another, oldest first, all DONE.
  - Restarted with batch 6: three more were imported. I killed the backend (`docker kill`) while game 6 was RUNNING, and restarted it 32 s later.
  - At startup: "Game 6 was RUNNING since 16:46:26 (attempt 1); back to PENDING". The run then analysed games 6, 5 and 4 in order, all DONE; game 6 with 2 attempts.
  - Clarification (added in #23): startup recovery does **not** ignore the stuck timeout. It applies the same `stuck-after` rule as every run. The live test only worked quickly because `ANALYSIS_STUCK_AFTER` was shortened to 30 s and the restart came 32 s after the kill. With the default 30 min, a game left RUNNING by a crash is recovered by the first check after 30 min (at startup or on a run), not straight away at startup.
- CI on PR #28, first run green, **3m01s** in all (PR #27: 3m09s). Backend job 1m28s: 44 unit tests; `SchemaIT` 13.4 s, `AnalysisEndToEndIT` 54.0 s for both tests, including the analysis image build (was 49.4 s with one test), `LichessImportIT` 2.8 s. Analysis-service job 1m06s; Compose stack 1m28s.

**Open points / risks:**
- When the backend dies mid-call, the analysis service finishes that game anyway (nothing cancels it). After a fast restart, its engine can overlap for a few seconds with the next call, so the cap is "one per backend", not a hard limit in the service.
- One backend instance is assumed. Two instances could pick the same PENDING game: `startAnalysis` would fail for the second, which is logged and skipped. No locking (`SELECT ... FOR UPDATE SKIP LOCKED`) yet.
- The batch size bounds a run, not the backlog: with 1 worker and about 7 s a game, a run of 10 games takes about 70 s, plus the 30 s pause, which is roughly 360 rapid games an hour. That is plenty for one account's 20-game imports.
- Timing is from one Apple M4. A CI-runner measurement would firm up the 3.6× margin.

**Links:** [#22](https://github.com/Gergo019/blindspot-chess/issues/22), [PR #28](https://github.com/Gergo019/blindspot-chess/pull/28), [#2 devlog on unbounded engines](#2026-11-13-post-analyze-with-pinned-stockfish-2--pr-16)

## 2026-11-13: Lichess import of recent completed rapid games (#5 / PR #27)

**Goal:** Import a user's recent completed rapid games from Lichess in the background, bounded and deduplicated, and store them as PENDING. Analysing them is #22.

**Changes:**
- `backend/.../lichess/`: `LichessClient` and `LichessClientConfig` (RestClient: base URL, `User-Agent`, bearer token only when set, connect 5 s, read 60 s); `LichessGame` (the NDJSON fields used); `LichessGameMapper` (skip rules and mapping, no Spring); `LichessImportService` (`importRecentGames(accountId)`); `LichessImportJob` (`@Scheduled`, every Lichess account, one failure does not stop the others); `LichessAccountSetup` (creates the account named by `LICHESS_USERNAME`); `LichessProperties`; `LichessException`.
- `backend/.../common/SchedulingConfig.java`: `@EnableScheduling` and a `Clock` bean.
- `ChessAccountRepository.findByPlatform`.
- `application.yml`, `docker-compose.yml`, `.env.example`: `LICHESS_USERNAME`, `LICHESS_IMPORT_BATCH_SIZE` (20), `LICHESS_IMPORT_INTERVAL` (1h), `LICHESS_IMPORT_ENABLED`, `LICHESS_USER_AGENT`, `LICHESS_BASE_URL`.
- Tests: `LichessGameMapperTest` (8), `LichessClientTest` (4), `LichessImportServiceTest` (3), `LichessImportIT` (PostgreSQL plus a local HTTP server answering with the recorded export). The other integration tests switch the job off.
- `backend/src/test/resources/lichess/`: the recorded export and a README on how each line was recorded.
- `README.md`, `CHANGELOG.md`.

**Decisions:**
- **Endpoint and format from the current API spec,** not memory: `GET /api/games/user/{username}` with `Accept: application/x-ndjson` (lichess-org/api, `doc/specs/tags/games/api-games-user-username.yaml`, last changed 2026-05-18). Parameters: `max`, `perfType=rapid`, `finished=true`, `ongoing=false`, `pgnInJson=true`, `opening=true`, `clocks=false`, `evals=false`, `sort=dateDesc`. The stored PGN is the full `pgn` field with tags; the analysis service reads it as is.
- **An identifying `User-Agent` on every request.** The documented endpoint answered with Lichess's generic HTML 404 page until a `User-Agent` was sent; a Lichess maintainer gives that as the cause in lichess-org/api#667. Default `blindspot-chess (+https://github.com/Gergo019/blindspot-chess)`, configurable. The client turns an HTML answer into a `LichessException` rather than storing nothing silently.
- **Filters checked again in the backend.** Lichess filters by speed and finished state on its side, but product rule 1 must not depend on a query parameter: the mapper skips the statuses `created`, `started`, `aborted`, `noStart` and `unknownFinish` (no reliable result), any speed but rapid, and any variant but standard (the analysis service handles standard chess only). The status check comes first, so a game in progress is always reported as unfinished.
- **Each run asks for the newest `batchSize` games, with no `since`.** Deduplication by `(account, Lichess game id)` (existing unique constraint, checked before saving) makes re-runs import nothing twice. Rejected: `since=lastSyncedAt`. A game that started before the last sync but finished after it would be missed for good. Cost: each run re-reads up to 20 known games, about a second at the anonymous rate (20 games/s). The batch size is also enforced in the backend, in case the server sends more.
- **The HTTP call runs outside the transaction** that stores the games, as in `AnalysisService`. A Lichess failure stores nothing and leaves `lastSyncedAt` unchanged.
- **Result from `winner`:** no winner on a finished game is a draw; otherwise WIN or LOSS from the user's colour. The user is matched on the Lichess user id (the lower-cased name).
- **`LICHESS_USERNAME` creates a user and a Lichess account at startup.** Not asked for in #5, but without it a running stack has no account to import for until there is sign-up. Empty by default: no account, no requests to Lichess.
- **Local HTTP stub instead of WireMock** in the integration test: the JDK's `com.sun.net.httpserver` serves the recorded file, so no new dependency. The unit test binds `MockRestServiceServer` to the same builder configuration.
- **Test fixtures are real, unedited records** (2026-11-13): three finished rapid games of the public account `thibault`, one of his blitz games, and a rapid game in progress recorded from Lichess TV (another player's game). Lichess's filters would not send the last two; they are mixed in to prove the backend skips them.

**Verification:**
- `mvn verify` in `maven:3.9-eclipse-temurin-25` with the Docker socket: Surefire 34 unit tests (15 new), Failsafe 5 integration tests (`LichessImportIT` 1, `AnalysisEndToEndIT` 1, `SchemaIT` 3), all green.
- `LichessImportIT`: first run received 5, imported 3, skipped `NOT_FINISHED=1` (`IVGlNazQ`) and `NOT_RAPID=1` (`v31fmkvX`); the three stored games are PENDING with the right colours. Second run: 0 imported, `ALREADY_IMPORTED=3`. Both requests carried `max=10&perfType=rapid&finished=true&ongoing=false`, the `User-Agent` and `Accept: application/x-ndjson`.
- Manual smoke test against live Lichess (not part of CI): `docker compose up db backend` with `LICHESS_USERNAME=thibault` and batch size 5. The account was created at startup; 10 s later "5 received, 5 imported, skipped {}"; all five rows PENDING, with colour, result, `600+0` and the opening name.
- CI on PR #27, first run green, **3m09s** in all (#21's last run on `main`: 2m58s). Backend job 1m28s: 34 unit tests; `LichessImportIT` 2.4 s, `SchemaIT` 16.3 s, `AnalysisEndToEndIT` 49.4 s (includes the analysis image build). Analysis-service job 1m05s; Compose stack 1m35s (runs after both).

**Open points / risks:**
- No sign-up or account API yet; one account through `LICHESS_USERNAME`.
- Lichess's rate limit (HTTP 429) is logged as a failure and the account is retried on the next run; there is no back-off beyond the interval.
- Correspondence and classical games are never imported (rapid only, as specified).
- The importer does not check the PGN itself; the analysis service rejects an illegal or unfinished PGN with 422, and the game is then marked FAILED (#22).

**Links:** [#5](https://github.com/Gergo019/blindspot-chess/issues/5), [PR #27](https://github.com/Gergo019/blindspot-chess/pull/27), [Lichess API: export games of a user](https://lichess.org/api#tag/games/GET/api/games/user/{username}), [lichess-org/api#667](https://github.com/lichess-org/api/issues/667)

## 2026-11-13: End-to-end analysis, backend to analysis service (#4 / PR #21)

**Goal:** The backend analyses a stored game through the analysis service and stores the result; a known game is tested end to end (the Phase 1 exit criterion).

**Changes:**
- `backend/.../analysis/AnalyzeApi.java`: records mirroring the `/analyze` JSON (request, response, engine, moves, scores, findings, facts).
- `backend/.../analysis/AnalysisClient.java`, `AnalysisClientConfig.java`, `AnalysisFailedException.java`: RestClient against `blindspot.analysis-service-url`; connect timeout 2 s, read timeout `blindspot.analysis-service-read-timeout` (env `ANALYSIS_SERVICE_READ_TIMEOUT`, default 180 s); any error status, connection failure or timeout becomes `AnalysisFailedException` with the status and the service's message.
- `backend/.../analysis/AnalysisService.java`: `analyzeGame(gameId)` in three steps: short transaction (load, `startAnalysis()`, save); the HTTP call outside any transaction; short transaction (one `MoveEvaluation` per ply with `EngineRun("Stockfish 19", 16)`, one `MistakeFinding` per finding with its facts as JSON, `completeAnalysis()`). Any failure in the call or the store marks the game FAILED in a third transaction and logs the reason.
- `backend/src/main/resources/db/migration/V2__mistake_finding_facts.sql`: `facts JSONB NOT NULL DEFAULT '{}'` on `mistake_findings`; `MistakeFinding.facts` mapped like `Explanation.facts`. V1 untouched.
- `backend/pom.xml`: `spring-boot-starter-restclient`; `maven-failsafe-plugin` for `*IT`.
- Tests: `AnalysisClientTest` (4) and `AnalysisServiceTest` (3) with `MockRestServiceServer`, no Docker; `AnalysisEndToEndIT` (Testcontainers: PostgreSQL and the analysis service built from `../analysis-service/Dockerfile`, runtime target); `SchemaIntegrationTest` renamed to `SchemaIT`.
- `application.yml`, `docker-compose.yml`, `.env.example`: the read-timeout setting. `README.md`, `CHANGELOG.md`.

**Decisions:**
- **HTTP call outside transactions,** between two short ones. Reason: one call can take minutes; holding a database transaction (and a pooled connection) that long is wasteful and risks timeouts. Rejected: `@Transactional` on the whole method.
- **FAILED on any error, in its own transaction,** including errors while storing (for example a classification the backend does not know). Reason: never leave a game RUNNING. The game can be retried from FAILED.
- **A finding for a move that is not the user's MISTAKE or BLUNDER is skipped with a warning,** not stored. The analysis service never sends one; `MistakeFinding` would reject it anyway.
- **Engine version stored as name and version together** (`"Stockfish 19"`), matching the column's example in V1.
- **Facts stored as the JSON the detector reported,** serialised with Spring Boot's Jackson 3 `JsonMapper`, so new fact fields need no migration.
- **`spring-boot-starter-restclient` added.** Reason: in Spring Boot 4 the auto-configured `RestClient.Builder` is its own module; without it the context does not start. Rejected: `RestClient.builder()` by hand (would bypass Boot's JSON configuration).
- **JDK `HttpClient` request factory** for the timeouts (`JdkClientHttpRequestFactory`). Reason: plain Spring Framework API, no extra HTTP library.
- **Evidence-line legality through a second call to the analysis service.** Reason: no maintained Java chess library is published on Maven Central (`chesslib` is on JitPack only; the libraries on Central are small or unmaintained). The test sends the game up to ply 5 plus the evidence line, in UCI, as a PGN; the service rejects any illegal move with 422, and the test asserts that every move comes back in order. The PGN gets a draw result only because the service requires a finished result; it is not a real game.
- **`TARGETARCH` passed as a build argument** when Testcontainers builds the analysis image, from the Docker host's architecture. Reason: only BuildKit sets `TARGETARCH` automatically, and a build through the Docker API (as Testcontainers does) may use the classic builder; without the argument the Dockerfile falls back to the x86-64 Stockfish, which does not run natively on Apple Silicon. Passing it works with either builder. (Not confirmed which builder Testcontainers used locally: it does not log build steps.)

**Verification:**
- Locally with Maven in `maven:3.9-eclipse-temurin-25` (no JDK on the host) and the Docker socket for Testcontainers: `mvn verify` green. Surefire: 19 unit tests (including the 7 new ones). Failsafe: `AnalysisEndToEndIT` 1 and `SchemaIT` 3.
- The end-to-end test stored the knight game (`1. e4 e5 2. Nf3 d6 3. Nd4 exd4 0-1`, user White), ran `analyzeGame`, and found: status DONE; 6 move evaluations, all `Stockfish 19` at depth 16; one `HANGING_PIECE` finding on ply 5 with `hanging-0.2`, `movedPiece` knight, evidence starting `e5d4`; the evidence line accepted move by move by the analysis service.
- Flyway applied V1 and V2 in both integration contexts.
- The first local run failed to start the context (no `RestClient.Builder` bean); fixed by adding the starter. Before that, a field name clash in the test (`analysisService` for both the container and the service) failed compilation.
- CI on PR #21, first run green. Backend job **1m33s** (was 27 to 47 s before this PR): 19 unit tests; `AnalysisEndToEndIT` 46.1 s including the analysis image build on the runner; `SchemaIT` 14.0 s. Analysis-service job 1m09s, Compose stack 1m06s (runs after both). Whole run 2m44s (was 2m11s to 2m28s on recent `main` runs).

**Open points / risks:**
- The backend CI job now builds the analysis image (Stockfish download and pip install) inside `mvn verify`, so it is slower; the Compose job builds the same image again. Caching or sharing the image between jobs would save time.
- Nothing calls `analyzeGame` yet; the background job that picks PENDING games comes with the Lichess import (#5).
- Two games analysed at the same time would each start an engine in the analysis service; no concurrency limit yet (noted in #2).
- `MistakeFinding` still has a constructor without facts (`"{}"`) for existing test data.

**Links:** [#4](https://github.com/Gergo019/blindspot-chess/issues/4), [PR #21](https://github.com/Gergo019/blindspot-chess/pull/21), [ADR 0003](decisions/0003-facts-first-explanations.md), [ADR 0004](decisions/0004-hanging-piece-detector.md)

## 2026-11-13: Hanging-piece detector v0.2 (#3 / PR #20)

**Goal:** Address the open points of v0.1 before merging: mates beyond the window, exchanges cut off by the window, and facts that name the moved piece.

**Changes:**
- `analysis-service/app/detectors/hanging_piece.py`: `DETECTOR_VERSION = "hanging-0.2"`. New input `score_after` (engine score after M, White's view) and `is_mate_against()`; window of 4 plies extended one ply at a time while the last ply was a capture, up to `MAX_WINDOW_PLIES = 8`; finding gains `moved_piece`, `moved_from`, `moved_to`.
- `analysis-service/app/engine.py`: `PV_PLIES = 8` (was 4); passes the score after M to the detector. Search settings unchanged.
- `analysis-service/app/models.py`: `facts` gains `movedPiece`, `movedFrom`, `movedTo`.
- Tests: new pure tests for the mate score (mate on ply 5, a Black user, `is_mate_against` for both colours and non-mate scores), the extended window (an exchange that is level after 4 plies but loses 3 after the captures end; the same exchange with only 4 plies available; the cap, checked by lowering it to 5) and the moved-piece facts (discovered loss: `movedPiece` knight, `piece` queen). Version and fact-set assertions updated to `hanging-0.2`; the engine test's evidence-length bound follows the new window (at most 8).
- Docs: [ADR 0004](decisions/0004-hanging-piece-detector.md) (rules, version history, "Open questions"), `analysis-service/README.md` example, `CHANGELOG.md`.

**Decisions:**
- **Rule 3 unchanged** (net loss of at least 2). Whether to measure net loss or the captured piece's value is open question (a) in ADR 0004, to be reviewed against real-game findings in Phase 2. Whether hung pieces in already-decided positions should count toward the pattern report is open question (b).
- **Mate beyond the window:** a mate score against the user after M blocks the finding, however many moves away. Reason: v0.1 reported a capture followed by a mate on ply 5 as a hanging piece. `mate == 0` after M can only mean the user mated, so it never blocks. Rejected: a longer board-only window (still misses longer mates and costs nothing less).
- **Window extension while captures continue, cap 8.** Reason: an exchange cut at ply 4 can look level although it loses material on ply 5. Rejected: a fixed longer window (would include quiet moves that do not change material and make evidence lines longer than needed).
- **Moved piece in the facts.** Reason: in a discovered loss the piece that moved is not the piece that is lost; typed claims (ADR 0003) need both.

**Verification:**
- 94 tests pass in the Docker `test` image on arm64 and on amd64, including both determinism tests (moves and findings).
- Hand-made positions were checked with python-chess before use. The exchange position first had White's bishop already giving check to the black king on g8 (invalid FEN); the king was moved to h8.
- Real games, identical on amd64 and arm64:

  | Game | v0.1 | v0.2 |
  |---|---|---|
  | `1. e4 e5 2. Nf3 d6 3. Nd4 exd4`, ply 5 (Nd4, BLUNDER, score after -720 cp) | evidence `e5d4 c2c3 d6d5 c3d4`, materialLost 2 | evidence `e5d4 c2c3 d6d5 c3d4 d5e4 d1b3`, **materialLost 3**; movedPiece knight f3→d4 |
  | `1. e4 d5 2. Qg4 Bxg4`, ply 3 (Qg4, BLUNDER, score after -955 cp) | evidence `c8g4 e4e5 b8c6 f1b5`, materialLost 9 | unchanged; movedPiece queen d1→g4 |

  The knight finding changed because ply 4 (`c3xd4`) was a capture, so the window extended: `d5xe4` on ply 5 is another capture, `Qb3` on ply 6 ends it. With the pawn trade finished, the loss is the whole knight. The queen line's ply 4 is quiet, so its window stays at 4. Neither game has a mate score, so the new mate rule did not affect them.
- CI on PR #20 after the v0.2 commits, first run green: 94 passed in the image on the runner (amd64), backend and Compose stack green.

**Open points / risks:**
- Open questions (a) and (b) in ADR 0004.
- The 8-ply cap is not reached by any test line; it is checked by lowering the cap in a test.
- Findings stored with `hanging-0.1` (none yet; nothing is persisted before #4) would need recomputing.

**Links:** [#3](https://github.com/Gergo019/blindspot-chess/issues/3), [PR #20](https://github.com/Gergo019/blindspot-chess/pull/20), [ADR 0004](decisions/0004-hanging-piece-detector.md)

## 2026-11-13: Hanging-piece detector v0.1 (#3 / PR #20)

**Goal:** Detect when a user's move leaves one of their pieces to be won, with an evidence line the player can replay.

**Changes:**
- `analysis-service/app/detectors/hanging_piece.py` (new): the pure detector, `DETECTOR_VERSION = "hanging-0.1"`.
- `analysis-service/app/engine.py`: each analysed position keeps the first 4 plies of the principal variation (`PV_PLIES`); `analyze_game` returns moves and findings and runs the detector on the user's moves only. Search settings unchanged.
- `analysis-service/app/models.py`, `app/main.py`: `POST /analyze` returns `findings` (`ply`, `motif`, `evidenceLineUci`, `detectorVersion`, `facts` with `piece`, `square`, `capturedBy`, `materialLost`); `moves` unchanged.
- Tests: `tests/test_hanging_piece.py` (20 pure tests, hand-written positions and lines), `tests/test_findings_engine.py` (5 engine tests), one API contract test; `tests/conftest.py` gains an `analyze_full` fixture, the existing `analyze` fixture behaves as before.
- Docs: [ADR 0004](decisions/0004-hanging-piece-detector.md), `analysis-service/README.md`, `CHANGELOG.md`.

**Decisions:**
- **Definition** as in ADR 0004: user move classified MISTAKE or BLUNDER; the engine's PV after it starts by capturing a user knight, bishop, rook or queen; net loss of at least 2 points over up to 4 PV plies; no checkmate within those plies. Reason: rules 1 and 2 let the engine judge whether the loss is real (sound sacrifices and refuted captures drop out), rule 3 drops even trades. Rejected: a static "attacked and undefended" check (misses discovered attacks, fires on sacrifices and on pieces that cannot safely be taken).
- **Pure detector, no engine calls.** Reason: every rule is unit-testable with hand-written lines. Rejected: letting the detector query the engine.
- **The material window ends after the user's second reply** (plies: capture, reply, opponent, reply), so the first recapture is always counted.
- **Mate is checked on the board inside the window only** (a checkmate after any window ply). The detector does not look at the engine's mate score, because its inputs are the board, the move, the classification and the PV.
- **Facts name the captured piece**, which in a discovered loss is not the piece that moved.
- **Illegal input raises** (`ValueError`) instead of returning no finding: engine lines are always legal, so an illegal line is a bug to surface.

**Verification:**
- Pure tests: fire for a knight moved onto a pawn-attacked square (3 points), a rook defended once but attacked by a bishop (exchange, 2), a discovered loss (queen for rook, 4) and a Black user; do not fire for an even trade (BxN, bxc6), a BEST sacrifice, GOOD or INACCURACY moves, a hanging pawn, a positional mistake (1. f3), a loose-looking piece the engine does not take, a capture that mates at once (back rank), a capture followed by mate on ply 3, a capture of the opponent's own piece, and an empty PV.
- While writing the mate test, the first hand-made position was wrong (after Qa5 the queen still covered e1, so Rxe1 was not mate); the position was checked with python-chess and replaced with Qa2. The detector was right.
- In the Docker `test` image: 81 passed on arm64 and on amd64, including the existing determinism test.
- Real findings, identical on amd64 and arm64: `1. e4 e5 2. Nf3 d6 3. Nd4 exd4` gives ply 5 (Nd4, BLUNDER), evidence `e5d4 c2c3 d6d5 c3d4`, knight on d4 taken by a pawn, materialLost 2 (White wins a pawn back in the line). `1. e4 d5 2. Qg4 Bxg4` gives ply 3 (Qg4, BLUNDER), evidence `c8g4 e4e5 b8c6 f1b5`, queen on g4 taken by a bishop, materialLost 9.
- CI on PR #20, first run green: 81 passed in the image on the runner (amd64), backend 15 passed, Compose stack healthy.

**Open points / risks:**
- **Net loss counts material won back in the window.** The hung knight above scores exactly 2 because the engine line regains a pawn; a knight lost for two pawns would not fire. Revisit the threshold (or measure the piece's value instead of the net) with real games.
- **The mate rule only sees the 4-ply window.** A capture followed by a mate on ply 5 or later is reported as a hanging piece. Using the engine's score after M (a mate against the user) would catch it, but that is an input the detector does not take yet.
- **Exchange sequences longer than 4 plies** are cut off; the net loss is whatever stands after ply 4.
- **Hopeless positions:** a piece hung when the game is already lost (or still won) is usually classified GOOD, so no finding. Intended by the classification design, but a coach might still want to mention it.
- **Promotions in the window** count against the user like any other material change.

**Links:** [#3](https://github.com/Gergo019/blindspot-chess/issues/3), [PR #20](https://github.com/Gergo019/blindspot-chess/pull/20), [ADR 0004](decisions/0004-hanging-piece-detector.md), [ADR 0003](decisions/0003-facts-first-explanations.md)

## 2026-11-13: Post-merge cleanup (PR #17)

**Goal:** Repair what the dependency merges and the PR #16 conflict resolution left behind, and put the backend on one Java LTS.

**What went wrong:**
- Dependabot opened ten separate PRs (#6 to #15), one per dependency, and they were merged before PR #16. Two of them touched files that #16 also changed (`.github/workflows/ci.yml` and `analysis-service/Dockerfile`).
- Resolving the resulting conflict kept both sides. The analysis-service CI job ran the new Docker-based tests and then the old host steps as well (a second checkout, `setup-python`, `apt-get install stockfish`, `pip install ... httpx`, a health check on the host), so CI also exercised an unpinned Stockfish from Ubuntu. The Dockerfile kept a stray first line `FROM python:3.15-slim`: an unused build stage, harmless at build time but misleading.
- The Java images drifted to non-LTS versions on both sides: `maven:3.9-eclipse-temurin-26` to build and `eclipse-temurin:24-jre` to run, while `pom.xml` and CI compiled for Java 21. CI stayed green, which hid the mismatch.

**Changes:**
- `.github/workflows/ci.yml`: the analysis-service job is only `actions/checkout@v7`, build `--target test`, run. Job name unchanged (required check). Backend job on `java-version: "25"`.
- `analysis-service/Dockerfile`: stray `FROM python:3.15-slim` removed; runtime stays on `python:3.13-slim`.
- Java 25 LTS everywhere: `<java.version>25</java.version>` in `backend/pom.xml`, `maven:3.9-eclipse-temurin-25` (build) and `eclipse-temurin:25-jre` (runtime) in `backend/Dockerfile`, `README.md`, [ADR 0001](decisions/0001-monorepo-and-service-split.md). The 2026-11-12 entry below keeps "Java 21" as history, annotated.
- `.github/dependabot.yml`: one grouped PR per ecosystem (`groups: all: patterns: ["*"]`); for docker, major updates of `eclipse-temurin` and `maven` and minor and major updates of `python` are ignored.
- `CHANGELOG.md` under Unreleased.

**Decisions:**
- **Java 25 LTS** for compile, test, build image and runtime image. Reason: one LTS version end to end; 24 and 26 are non-LTS releases with short support. Rejected: staying on Java 21 (older LTS, and the merged images had already moved past it) and following Dependabot to each new feature release.
- **Java and Python upgrades by hand.** Reason: a base-image upgrade changes the platform the tests ran on and deserves its own PR and devlog entry. Rejected: letting Dependabot propose them (that is how 24, 26 and 3.15 arrived).
- **Grouped Dependabot PRs**, one per ecosystem. Reason: fewer PRs, merged as one unit, and fewer chances for overlapping changes to conflict with feature branches. Rejected: ungrouped PRs.
- **Python stays on 3.13**, the version all analysis-service tests ran on.

**Verification:**
- Locally: the backend image builds with `maven:3.9-eclipse-temurin-25`; it runs on Temurin 25.0.5 LTS; the compiled classes are class file version 69 (Java 25).
- CI on PR #17, first run green. The backend job ran on Temurin 25.0.5: 15 tests, 0 failures, 0 skipped, the same test classes and timings as the last `main` run on Java 21 (`SchemaIntegrationTest` 14.69 s vs 14.94 s). The analysis-service job ran only checkout, image build and pytest in the image. The Compose stack reported Stockfish 19, depth 16, and database UP.
- One difference on Java 25, a warning only: JNA (`jna-5.18.1`, used by Testcontainers to talk to Docker) calls a restricted method (`System::load`) and the JVM suggests `--enable-native-access=ALL-UNNAMED`. The existing warnings (Mockito's dynamic agent, the intended duplicate-key violation in a constraint test) are unchanged.

**Open points / risks:**
- Dependabot reads a Docker tag's leading number as its version. For `maven:3.9-eclipse-temurin-25` that is the Maven version (3.9), not the JDK, so ignoring major updates of `maven` may not stop a JDK change in the tag suffix (the earlier `-21` to `-26` bump changed only the suffix). Watch the next docker PR; if it proposes a new JDK suffix, add an explicit `versions` ignore.
- Review Dependabot PRs before merging when a feature branch touches the same files; rebase the feature branch instead of merging both sides.
- Java 25 warns that restricted native calls (JNA in the tests) will be blocked in a future release. Not urgent; when it matters, pass `--enable-native-access=ALL-UNNAMED` to the test JVM (Surefire `argLine`) or follow a Testcontainers/JNA update.

**Links:** [PR #17](https://github.com/Gergo019/blindspot-chess/pull/17), [PR #16](https://github.com/Gergo019/blindspot-chess/pull/16), Dependabot PRs [#6](https://github.com/Gergo019/blindspot-chess/pull/6) to [#15](https://github.com/Gergo019/blindspot-chess/pull/15)

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
- **Worked example, 1. f3** (Stockfish 19, depth 16, Threads 1, Hash 64 MB; identical on amd64 and arm64):

  | | Score (White's view) | Winning chances (White's view) |
  |---|---|---|
  | Before 1. f3 (best move e2e4) | +39 cp | +0.071677 |
  | After 1. f3 | -71 cp | -0.129974 |

  Drop for the mover (White): 0.071677 - (-0.129974) = **0.201652**, at least 0.2 and below 0.3, so **MISTAKE**. Computed the way lila's `CpAdvice` does it ([Advice.scala L51-58](https://github.com/lichess-org/lila/blob/3e79a4579cb02b83d6fc7fe2e855cf8ff5949e20/modules/tree/src/main/Advice.scala#L51-L58)): the previous position's eval against this position's eval, both from White's view, with `delta = current - prev` negated when the mover is White (`info.color.fold(-d, d)`). lila's `Info` is the position after the move and `color = prevPly.turn` is the mover ([Info.scala L7-14](https://github.com/lichess-org/lila/blob/3e79a4579cb02b83d6fc7fe2e855cf8ff5949e20/modules/tree/src/main/Info.scala#L7-L14)). An independent re-implementation of that expression gave the same delta and the same judgement. Sensitivity: with -70 cp after the move the drop would be 0.199841 (INACCURACY).
- Runtime image smoke test: `/health` reports Stockfish 19, depth 16; `POST /analyze` returns the contract; an unfinished game returns 422.
- CI on PR #16, first run green: the runner's image build verified the x86-64 checksum (`/tmp/stockfish.tar.gz: OK`); `Analysis service (install + smoke test)` ran 55 passed in the image; `Backend (build + tests)` passed; `Docker Compose stack` started and `/health` reported Stockfish 19, depth 16.

**Open points / risks:**
- Not yet measured: time per position at depth 16, single-threaded. The whole test suite, including nine engine analyses of short games, runs in about 12 s in the image; a long game will take much longer. Fine for a background job; the backend client needs a generous timeout.
- Concurrent requests each start an engine process; there is no limit on concurrency yet. Worth a cap when the backend job runs in parallel.
- The mate rule differs slightly from Lichess's `MateAdvice` (see Decisions).
- 1. f3 is classified MISTAKE, but only just (worked example below): one centipawn less loss (-70 instead of -71) would make it an INACCURACY. Moves near a threshold can change class with a different engine version, depth or hash size. Thresholds stay as they are; worth reviewing with real games. Lichess's own analysis may label the same move differently because its server analysis uses its own engine settings.
- Upgrading Stockfish changes evaluations; stored rows keep their engine version.

**Links:** [#2](https://github.com/Gergo019/blindspot-chess/issues/2), [PR #16](https://github.com/Gergo019/blindspot-chess/pull/16), [ADR 0002](decisions/0002-python-analysis-service.md)

## 2026-11-12: Project foundation (PR #1)

**Goal:** Lay the foundations for a chess coach that finds one recurring mistake in a player's own games, drills it, and tests whether the lesson transfers.

**Changes:**
- Pivot from the earlier idea, an achievement and trophy tracker, to a chess coach. The project was renamed from OneMore% to BlindSpot; the GitHub repository `onemore-percent` was renamed to `blindspot-chess`.
- Monorepo: `backend/` (Java 21 at the time, Java 25 since the post-merge cleanup; Spring Boot 4.1), `analysis-service/` (Python, FastAPI, python-chess, Stockfish), `frontend/` (placeholder for Phase 3), `docker-compose.yml` with PostgreSQL 17.
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
