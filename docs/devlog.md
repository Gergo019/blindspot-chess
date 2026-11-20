# Engineering log

A chronological record of what was done, why, and what to change if something breaks. Newest entry at the top. Every pull request adds or updates an entry.

Entry format: Goal, Changes, Decisions (with the reason and the alternative rejected), Verification, Open points / risks, Links.

## 2026-11-20: Hold TypeScript 7 updates, GPL-3.0-or-later for the analysis service (PR #59)

**Goal:** Two follow-ups from #50 before #51: unblock Dependabot's grouped npm PR (#58), and settle the licence question raised there (python-chess is GPL and imported by the analysis service).

**Changes:**
- `.github/dependabot.yml` (npm, `/frontend`): ignore `typescript` major updates, with the reason and "remove when typescript-eslint supports TypeScript 7".
- `analysis-service/LICENSE`: the GPL version 3 text from gnu.org (SHA-256 `3972dc97…`). It is identical, apart from whitespace, to the `COPYING.txt` that ships with Stockfish in the image.
- Licence notes: a "Licence" section in `analysis-service/README.md`, the root README's licence section split by directory, `THIRD_PARTY_NOTICES.md` (intro and the python-chess row), and an **amendment section in ADR 0002**. No other ADR was edited.
- Docs fix on the side: in `analysis-service/README.md` the "needs a Stockfish binary, set `STOCKFISH_PATH`" paragraph had ended up after "Golden files" (#33). It is back under "Run locally without Docker", where it belongs.
- CHANGELOG.
- Deleted the remote branch `feat/50-frontend-scaffold`, which a push after #54's merge had re-created (fully merged, approved).

**Decisions:**
- **Why #58 failed:** Dependabot grouped `typescript` 6.0.3 → 7.0.2 with `typescript-eslint` 8.72.1 → 8.73.0. typescript-eslint 8.73.0 still declares `typescript: ">=4.8.4 <6.1.0"` (npm registry), so `npm ci` stops with ERESOLVE ("While resolving: typescript-eslint@8.73.0, Found: typescript@7.0.2"). Ignoring only TypeScript's *major* updates keeps 6.0.x patches and every other update flowing. The alternative, `--legacy-peer-deps`, was rejected: it would hide the incompatibility and break type-aware linting.
- **The analysis service is GPL-3.0-or-later; everything else stays MIT.** python-chess 1.11.2 declares `License: GPL-3.0+` (classifier "GPLv3 or later") and is imported into the service's code, so the service is a work based on it. The backend and the frontend only call it over HTTP and contain none of its code, so they stay MIT, the same separation that already covers Stockfish. Details are in the ADR 0002 amendment.
- **"or later":** the request said "GPL-3.0". The SPDX identifier `GPL-3.0` is deprecated because it leaves the version question open. I chose `GPL-3.0-or-later` to match python-chess's own terms. `GPL-3.0-only` would also be compatible; switching is a one-line change in the README and notices (the LICENSE text is the same).
- **Rewriting on an MIT chess library was rejected:** python-chess's PGN parsing, move generation and UCI handling are what the service is built on.

**Verification:**
- #58's failing job log: `npm error ERESOLVE could not resolve … While resolving: typescript-eslint@8.73.0 … Found: typescript@7.0.2`.
- `dependabot.yml` parses; the npm entry has the new ignore rule.
- GPL text: `diff -w` against Stockfish's `COPYING.txt` from the analysis-service image shows no difference.
- CI on PR #59: CI_RESULT
- After merge: CI_DEPENDABOT

**Open points / risks:**
- When typescript-eslint supports TypeScript 7, remove the ignore rule and upgrade TypeScript (and check the `tsconfig` options).
- ADR 0007's "the project's own code stays MIT" now reads as "the frontend stays MIT"; per the instruction, ADR 0007 was not edited, and the ADR 0002 amendment is the record.

**Links:** [#58](https://github.com/Gergo019/blindspot-chess/pull/58), [PR #59](https://github.com/Gergo019/blindspot-chess/pull/59), [ADR 0002](decisions/0002-python-analysis-service.md#amendment-2026-11-20-licence-of-the-analysis-service), [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)

## 2026-11-20: Frontend scaffold, theme and app shell (#50 / PR #54)

**Goal:** The foundation of the web app (ADR 0007): a strict React + TypeScript build with lint, tests and CI, the dark and light theme with tested contrast, the app shell with a working board, and an nginx container in the Compose stack. No API calls and no findings yet (#51, #24).

**Changes:**
- `frontend/`: Vite 8, React 19.3, TypeScript 6.0 (strict, plus `noUncheckedIndexedAccess` and `exactOptionalPropertyTypes`), ESLint 10 with typescript-eslint's strict type-checked rules and the React hooks rules, Prettier, Vitest 5 + Testing Library + jsdom. Exact versions in `package.json`, `package-lock.json` committed. Node pinned to **26.13.1** in `.nvmrc` and `"engines": ">=26.13.1 <27"`.
- **Theme:** every colour is a CSS custom property in `src/theme/theme.css`, one block per theme, switched by `data-theme` on `<html>`. Dark is the default; the choice is stored in `localStorage` (`blindspot.theme`). An inline script in `index.html` applies it before the first paint. Every storage access is in try/catch, including touching `localStorage` itself, which throws in some browsers with site data blocked. Inter is self-hosted from `@fontsource-variable/inter`, so there are no requests to font CDNs.
- **Contrast test** (`src/theme/contrast.test.ts`, 40 checks): it parses `theme.css` and checks text, muted and disabled text on all three backgrounds (4.5:1), coordinates on both squares (4.5:1), the focus ring on all backgrounds (3:1), and the highlight halo on both squares plus the meaning colours on the halo (3:1), in both themes. It also checks that both themes define the same tokens and that the agreed meaning colours are unchanged.
- **Highlight style** (`src/theme/highlight.ts`): an orange or blue ring between two dark halo rings, never a fill. A test pins that shape; ADR 0007 now records why.
- **App shell:** a header with the name and mark, and navigation Findings / Games / Drills, with Drills disabled. The board column (max 760 px, about two thirds) and the side panel stack below 900 px. The theme toggle has an accessible name ("Switch to light theme"). react-chessboard 5.12.1 shows the starting position, read-only, with coordinates inside the edge squares and square colours taken from the tokens. The legend ("Your move", "Evidence line") and the keyboard hint sit under the board. The panel shows an empty state ("No findings yet" / "No games yet") instead of fake data.
- **Serving:** `frontend/Dockerfile` has a Node 26 build stage and an nginx-unprivileged 1.30 runtime (uid 101, port 8080), both pinned by digest. `nginx/default.conf.template` provides `/healthz`, a single-page-app fallback, long caching for hashed assets, `no-cache` for `index.html`, and `nosniff`. `/api/` is proxied to `${BACKEND_ORIGIN}`, resolved per request through Docker's DNS, so nginx starts without the backend.
- `docker-compose.yml`: a `frontend` service on `${FRONTEND_PORT:-8081}` with a healthcheck (busybox `wget` on `/healthz`). `FRONTEND_PORT` added to `.env.example`.
- **CI:** a new job **"Frontend (lint + test + build)"** (setup-node from `.nvmrc`, `npm ci`, lint, Prettier check, type check, tests, build). The Compose job now also waits for the frontend's `/healthz`, checks that `/` serves the app, checks that `/api/proxy-check` through nginx gives the backend's own answer, and checks that Compose reports the frontend healthy.
- **Dependabot:** `npm` in `/frontend` (weekly, grouped like the others), `/frontend` added to the docker entry, and Node major updates ignored (LTS upgrades are done by hand, like Java and Python).
- `THIRD_PARTY_NOTICES.md`: react-chessboard, the Cburnett pieces (BSD option, author credited), Inter, React, dnd-kit, Stockfish, python-chess, Lichess open database. Copyright lines were copied from the packages' licence files.
- README (web app URL, screenshot, licence pointer), `frontend/README.md`, ADR 0007 (contrast results), CHANGELOG.

**Decisions:**
- **Node 26, not 24.** The official release schedule (`nodejs/Release`, `schedule.json`) puts Node 26 "Lithium" in Active LTS since 2026-10-28; Node 24 entered maintenance on 2026-10-20. **26.13.1 (2026-11-10) is newer than the two-week age the project otherwise waits for**, but it is a security release (`"security": true` in nodejs.org's `index.json`); the previous one, 26.13.0, is not patched. All npm packages are the newest versions released before 2026-11-06.
- **TypeScript 6.0.3, not 7.0.** typescript-eslint 8.72 supports TypeScript `>=4.8.4 <6.1.0`, so TypeScript 7 would break type-aware linting. Revisit when typescript-eslint supports 7.
- **Highlights as rings, not the colours changed.** As a plain fill, orange and blue reach only 1.02 to 1.87:1 on the squares (blue on the light theme's light square: 3.41). Changing the meaning colours would break ADR 0007; a dark halo ring around the colour ring carries the contrast instead (4.20 to 14.34:1). The full table is below.
- **Disabled text adjusted, not exempted.** WCAG exempts disabled controls, but the disabled "Drills" link should still be readable. The first `--text-disabled` reached only 4.13 to 4.42:1 on some backgrounds; it was nudged to `#848ea0` (dark) and `#646d79` (light), and the test treats it like other text.
- **Port 8081, not 3000.** 3000 is a common default (Grafana uses it, and on the development Mac it was taken). 8081 sits next to the backend's 8080; 5173 stays free for `npm run dev`.
- **The proxy check compares with the backend, not with a fixed body.** The same request straight to the backend must give the same status, and the body through nginx must be Spring's JSON naming the path. The first attempt compared whole bodies, which never match because of the timestamp. Before that, a 404 with `{"message":"Not found"}` turned out to come from Grafana on port 3000, which is what showed the port clash.
- **The backend's name is resolved per request** (`resolver 127.0.0.11`, `resolver_timeout 2s`), because a fixed `proxy_pass http://backend:8080` makes nginx refuse to start whenever the backend is not resolvable. Outside Compose, `/api` answers 502 after 2 s instead.
- **Navigation without a router yet.** Findings and Games switch the panel's empty state; routes come with real screens (#24).

**Contrast results** (WCAG ratio; needed: 4.5 for text and coordinates, 3 for graphics):

| Pair | Dark | Light |
|---|---|---|
| text on bg / surface / raised | 15.17 / 14.05 / 12.52 | 15.13 / 16.22 / 14.31 |
| muted text on bg / surface / raised | 7.32 / 6.77 / 6.04 | 5.95 / 6.38 / 5.63 |
| disabled text on bg / surface / raised | 5.59 / 5.18 / 4.61 | 4.89 / 5.24 / 4.62 |
| coordinates on light / dark square | 9.52 / 4.72 | 14.34 / 7.50 |
| focus ring on bg / surface / raised | 7.31 / 6.77 / 6.03 | 4.32 / 4.63 / 4.09 |
| halo on light / dark square | 9.52 / 4.72 | 14.34 / 7.50 |
| orange / blue on halo | 7.69 / 7.70 | 7.69 / 4.20 |
| orange as fill on light / dark square (not used) | 1.24 / 1.63 | 1.87 / 1.02 |
| blue as fill on light / dark square (not used) | 1.24 / 1.63 | 3.41 / 1.79 |

**Verification:**
- In the Node 26.13.1 container: `npm run lint`, `format:check` and `typecheck` are clean; `npm test` passes 4 files and 56 tests (40 contrast checks, theme storage including throwing and missing storage, the highlight shape, and the shell); `npm run build` gives 297 kB of JS (91 kB gzipped) plus the Inter subsets.
- Image: `/healthz` answers `ok`; `/` serves the app; `/games` falls back to `index.html`; `Cache-Control: no-cache` and `nosniff` are set; it runs as `uid=101(nginx)`; without a backend, `/api` answers 502.
- Full stack locally: frontend healthy; `/api/proxy-check` through nginx and straight to the backend both give Spring's 404 JSON with `"path":"/api/proxy-check"`.
- Screenshots with headless Chrome against the stack (dark, light, and 420 px wide): the stored theme is applied, 32 pieces are on the board, and the font in use is Inter Variable. Dark and light are in `docs/images/`.
- CI on PR #54, green on the first run: **Frontend (lint + test + build)** 21 s, Backend 1m31s, Analysis service 1m32s, Docker Compose stack 1m29s. In the Compose job the frontend's `/healthz` answered `ok`, and `/api/proxy-check` through nginx returned the backend's own 404 JSON (`"path":"/api/proxy-check"`), with the same status as asking the backend directly.

**Open points / risks:**
- **python-chess is GPL-3.0 and is imported by the analysis service's code**, unlike Stockfish, which runs as a separate program. ADR 0002 treats the analysis service as part of an MIT project. Whether that service must itself be GPL (it is a separate program from the backend and the frontend) needs a decision; it is listed accurately in `THIRD_PARTY_NOTICES.md` meanwhile.
- No Content-Security-Policy yet. The inline theme script would need a hash; add it with the findings screen.
- The halo is dark in both themes, which reads well on the board but looks heavy on the light panel; revisit when real highlights appear in #24.
- The required status check "Frontend (lint + test + build)" has to be added to branch protection by hand.

**Links:** [#50](https://github.com/Gergo019/blindspot-chess/issues/50), [PR #54](https://github.com/Gergo019/blindspot-chess/pull/54), [ADR 0007](decisions/0007-frontend-board-and-visual-identity.md), [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md), [Node release schedule](https://github.com/nodejs/Release)

## 2026-11-16: ADR 0007, frontend decisions, and UI before Phase 2 (#49 / PR #53)

**Goal:** Settle the frontend's board component, licences, layout and theme before any frontend code, and record the new order of work.

**Changes:**
- [ADR 0007](decisions/0007-frontend-board-and-visual-identity.md): react-chessboard (MIT) instead of Chessground (GPL-3.0); the Cburnett piece set under its BSD option, with attribution; chess.js (BSD-2-Clause); Inter (OFL 1.1), self-hosted; Lichess's principles but not its look; no gamification; the layout of the findings screen; how evidence is drawn; the dark and light theme and the two meaning colours (orange `#F0883E` for "your mistake", blue `#58A6FF` / `#1F6FEB` for evidence).
- README roadmap: Phase 1 ticked (v0.1.0), and a "Next, in this order" list. CHANGELOG.
- Issues: #49 (this ADR), #50 (scaffold and theme), #51 (read-only findings and patterns API, split out of #24), #24 narrowed to the frontend screen with the layout from the ADR, #52 (Playwright).

**Decisions:**
- **UI before Phase 2.** The order is now ADR 0007 → scaffold and theme (#50) → findings and patterns API (#51) → findings screen (#24) → Playwright (#52) → one or two Cucumber scenarios → knight fork (#26). Reasons: the screen is demoable from the data that already exists (the demo's 5 hanging-piece findings), so the pipeline's output can be seen and checked on a board rather than in SQL; and the React frontend becomes visible in the repo. Phase 2's detectors then land in a UI where each new finding can be checked by eye.
- **#24 split.** The backend API (#51) and the screen (#24) are separate PRs: each is reviewable on its own, and the API's query-count test (the N+1 item of #37) does not depend on any frontend.
- **The piece set's licence was checked, not assumed.** react-chessboard's package is MIT but does not name its default pieces. Their SVG paths are the Cburnett set from Wikimedia Commons, which is offered under GFDL (relicensed to CC BY-SA 3.0), BSD or GPL. Using it under BSD keeps the frontend permissive; the attribution goes into `THIRD_PARTY_NOTICES.md` with the scaffold (#50). Alternative rejected: drawing our own pieces now, which is work with no product value at this stage.
- **`EvidenceBoard` draws only today's facts** (piece and square, moved piece, evidence line). New fact types bring their renderer in the same PR (#34); a test keeps "every fact type has a renderer" true.
- **The mockup stays private.** The ADR describes its decisions in words; from now on the implementation is the reference.

**Verification:**
- Versions and licences from the npm registry on 2026-11-16: react-chessboard 5.12.1 (MIT, released 2026-08-16, peer React 19), Chessground 9.2.1 (GPL-3.0-or-later), chess.js 1.4.0 (BSD-2-Clause), `@fontsource-variable/inter` 5.3.0 (OFL-1.1).
- Piece set: the default pawn in react-chessboard 5.12.1 (`dist/index.esm.js`, `wP`, a 45×45 viewBox) has the path of Commons' `Chess_plt45.svg` by Cburnett; the Commons licence template is `{{self|GFDL|migration=relicense|BSD|GPL}}`.
- Docs only; CI runs as usual.

**Open points / risks:**
- The light theme's contrast for the orange and blue highlights on the blue-grey squares is checked in #50; if one falls short, the highlight style changes, not the colours.
- react-chessboard is maintained by one person. The board is wrapped in our own `EvidenceBoard`, so a replacement would touch one component.

**Links:** [#49](https://github.com/Gergo019/blindspot-chess/issues/49), [PR #53](https://github.com/Gergo019/blindspot-chess/pull/53), [ADR 0007](decisions/0007-frontend-board-and-visual-identity.md), [#50](https://github.com/Gergo019/blindspot-chess/issues/50), [#51](https://github.com/Gergo019/blindspot-chess/issues/51), [#24](https://github.com/Gergo019/blindspot-chess/issues/24), [#52](https://github.com/Gergo019/blindspot-chess/issues/52), [#26](https://github.com/Gergo019/blindspot-chess/issues/26)

## 2026-11-16: Release v0.1.0 (PR #48)

**Goal:** Close Phase 1 with a tagged release: a fresh clone runs end to end with one command, and the CHANGELOG, README and this log say exactly what v0.1.0 does and does not do.

**What Phase 1 delivered:**
- **Import:** recent finished Lichess rapid games, fetched in the background in bounded batches, stored as PENDING (#5).
- **Analysis:** a FastAPI service with one pinned, checksum-verified Stockfish 19 binary, single-threaded at a fixed depth and hash. `POST /analyze` evaluates every half-move and classifies it by the drop in winning chances (Lichess formula) (#2). The backend analyses PENDING games in a background job, one at a time, and stores evaluations and findings (#4, #22).
- **Hanging-piece detection** with an evidence line and typed facts, now `hanging-0.4` ([ADR 0004](decisions/0004-hanging-piece-detector.md)) (#3, #31).
- **Demo data:** 20 real rapid games from the Lichess open database (2013-01, CC0), loaded by the `demo` profile, so the whole pipeline runs without a Lichess account (#23).
- **Schema:** Flyway V1 to V4. **Tests:** unit and Testcontainers integration tests, an end-to-end test through the real analysis image, and analysis-service tests inside the image. **CI** for all three jobs.

**Hardening after the 2026-11-14 code review (H1 to H4):**
- **#30, failures by kind** ([ADR 0005](decisions/0005-failure-handling-in-background-analysis.md)): a rejected game or an unreadable or unstorable response is handled without losing data; an outage of the analysis service no longer fails games; games are claimed with one conditional update.
- **#31, hanging-piece baseline:** the loss is measured against the engine's best line, through forcing sequences, and an exchange on the moved piece's square is judged as a whole (`hanging-0.3`, `hanging-0.4`).
- **#32, PGN validation:** every malformed or impossible game is a 422, never a 500; a property-based test guards this.
- **#33, reproducibility:** golden files pin the full `/analyze` output, dependencies are locked with hashes, base images pinned by digest, and threads and hash size are stored with every evaluation ([ADR 0002](decisions/0002-python-analysis-service.md)).

**Changes in this PR:**
- `CHANGELOG.md`: everything under Unreleased moved to `[0.1.0] - 2026-11-16`, regrouped as Added / Changed / Fixed (the hardening items that corrected wrong behaviour are under Fixed), an empty Unreleased section above it, and compare links.
- `README.md`: status line for v0.1.0; the analysis-service health row also names threads and hash size.
- `.env.example`: two stale comments fixed. The analysis job takes the *newest* PENDING games (since #30, the comment still said oldest), and evaluations record threads and hash size too (since #33).

**Verification:**
- **Getting started on a fresh clone**, run exactly as written (fresh `git clone` of main at 87295fe into a temp folder, `cp .env.example .env`, `docker compose up --build`, here with `-d`), on an Apple Silicon Mac: images built in about a minute (with the local Docker layer cache; the clone itself was fresh); both `/health` endpoints UP (the analysis service reports Stockfish 19, depth 16, 1 thread, 64 MB). The `demo` profile loaded 20 games as PENDING; all 20 were DONE about 3.5 minutes after they were created (17:43:26 to 17:47:04 UTC), none FAILED.
- The README's findings query returned 5 `hanging-0.4` findings, as documented: 008j6z3b (White, ply 9, Qe2), 008pkspi (Black, ply 36, Rb6), 00ckuzm1 (Black, ply 66, Rc3+), 00jay4x6 (Black, ply 22, Be7), 00lv6mdm (Black, ply 16, Bh3). The first two are the golden-file games of #33.
- All 1,238 stored evaluations record 1 thread and 64 MB (V4). No WARN or ERROR lines in the backend log.
- CI on PR #48 green on the first run: analysis service 2m20s, backend 1m38s, Compose stack 1m26s.

**Known limits:**
- Low-severity review items are collected in [#37](https://github.com/Gergo019/blindspot-chess/issues/37): mate handling in the classifier, `engine_version` width, `BaseEntity` clock, the classification formula not re-checked against lila, an N+1 on the future findings screen, per-account Lichess rate limiting, and the 75-move rule.
- [#41](https://github.com/Gergo019/blindspot-chess/issues/41): a line still forcing at the 8-ply cap is not judged, so some real hung pieces are missed (known case: Qc2).
- No user interface yet; findings are visible only in the database.

**What Phase 2 starts with:**
- [#26](https://github.com/Gergo019/blindspot-chess/issues/26): knight-fork detector (`knight-fork-0.1`, ADR 0006), the second motif.
- [#34](https://github.com/Gergo019/blindspot-chess/issues/34): cause and defence facts for HANGING_PIECE (already attacked, defended, why lost), which typed claims need before any explanation.
- [#35](https://github.com/Gergo019/blindspot-chess/issues/35): decide whether a loose piece lost to a check or double attack is a HANGING_PIECE sub-type or a third motif.

**Links:** [CHANGELOG 0.1.0](../CHANGELOG.md), [PR #48](https://github.com/Gergo019/blindspot-chess/pull/48), release v0.1.0 (tagged on the merge commit after merging)

## 2026-11-16: Golden-file reproducibility test, pinned dependencies, engine threads and hash size (#33 / PR #46)

**Goal:** Make "analysis is reproducible" (product rule 4) checkable instead of assumed. Phase 1 hardening item H4 from the 2026-11-14 code review.

**Changes:**
- **Golden files:** `analysis-service/tests/golden/` holds the full `POST /analyze` output (request and response) for two demo games: `008j6z3b` (user White, 42 plies, a hanging-piece finding at ply 9) and `008pkspi` (user Black, 39 plies, a hanging-piece finding at ply 36). `tests/test_golden.py` compares the live output exactly: engine block first, then ply by ply, then the findings, then the whole response. `tests/golden/regenerate.py` rewrites them only with `--write`; without it, it only reports which files would change and exits 1 if any would.
- **Hashed Python dependencies:** `requirements.in` and `requirements-dev.in` hold the direct dependencies at their existing exact versions; `requirements.txt` and `requirements-dev.txt` are compiled by pip-compile with `--generate-hashes` (the command is in each file's header). The Dockerfile installs both with `--require-hashes`.
- **Base images pinned by digest** in both Dockerfiles: `debian:bookworm-slim`, `python:3.13-slim`, `maven:3.9-eclipse-temurin-25` and `eclipse-temurin:25-jre`, each `tag@sha256:<index digest>`. The digests are those of the multi-platform image index, so the same pin builds on amd64 (CI) and arm64 (Mac).
- **Backend build image back to `maven:3.9-eclipse-temurin-25`** (Dependabot had moved it to `-26`, a non-LTS JDK, in #18), and `.github/dependabot.yml` now ignores every tag change of `maven` (see Decisions).
- **Threads and hash size recorded:** the `/analyze` engine block and `/health` return `threads` and `hashMb`. Flyway `V4__engine_settings.sql` adds `engine_threads` and `engine_hash_mb` to `move_evaluations` (nullable: NULL means "stored before V4, not recorded"; positive if set; both set or both NULL). `EngineRun` in the backend carries them, validates them, and `isComplete()` says whether a stored run records every setting. V1 to V3 are unchanged.
- Docs: ADR 0002 (what reproducibility guarantees and what it does not), CONTRIBUTING (golden-file regeneration needs a devlog entry; dependencies are edited in the `.in` files), analysis-service README, CHANGELOG.

**Decisions:**
- **Exact comparison, not a tolerance.** With a single thread, a fixed hash, `ucinewgame` before every position and a pinned binary, Stockfish is deterministic, so any difference is a real change (engine, settings, detector or dependency). A tolerance would hide exactly the drift the test is for.
- **Golden games from the demo data**, because they are real games already in the repo under CC0, and both have a finding, so the files pin the detector too, not only the evaluations.
- **What the `maven` Dependabot rule does.** From dependabot-core's Docker tag parser (`docker/lib/dependabot/docker/tag.rb`): a tag like `3.9-eclipse-temurin-25` matches its `WORD_WITH_BUILD` pattern (`-words-<number>`), so the whole tag is the "version", words are stripped and it is compared as **3.9.25**; the format `<version>-eclipse-temurin-<build_num>` is kept. A JDK change from `-25` to `-26` is therefore a semver **patch** update (3.9.25 to 3.9.26), which is why ignoring major updates did not stop #18, and a Maven 3.10 with JDK 25 would be a minor update. The rule now ignores major, minor and patch updates of `maven`.
  - It **does** stop Dependabot from changing the tag at all: no JDK change in the suffix, no Maven minor upgrade.
  - It **does not** stop digest updates of the same tag: `maven:3.9-eclipse-temurin-25` is a moving tag, and its new digests (Maven 3.9.x patches, JDK 25.0.x patches, OS fixes) still arrive as explicit PRs. That is intended.
  - It **does not** upgrade Maven or the JDK for us: those are manual changes now, as for the Java LTS and Python versions. And it does not cover other images: `eclipse-temurin` still ignores only major updates, `python` major and minor.
  - The alternative, a `versions` range, was rejected: the "version" Dependabot compares mixes Maven and JDK numbers, so no range expresses "same JDK".
- **pip-tools version.** pip-compile 7.5.3 (the version Dependabot runs) and 7.6.0 fail with the image's pip 26.2.2 (`make_requirement_preparer() missing … 'allow_editables'`). The files were compiled with pip-tools 7.7.0 in a throwaway container. Because 7.7.0 was released the same day, I cross-checked with pip-tools 7.6.0 on pip 25.3 (released 2025-10-25): identical pins and hashes, only the header differs. pip-tools is never installed in the image; it only writes the lock files.
- **Dependabot and the lock files:** Dependabot's pip ecosystem recognises pip-compile output (the header and the `.in` next to it), updates the `.in` pin and recompiles with the options in the header, so `--generate-hashes` is kept. The pip entry in `dependabot.yml` is unchanged.
- **Threads and hash size are nullable**, not backfilled with 1 and 64: the service probably ran with those values, but a default would claim something that cannot be proven per row. `EngineRun` requires both or neither, and so does a check constraint.

**Verification:**
- **arm64 (this Mac, Apple Silicon, Stockfish `linux-arm64-universal`):** the golden files were generated here; afterwards, in a freshly built test image, `regenerate.py` without `--write` reports both files unchanged, and the full analysis-service suite passes: 148 passed in 90 s, including the 3 golden tests (`uname -m`: `aarch64`).
- **amd64:** CI on PR #46 ran the same golden tests on the x86-64 binary: both games match the files generated on arm64 exactly, 148 passed in 75 s. Both lock files installed with `--require-hashes` on the runner. First run green, about 3m20s in all: analysis service 2m06s, backend 1m21s, Compose stack 1m06s.
- Analysis-service image builds with `--require-hashes` for both lock files.
- Backend `mvn verify` in Docker: 76 unit tests and 26 integration tests green, including the new `SchemaIT` tests (threads and hash size stored; a pre-V4 row with NULLs loads with `isComplete() == false`; one without the other is rejected by the database) and `DomainRulesTest` for `EngineRun`.

**Open points / risks:**
- The first monthly Docker run of Dependabot should show digest-update PRs for the pinned tags and no `maven` tag change. If it proposes a tag change, or no digest updates at all, revisit the ignore rule.
- The first weekly pip run should produce a PR with regenerated hashes. If Dependabot's pip-compile (7.5.3) cannot compile with the current pip, the PR fails visibly; recompile by hand then.
- Every intended output change (Stockfish upgrade, other defaults, a new detector version) now has to regenerate the golden files in the same PR, with a devlog entry. That is the point, but it is extra work.
- Golden files cover two games. They catch drift in the engine, the settings, the classifier and the hanging-piece detector, but not every code path.

**Links:** [#33](https://github.com/Gergo019/blindspot-chess/issues/33), [PR #46](https://github.com/Gergo019/blindspot-chess/pull/46), [ADR 0002](decisions/0002-python-analysis-service.md), [#18](https://github.com/Gergo019/blindspot-chess/pull/18), [dependabot-core docker tag.rb](https://github.com/dependabot/dependabot-core/blob/main/docker/lib/dependabot/docker/tag.rb), the code review of 2026-11-14

## 2026-11-16: PGN validation, no 500 from /analyze (#32 / PR #45)

**Goal:** Every malformed or impossible game is a 422 with a clear message, and no input can make `POST /analyze` answer 500. Phase 1 hardening item H3 from the 2026-11-14 code review (A6).

**Changes:**
- `analysis-service/app/pgn.py` rejects with `InvalidGameError` (422):
  - null moves (`--`);
  - a start position that is not `is_valid()`;
  - more than one game in the PGN (trailing plain text is still ignored);
  - a movetext result that contradicts the `Result` header. A small `GameBuilder` subclass keeps the movetext's termination marker, which python-chess otherwise drops when a header exists;
  - moves after checkmate, stalemate, insufficient material or the 75-move rule, with "after the game ended by …". Moves after checkmate and stalemate were already illegal for python-chess, but are now named as such.
- **Fivefold repetition ends the game for analysis:** later moves are dropped, and `ParsedGame.ended_by_fivefold_at` records where.
- Any other exception while parsing becomes an `InvalidGameError` ("The PGN could not be read").
- `app/engine.py`: the analysis loop raises `InvalidGameError` instead of reading a missing best move. `app/main.py` maps that to 422 too.
- Tests:
  - `test_pgn.py`: one test per case, with the review's examples. These cover null moves (`1. e4 -- 2. d4 -- 3. Nf3 e5 1-0`), a FEN without a white king, two games, `*` against `1-0`, a move after mate, after stalemate and after insufficient material, the fivefold case (knight shuffle, analysed to ply 16), the 75-move rule, and an unexpected parser failure.
  - `test_engine_guard.py`: the loop never reads a missing best move. It needs no Stockfish.
  - `test_api.py`: the fivefold and 75-move games through the API.
  - `test_api_fuzz.py`: Hypothesis, pinned `hypothesis==6.168.5`, released 2026-10-05. It sends random text, every truncation of four real games, huge and unclosed comments up to past the 100,000-character limit, and junk headers, and asserts the status is always 200 or 422. It is derandomized so CI is reproducible, and runs the engine at depth 1, because the property is about errors, not analysis.
  - Backend `AnalysisEndToEndIT`: a null-move game through the real image ends FAILED after 1 attempt, nothing is stored, and the log has the 422 and "null move".
- Analysis-service README (the 422 list, fivefold) and CHANGELOG.

**Decisions:**
- **Fivefold repetition: end of game, not a rejection.** I ran the complete new validation, locally and never in CI, over the 2013-01 open database (`tools/demo-data/cache`):

  | Finished games | Games | Fivefold, game ends there | Fivefold, game continues | 75-move position reached |
  |---|---|---|---|---|
  | Rapid, by the demo tool's header rules | 39,080 | 9 | **79** | 0 |
  | All rated standard | 121,332 | 23 | **213** | 0 |

  Real games continue after a fivefold repetition, so rejecting them would mark 0.2% of real rapid games FAILED. They are analysed up to the fivefold position instead: the game is over by the rules there, and the engine has no best move after it.
- **The 75-move rule stays a rejection.** No game in the month reaches such a position, so there is no evidence that real games continue past it. If one ever does, the 422 says so plainly.
- **The whole new validation accepts all 39,080 rapid games;** 79 of them end at the fivefold position. The 26 recorded Lichess and demo games also pass, except the one unfinished game that the importer skips anyway. No real game continues after insufficient material.
- **Trailing text that is not a game** (for example a note after the result) is ignored, but a second game with its own tags or moves is rejected. Requests should be one game each.
- **The reason for a 422 is logged, not stored.** ADR 0005 already settled this: the game is FAILED at once and the service's message appears in the backend's WARN log with the game id. No backend change was needed.

**Verification:**
- Analysis-service tests in the Docker test image: 145 passed in 37 s, the fuzz tests about 15 s of that.
- Backend `mvn verify` in Docker: 75 unit tests and 23 integration tests green, including the new rejection test through the rebuilt image.
- CI on PR #45, first run green, **3m04s** in all. Analysis-service job 1m51s, up from about 1m05s: its 145 tests take 76 s on the runner, against 37 s locally, and the fuzz tests account for most of the increase. Backend job 1m29s: 75 unit tests and 23 integration tests, `AnalysisEndToEndIT` (3 tests) 36.2 s. Compose stack 1m08s.

**Open points / risks:**
- The fuzz test runs a fixed, derandomized sample (60 examples per strategy) so CI stays reproducible. It is a guard rail, not an exhaustive search.
- A fivefold game's moves after the repetition get no evaluations, so they can never produce a finding. That is correct by the rules, but the stored game has fewer evaluations than half-moves.

**Links:** [#32](https://github.com/Gergo019/blindspot-chess/issues/32), [PR #45](https://github.com/Gergo019/blindspot-chess/pull/45), [ADR 0005](decisions/0005-failure-handling-in-background-analysis.md), the code review of 2026-11-14 (A6)

## 2026-11-16: Exchange on the square summed over all captures, hanging-0.4 (#31 follow-up / PR #44)

**Goal:** Close the known gap left by PR #43. The exchange on the square stopped at the first recapture, so Nxd5 exd5 Qxd5 (the knight bought two pawns, a net 1) was still reported as a loss of 2.

**Changes:**
- `hanging_piece.py`: when M is a capture and the opponent's first PV move recaptures on M's square, every capture on that square inside the played window is summed from the user's side. The window is rule 3's window, computed from the played line alone, because the best line plays no part in this case. The finding needs a net loss of at least 2. The loss is that net loss, and the evidence line is the played window. A window that ends unfinished or in mate gives no finding. Version `hanging-0.4`.
- Tests:
  - Nxd5 exd5 Qxd5 with pawns on d5 (net 1): no finding.
  - Nxd5 exd5 with no retake: knight, loss 2.
  - Nxd5 exd5 Rxd5 Rxd5: net loss 6.
  - A line that stops right after a retake on the square: not judged.
  - The existing exchange cases still pass, and `hanging-0.4` is expected in `test_api.py`, `test_findings_engine.py` and `AnalysisEndToEndIT`.
- ADR 0004: rule 2 text, version table (`hanging-0.4`), and the known-gap note on the first recapture removed. Analysis-service README, README, demo notes, CHANGELOG.
- `CONTRIBUTING.md` (Workflow): pull requests are opened as drafts and marked "Ready for review" only with the final report. This PR is the first one opened that way.
- Backlog issue #37: re-detect findings when the detector version changes.

**Decisions:**
- **A new version, not a change to 0.3.** `hanging-0.3` is on main (PRs #42 and #43), and a stored finding must say which rules produced it.
- **The window comes from the played line alone** in this case. The best line would only make the window longer without adding anything to the exchange on the square.
- **An exchange still going at the end of the window is not judged,** the same rule as elsewhere.

**Demo check:** all 20 demo games through `POST /analyze` of the new image. **The table is unchanged:** `008j6z3b` (knight d4, 2), `008pkspi` (rook b6, 4), `00ckuzm1` (rook c3, 6), `00jay4x6` (bishop b7, 5) and `00lv6mdm` (bishop h3, 3), now with `hanging-0.4`. No other game has a finding. None of the 20 games has a capture that is recaptured on its square with a net loss of 2 or more. The README example (the Nd4 game) is unchanged apart from the version.

**Verification:**
- Analysis-service tests in the Docker test image: 115 passed.
- Backend `mvn verify` in Docker: 75 unit tests and 22 integration tests green, including `AnalysisEndToEndIT` through the rebuilt analysis image (`hanging-0.4`).
- CI on PR #44, first run green, **3m01s** in all. Analysis-service job 1m05s (115 tests in the image). Backend job 1m50s: 75 unit tests and 22 integration tests, `AnalysisEndToEndIT` 54.1 s. Compose stack 1m07s.

**Open points / risks:**
- Material lost elsewhere in a line that starts with a recaptured capture is still not attributed (known gap, ADR 0004).
- Stored findings keep their `detectorVersion` until the games are analysed again (#37).

**Links:** [#31](https://github.com/Gergo019/blindspot-chess/issues/31), [PR #44](https://github.com/Gergo019/blindspot-chess/pull/44), [ADR 0004](decisions/0004-hanging-piece-detector.md), [#37](https://github.com/Gergo019/blindspot-chess/issues/37)

## 2026-11-16: Hanging-piece loss measured against the engine's best line (#31 / PR #42, #43)

**Goal:** `hanging-0.3`. Measure what a move cost compared with the engine's choice, never judge a line in the middle of an exchange, and run the window through checks. Phase 1 hardening item H2 from the 2026-11-14 code review (A2, A7, the window part of A4).

**Changes:**
- `analysis-service/app/detectors/hanging_piece.py`, rules as in ADR 0004:
  - **Baseline.** The detector takes the engine's best line (the PV of the position before M, starting with the best move) as a new argument. It compares that line with the played line (M and the PV after M), both from the position before M, over the same window. `materialLost` = balance at the end of the best line minus balance at the end of the played line.
  - **Window.** At least 4 plies; extended while the last ply of either line was forcing (a capture, a check, or the reply to a check); cap 8. A window that still ends on a forcing ply (PV ran out, or cap) means no finding.
  - **Exchange on the square** (rule 2): if M is a capture and the opponent's first PV move recaptures on M's square, only that exchange is judged. It fires if value(moved piece) - value(piece M captured) is at least 2, with the moved piece as `piece`, the difference as `materialLost`, and the recapture as the evidence line.
  - **Played-line guard** (rule 4): otherwise, the played line itself must end at least 2 below the balance right after M.
  - **Evidence:** the played window without M.
  - Version `hanging-0.3`.
- `app/engine.py` passes the position-before PV (`before.pv`, already kept, 8 plies) to the detector. No change to the search or its settings.
- Tests (`test_hanging_piece.py`):
  - new `TestBaseline` (Qxf3 with the engine lines, loss 4; the same with a hand line, loss 3; a loss shared by the best line is not blamed);
  - `TestWindow` reworked: mid-exchange and A7 cases give no finding, the check and its reply are one unit, the 008pkspi best line is not cut at the reply, the cap, an equal-length extension, and Qc2 as a known miss;
  - new `TestGuards` (a quiet move followed by an even trade; demo game 001c7wqq, a queen trade);
  - new `TestExchangeOnTheSquare` (a knight takes a defended pawn: knight, loss 2; rook for knight: 2; demo game 00hjmrjd, an even trade: no finding; later losses elsewhere in the line are not counted);
  - every case now passes a best line.
- `test_findings_engine.py`: Qxf3 fires with the real engine, Qc2 does not; games from a FEN start position.
- `test_api.py` and the backend's `AnalysisEndToEndIT` expect `hanging-0.3`.
- ADR 0004: rules, the reasoning for the baseline, version table, known misses; open question (a) closed. Analysis-service README, README (demo: 5 games), `tools/demo-data/README.md`, CHANGELOG.
- Issues: #41 (follow-up: a longer window for forcing lines; Qc2), and #26 notes that its N10 belongs to HANGING_PIECE.

**Decisions:**
- **Best line as the baseline, equal windows.** v0.2 compared with the balance before M. It missed a piece lost inside an exchange: Qxf3 (instead of gxf3) leaves the balance the same after Qxf3 Qxd4. It also blamed moves for pieces lost in every line. The engine already searches the position before M, so its PV is free. Equal windows keep either line from getting extra plies to win material back. Rejected: the value of the first captured piece (open question (a)), which ignores what is won back.
- **Replies to checks are forcing too.** This came from the demo check. In `008pkspi` the best line Nd4 Qxe6 Nxe2+ Kh1 Bxe6 recaptures the queen after the reply Kh1. A capture-or-check rule cut it at Kh1, measured the best line 7 points down, and lost a real finding. A check and its reply are one unit.
- **No finding when a window ends on a forcing ply** (review A7). The test `test_cut_after_four_plies_the_same_exchange_looks_level` asserted that a line cut after four plies of an exchange should be judged "level". It is replaced by tests that such lines are not judged at all; the A7 line (Ke2 with only Nxb5) fired with loss 3 in v0.2 and no longer does.
- **Exchange on the square, and a played-line guard, both from the demo check.** The best-line baseline mixes "missed a win" with "hung a piece":
  - In `001c7wqq` the user traded queens (Qxd8+ Bxd8) instead of winning a rook (Qxa8), and v0.3 without a rule for it reported "queen hanging on d8, lost 6". When the opponent recaptures on the square the user's move captured on, only that exchange is judged: queen for queen is 0, no finding.
  - A plain "no finding on a recapture" guard was tried first and merged with PR #42. It also dropped the most common hung piece of all, a piece taking a defended pawn, so it was replaced in review by judging the exchange on the square (PR #43, pushed after #42 was merged).
  - A quiet move followed by an even trade, with a best line that wins something, would fire the same way. The played-line guard stops it.

  `materialLost` stays the loss against the best line, so it can include a missed gain (008pkspi: rook for knight, while the best line ends a pawn up: 4).
- **`00hjmrjd` no longer fires**, on purpose. There Nxd5 exd5 is an even knight-for-bishop trade (0 on the square), and Black's material is lost later, when Nxc5 takes the c5 bishop. Reporting "knight lost on d5" (v0.2) was wrong about the piece; the later loss is a documented known miss. An alternative was considered that measures the played line from before M after a recapture. It would have kept this finding, with the same wrong facts. It was rejected after checking the position: the first reading of it, mine, wrongly took d5 for a pawn.
- **Qc2 is a known miss, not a fixture that fires.** With the real engine lines, both windows are still forcing at the cap: the played line ends on the check Ne1+, the best line on the reply Bc8 to Qb8+. Making it fire needs a longer window and more PV plies, a change measured on real games first (#41).
- **Rule 2 is unchanged:** a double attack that starts with a check (A4, the motif part) stays out; that is #35.

**Demo check** (all 20 demo games through `POST /analyze` of the new image, against the same games through main's image):

| Game (ply, `demo` plays) | `hanging-0.2` | `hanging-0.3` | Why |
|---|---|---|---|
| `008j6z3b` (9, W) | knight d4, 3 | knight d4, 2 | the best line keeps less than the static baseline |
| `008pkspi` (36, B) | rook b6, 2 | rook b6, 4 | the best line ends a pawn up; window runs through the check reply |
| `00ckuzm1` (66, B) | rook c3, 5 | rook c3, 6 | longer window (7 plies) |
| `00hjmrjd` (20, B) | knight d5, 3 | none | exchange on the square: knight for bishop, 0 |
| `00jay4x6` (22, B) | bishop b7, 5 | bishop b7, 5 | same |
| `00lv6mdm` (16, B) | bishop h3, 3 | bishop h3, 3 | same |
| `00mq9lvs` (47, W) | bishop f4, 2 | none | the best line loses the a4 pawn too: the move costs 1 |

No new finding appears in the other 13 games. **5 distinct games** keep a finding, above the pattern threshold of 3, so the demo selection is not re-run (`tools/demo-data/README.md` says so). The selection itself was made with `hanging-0.2`.

**Verification:**
- Analysis-service tests in the Docker test image: 112 passed (engine tests included).
- Each new rule is pinned: with no rule for recaptures on the square, 2 tests fail; with the played-line guard removed, 1 test fails; without check replies as forcing, 2 tests fail. (Checked with the first version of the recapture rule; the exchange-on-the-square tests then pin the replacement.)
- Backend `mvn verify` in Docker: 75 unit tests and 22 integration tests green, including `AnalysisEndToEndIT` through the rebuilt analysis image (the knight game still gives `e5d4 ...`, now `hanging-0.3`).
- CI on PR #42, first run green, **2m59s** in all. Analysis-service job 1m08s (109 tests in the image). Backend job 1m47s: 75 unit tests and 22 integration tests, `AnalysisEndToEndIT` 55.1 s. Compose stack 1m06s.

**Open points / risks:**
- **Known misses:** forcing lines longer than 8 plies (Qc2, #41), and material lost later in a line the user started with a capture that was recaptured (00hjmrjd).
- **The exchange on the square stops at the recapture:** Nxd5 exd5 Qxd5 (knight for two pawns, a net 1) would still be reported as 2. No demo game has such a case.
- **The golden files of #33** will be taken with `hanging-0.3`; any later detector change regenerates them on purpose.
- **Stored demo findings** in an existing database keep `hanging-0.2` until the games are analysed again. A fresh `docker compose down -v` gives `hanging-0.3`.

**Links:** [#31](https://github.com/Gergo019/blindspot-chess/issues/31), [PR #42](https://github.com/Gergo019/blindspot-chess/pull/42), [PR #43](https://github.com/Gergo019/blindspot-chess/pull/43) (exchange on the square), [ADR 0004](decisions/0004-hanging-piece-detector.md), [#41](https://github.com/Gergo019/blindspot-chess/issues/41), the code review of 2026-11-14 (A2, A4, A7)

## 2026-11-16: Transient analysis-service failures no longer fail games (#30 / PR #38, #39)

**Goal:** A short outage of the analysis service must not fail games for good. Only a game the service rejects is FAILED at once. The same PR claims games atomically, analyses the newest games first, and makes only template explanations showable. This is the first Phase 1 hardening item from the 2026-11-14 code review (A1, A8, A9), to be done before `v0.1.0`.

**Changes:**
- `AnalysisFailedException.Kind`: `REJECTED`, `UNAVAILABLE`, `TIMEOUT`, `INVALID_RESPONSE`. `AnalysisClient` decides the kind from the exception type and the status code, never from a message:
  - 422 is `REJECTED`; every other error status is `UNAVAILABLE`;
  - for an I/O error, a read timeout (`HttpTimeoutException`, but not its subclass `HttpConnectTimeoutException`) is `TIMEOUT`, and anything else (connection refused, host not found, connect timeout, connection reset) is `UNAVAILABLE`;
  - a 2xx with no body or an unreadable body is `INVALID_RESPONSE`, and so is readable content the backend cannot store (decided in `AnalysisService`, see below).
- `Game`:
  - `releaseAnalysis()`: RUNNING -> PENDING, the attempt given back;
  - `retryOrFailAnalysis()` (for a timeout or an invalid response): PENDING while attempts remain, otherwise FAILED. It shares the rule with `recoverStuckAnalysis()`.
- `GameRepository.claimForAnalysis(id, now)`: a conditional JPQL update, PENDING -> RUNNING with attempts + 1, the start time and `updated_at`. It returns whether exactly one row changed. `findByAnalysisStatusOrderByPlayedAtDesc` replaces the ascending query.
- `AnalysisService.analyzeGame` claims through the query and returns an `Outcome`: `DONE`, `FAILED`, `RETRY`, `INVALID_RESPONSE_RETRY`, `INVALID_RESPONSE_FAILED`, `SERVICE_UNAVAILABLE` or `NOT_CLAIMED`. It acts on the kind and logs the kind every time: an invalid response at ERROR, the rest at WARN. Storing first turns the response into entities in memory, then saves and flushes everything in one transaction; anything the backend or the database rejects becomes `INVALID_RESPONSE` and rolls back.
- `AnalysisJob`:
  - newest PENDING games first;
  - stops the run at the first `SERVICE_UNAVAILABLE` or invalid response (with several workers, games not yet started are skipped);
  - `nextDelay()` is the interval, or the back-off while the service is unavailable;
  - `RunResult` gains `failed`, `retried` and `serviceUnavailable`.
- `AnalysisJobScheduler`: a `SchedulingConfigurer` trigger instead of `@Scheduled(fixedDelay)`. Each run starts `job.nextDelay()` after the previous one ended, and the first run starts after `initial-delay`.
- `Explanation.isShowable()` and `ExplanationRepository.findShowable`: `TEMPLATE` and `PASSED`.
- `docker-compose.yml`: a healthcheck on the analysis service (`/health` must report `engine: UP`; the image has Python but no curl), and the backend `depends_on` it being healthy.
- [ADR 0005](decisions/0005-failure-handling-in-background-analysis.md) "Failure handling in background analysis"; ADR 0003 gains the showable rule. README (architecture notes) and CHANGELOG.
- Tests:
  - New `AnalysisFailureHandlingIT` (PostgreSQL, the real HTTP client, a stub analysis service on a fixed port).
  - New `AnalysisJobSchedulerTest`.
  - `AnalysisClientTest` gains classification tests, including the real client against a closed port, an unresolvable `.invalid` host and a server that does not answer.
  - `AnalysisServiceTest` and `AnalysisJobTest` cover each outcome, the stopping, the back-off sequence and newest-first.
  - `DomainRulesTest` gains the new lifecycle methods and the showable rule; `SchemaIT` checks `findShowable` on PostgreSQL.

**Decisions:**
- **Failure kinds and what they do:**

  | Kind | Cause | Game | Attempt |
  |---|---|---|---|
  | `REJECTED` | 422 | FAILED at once, reason logged | counted |
  | `UNAVAILABLE` | connection refused, connect timeout, DNS failure, 5xx, other error statuses | PENDING | given back |
  | `TIMEOUT` | read timeout | PENDING, FAILED on the 2nd | counted |
  | `INVALID_RESPONSE` | 2xx without a readable body, or content that cannot be stored | PENDING, FAILED on the 2nd; logged at ERROR; the run stops, no back-off | counted |

  An outage of any length therefore never fails a game. A timeout may be the game's own fault (pathological length), so it counts against the existing limit of 2. Rejected alternative: a separate retry counter for outages, which would still fail games during a long outage.
- **Two kinds beyond the three in the task.** `INVALID_RESPONSE`, a 2xx the backend cannot read, was first FAILED at once. In review it was changed to behave like `TIMEOUT`: back to PENDING, counted, FAILED after the second, and logged at ERROR with the game id. It most likely means a contract mismatch between backend and service, which would hit every game, so failing at once would fail a whole run. A second review change gave a readable response that cannot be stored (an unknown classification or motif, values the domain rules or the database reject) the same handling, with nothing partial stored. Invalid responses now also stop the run, like an unavailable service, but without the back-off and with the attempt counted, so one mismatch costs one game an attempt instead of a whole batch. Other storing errors (for example, the database being down) still mark the game FAILED. Error statuses other than 422 and 5xx (400, 404, ...) count as `UNAVAILABLE`, because they come from a wrong URL or a contract mismatch and would fail every game.
- **Back-off:** twice the interval, doubling after each further unavailable run, capped at 10 minutes and never below the interval. It resets when a game ends DONE or FAILED, because the service answered (a 422 shows the service is up too). A run with only timeouts leaves it unchanged. The cap is a constant (`AnalysisJob.MAX_BACKOFF`), not a setting, because nobody needs to tune it yet. The schedule follows the back-off exactly through a trigger. Rejected alternative: keeping the fixed delay and skipping runs, which only rounds to multiples of the interval.
- **The claim is a query, not an entity method.** `Game.startAnalysis` checks the status in memory, so two workers that both loaded the game as PENDING under READ COMMITTED could both commit RUNNING. The `UPDATE ... WHERE analysis_status = 'PENDING'` is re-checked by PostgreSQL on the locked row, and the second worker changes nothing. `Game.startAnalysis` keeps the same transition for in-memory use (and the manual retry from FAILED); both are documented to stay in step. Rejected alternatives: `@Version` (a retry path, and the losing worker still logs a failure) and `SKIP LOCKED` (a lock held across the HTTP call, or released before it).
- **A game that cannot be claimed is `NOT_CLAIMED`, not an exception.** Before, `startAnalysis` threw `IllegalStateException` for a non-PENDING game, and the job logged it as "skipped". Now the losing worker sends nothing and logs at info level. A game that does not exist is also `NOT_CLAIMED`.
- **Newest first:** the pattern report reads a player's last 20 games, so they matter most after an import. Batches stay bounded (`ANALYSIS_BATCH_SIZE`).

**Verification:**
- `mvn verify` in Docker: 75 unit tests and 22 integration tests, all green (after the review changes; 67 and 19 before them). `AnalysisFailureHandlingIT` has 11 tests:
  - **An outage:** connection refused for one run, then 503 for the next, then working. All three games end DONE with `analysis_attempts` = 1, each failing run stops after one game, and the back-off goes 60 s, 120 s, then back to 30 s.
  - **Newest first** on PostgreSQL.
  - **A real read timeout twice** (1 s timeout): PENDING with 1 attempt, then FAILED with 2.
  - **An unreadable 2xx body** (`{"engine": [`) twice: PENDING with 1 attempt, then FAILED with 2, and nothing stored.
  - **An unknown motif** in an otherwise valid response: the run stops after the newest game, which is PENDING with 1 attempt. The next game is untouched (0 attempts), no evaluations or findings are stored, and there is no back-off.
  - **Two moves with ply 3**, accepted in memory and rejected by PostgreSQL's `move_evaluations_game_ply_uq` on the flush: the whole store rolls back (0 evaluations), then FAILED on the second attempt.
  - **A 422** is FAILED at once with 1 attempt.
  - **No transaction is active during the HTTP call.** An interceptor on every application `RestClient` records `TransactionSynchronizationManager.isActualTransactionActive()` on the calling thread. The test also checks that the same probe does see a transaction inside one.
  - **A FAILED game** (with a start time far beyond the stuck timeout) stays FAILED, with the same attempt count and start time, through three job runs, three recovery passes and a direct `analyzeGame`. The service never receives it.
  - **Two concurrent claims:** both workers first read the game as PENDING in their own open transaction, then claim it. Exactly one wins, and the game ends RUNNING with 1 attempt.
  - **An unclaimed game** is not sent to the service.
- **The transaction test fails when it should.** With `@Transactional` added to `AnalysisService.analyzeGame` as a check, `noTransactionIsActiveDuringTheHttpCall` failed; the annotation was removed again.
- **Full stack:** with the `demo` profile and a fresh build, the backend started only after the analysis service was healthy. After 3 games were DONE, I stopped the analysis service (`docker compose stop analysis-service`):
  - The game being analysed went back to PENDING with 0 attempts. The runs at 11:04:34, 11:05:34 and 11:07:34 each stopped after one game and logged "next run in PT1M", "PT2M", "PT4M". The cause was first a dropped connection, then "host not found" once the container was gone.
  - The 17 remaining games stayed PENDING with 0 attempts.
  - After `docker compose start analysis-service`, the run at 11:11:34 (4 min after the last) analysed 10 games, and the next one, 30 s after it ended, the last 7. All 20 games were DONE with `analysis_attempts` = 1, with the same 7 findings on the same plies as in #23.

- CI on PR #38, first run green, **2m51s** in all. Backend job 1m32s: 67 unit tests; `AnalysisFailureHandlingIT` 4.7 s, `AnalysisEndToEndIT` 43.1 s, `SchemaIT` 13.3 s, `LichessImportIT` 2.2 s, `DemoDataIT` 2.0 s. Analysis-service job 1m10s; Compose stack 1m15s, now including the wait for the healthcheck.

- **Follow-up PR #39** (unreadable responses retried once), before the second review change: CI green on the first run, **2m56s** in all. Backend job 1m45s: 69 unit tests and 20 integration tests, `AnalysisFailureHandlingIT` 9 tests in 5.5 s. Analysis-service job 1m01s; Compose stack 1m07s.

**Open points / risks:**
- **Newest first can starve old games** if imports outpaced analysis for a long time. It is bounded today: the importer fetches at most the newest 20 games per account and run, and one game takes seconds.
- A late store after recovery (a game recovered from RUNNING while its original call is still running) is still unhandled. It needs a read timeout longer than `stuck-after`, which the startup check prevents. Listed in the review backlog (#37).
- `TIMEOUT` cannot tell a slow game from an overloaded service. If timeouts cluster across games, the read timeout or the number of workers is wrong; the log shows the kind each time.
- **ADR numbering:** this PR takes ADR 0005, so the knight-fork ADR (#26) will be 0006.

**Links:** [#30](https://github.com/Gergo019/blindspot-chess/issues/30), [PR #38](https://github.com/Gergo019/blindspot-chess/pull/38), [PR #39](https://github.com/Gergo019/blindspot-chess/pull/39) (follow-up: invalid responses), [ADR 0005](decisions/0005-failure-handling-in-background-analysis.md), the code review of 2026-11-14 (A1, A8, A9)

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
