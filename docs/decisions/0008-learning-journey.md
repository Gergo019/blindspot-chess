# 0008: Learning journey, slice 1 (Hanging Pieces)

- Status: accepted
- Date: 2026-11-21 (#77), amended 2026-11-27 (the mastery retry rule, open question (a); see the [amendment](#amendment-2026-11-27-a-retry-needs-unused-positions))
- Design and decisions: [#74](https://github.com/Gergo019/blindspot-chess/issues/74) (proposal and answers in its comments)

## Context

The coaching loop ends in practice ([README](../../README.md#the-coaching-loop), steps 5 and 6): drill your own positions, then test transfer on unseen puzzles. #74 puts that practice into a **learning journey**: a map of motif units, each tied to a detector, with lessons of puzzles inside. The first slice has one unit, Hanging Pieces, because `hanging-0.4` ([ADR 0004](0004-hanging-piece-detector.md)) is the only detector so far.

What already exists, and what it constrains:

- **`drills` and `drill_attempts` exist since V1**, with `OWN_GAME` and `TRANSFER` kinds and SM-2 scheduling, but nothing creates or serves them. `solution_uci` holds one move, so every safe move except the engine's first choice counts as wrong (finding A10 of the 2026-11-14 review, [#36](https://github.com/Gergo019/blindspot-chess/issues/36)).
- **A finding already holds a puzzle:** the position before the mistake (`move_evaluations.fen_before`, the user to move), the engine's best move, the played move, the evidence line that refutes it, and the facts (`piece`, `square`, `capturedBy`, `materialLost`, `movedPiece`, `movedFrom`, `movedTo`).
- **Data is thin.** The 20 demo games have 5 hanging-piece findings, and a real player with 20 analysed rapid games may have about as many. "About 5 lessons of 5 to 8 puzzles" (#74) needs 25 to 40 puzzles.
- **[ADR 0007](0007-frontend-board-and-visual-identity.md) rules out gamification** and makes the board a non-interactive image. Lessons need a board the user can move on.

## Decision

### Curriculum, progress and what is stored

- **The curriculum is defined in code, not in the database:** one unit, `HANGING_PIECE`, with lessons under stable keys (`hanging-piece/1` to `hanging-piece/5`), their titles, the short explanation, sizes and the mastery rule. It changes only through code review, and every lesson assignment records the `curriculum_version` it was built from, as findings record their `detector_version`. Hand-written curriculum content stays out of scope (#74).
- **Stored:** which drills belong to which lesson for a user, and when the user passed the lesson's mastery check. A lesson does not change when new games are analysed, and a passed lesson stays passed.
- **Derived from `drill_attempts`:** whether a puzzle is solved (first try or later), "3 of 5 solved", and whether a lesson is in progress. There is no second counter next to the attempts.

The tables, in a migration after #36's (numbers assigned when they land):

| Table or column | Content |
|---|---|
| `lesson_assignments` | `user_id`, `lesson_key`, `curriculum_version`, `mastered_at` (set only by passing the check); unique `(user_id, lesson_key)` |
| `lesson_items` | `assignment_id`, `drill_id`, `position`, `role` (`PRACTICE` or `CHECK`); unique `(assignment_id, position)` and `(assignment_id, drill_id)` |
| `drill_attempts.lesson_item_id` | the lesson item an attempt answered; NULL for a review from the SM-2 queue |
| `drill_attempts.is_check` | copied from the item's role, so a **partial unique index** allows one attempt per check item. A partial index cannot look at another table, and a rule that protects data belongs in the database too ([CONTRIBUTING](../../CONTRIBUTING.md#database)). |

### Lessons fill up from findings

- Each lesson has a target of **5 practice items and 2 check items**. It is assigned only when it can be filled with **at least 3 practice items and 2 check items**; until then the map says how many more positions it needs.
- **With the demo data, only lesson 1 fills.** That is accepted (#74, answer 2): the map says so instead of padding the lessons.
- Initial order of positions across lessons: a higher `materialLost` first (a hung queen is easier to see than a hung knight), discovered losses (`movedPiece` differs from `piece`) last. A starting heuristic, to be tuned like the pattern threshold.

### Puzzle tasks: "Find a safe move" first, "See the capture" second

| Task | Position | The user plays | Correct |
|---|---|---|---|
| **Find a safe move** (`FIND_SAFE_MOVE`) | before the mistake, the user to move | a move instead of the mistake | any move whose verdict is accepted |
| **See the capture** (`SEE_THE_CAPTURE`) | after the mistake, the opponent to move, the board still from the user's side | the opponent's winning capture | any capture whose verdict wins at least the finding's `materialLost` |

- "Find a safe move" is built first. "See the capture" follows in its own change and doubles the puzzles per finding. It trains the part a blind spot is about, noticing what can be taken, and needs no new detector.
- A drill records its task (`drills.task`), so one finding gives at most one drill per task: unique `(user_id, finding_id, task)`.
- **Before the answer, a puzzle never shows the game's moves.** After it, the screen shows the user's move from the game (orange) and the evidence line (blue), as on the findings screen.

### Verdicts for every legal move, and answers checked by lookup

- **The analysis service judges every legal move** of a drill position (`POST /judge`, [#78](https://github.com/Gergo019/blindspot-chess/issues/78)). It searches each move on its own (`searchmoves`), at the pinned depth and from a cleared hash, like every search ([ADR 0002](0002-python-analysis-service.md)). A verdict has the score, the `drop` in winning chances on the classification's scale, `accepted` (`drop < 0.1`, the inaccuracy threshold) and the refutation line.
- **A time budget per request** is measured on the demo positions and enforced (#78). The budget never shortens a search: depth stays fixed, so a result is reproducible, and a request over budget fails with no partial verdicts.
- **The backend stores the verdicts with the drill** (`drill_move_verdicts`, plus the engine version, depth, threads and hash on `drills`, product rule 4, [#36](https://github.com/Gergo019/blindspot-chess/issues/36)). A background job prepares drills from findings and stores a drill and its verdicts in one transaction, so a half-prepared drill never exists.
- **Sanity rule:** if the recomputed verdicts accept the game's mistake, no drill is created and the case is logged. A drill the engine no longer supports is not shown.
- **Answer checking happens in the backend, by looking the move up in the stored verdicts.** Every legal move has a verdict, so every wrong answer comes with board evidence (product rule 2), not just "not in the set". An illegal move is rejected.
- **"Solved first try"** means the first attempt in the lesson was correct; only that counts for mastery. Every attempt is written to `drill_attempts` and goes through SM-2 as before, so lesson puzzles return later as reviews.

### Mastery: 2 check puzzles, both right first try, with a retry on new positions

- A lesson is **mastered** when both of its 2 check puzzles are answered correctly on their single attempt. Only that sets `mastered_at`, in the domain (product rule from #74: progress comes only from solving puzzles).
- A check puzzle is answered once; a second answer is refused (HTTP 409) and the database enforces it (the partial index above).
- **A failed check can be retried right away, but only on 2 unused positions** (see the [amendment of 2026-11-27](#amendment-2026-11-27-a-retry-needs-unused-positions)). A retry is a new pair of check items, added to the same assignment. An unused position is a drill that is not yet an item of any of the user's lessons.
- **Positions are never repeated as check items within a lesson.** The unique `(assignment_id, drill_id)` on `lesson_items` already enforces it.
- When fewer than 2 unused positions exist, no retry is offered: the lesson shows how many more positions a retry needs, and the user continues with practice and SM-2 reviews.

### Soft locking

- **All lessons are open, with a recommended order.** The map marks the recommended next lesson. Nothing is hidden or locked behind progress. A lesson that cannot be filled yet is shown as needing more positions, which is a fact about the data, not a lock.
- Lesson states: `NEEDS_POSITIONS`, `AVAILABLE`, `IN_PROGRESS`, `MASTERED`, plus "recommended next" as a flag. None of them is stored; all are derived.
- **A failed check needs no new state.** The lesson stays `IN_PROGRESS` until it is mastered. Two derived fields describe the retry: `retryAvailable` (the check failed and at least 2 unused positions exist) and the existing "positions still needed", which then counts the positions a retry still needs. `NEEDS_POSITIONS` stays reserved for a lesson that cannot be assigned yet.

### API

| Endpoint | Purpose |
|---|---|
| `GET /api/journey` | the map: the unit with its lessons (key, number, title, state, recommended, solved first try, size, positions still needed, retry available) and the motif's line from the blind-spot report ("in 5 of your last 20 rapid games") |
| `POST /api/lessons/{key}` | starts the lesson: assigns it if it can be filled (idempotent), returns it. On a lesson whose check failed, it starts the retry: it adds a new pair of check items if 2 unused positions exist. If not, it changes nothing and returns the lesson as it is (200, `retryAvailable: false`, the positions still needed); that is a fact about the data, not an error |
| `GET /api/lessons/{key}` | the explanation and the items in order (position, role, task, FEN, orientation, prompt, answered, correct). The game and the solution only for answered items; the result is part of the same response, including `retryAvailable` and the positions a retry still needs |
| `POST /api/lessons/{key}/items/{position}/answer` | `{moveUci}` → correct, first try, the move's verdict and refutation, the engine's move, the accepted moves, the game's move and evidence line, the lesson state |

These are the first endpoints that change data, and there is no sign-in yet ([#60](https://github.com/Gergo019/blindspot-chess/issues/60)). The POSTs accept only `Content-Type: application/json`, so a cross-site form cannot reach them (a cross-origin JSON request needs a CORS preflight, which the backend does not allow), with a test. Queries take the user id from `CurrentPlayer`, like the read API.

### Frontend

The screens, the `PuzzleBoard` and the navigation are part of the frontend decisions in [ADR 0007, amendment of 2026-11-21](0007-frontend-board-and-visual-identity.md#amendment-2026-11-21-the-journey-and-an-interactive-board). In short: "Drills" becomes **"Journey"** (`/journey`), with a map, a lesson screen and the result inside the lesson screen, all in the existing board-and-side-panel layout.

### What mastery means in slice 1

Mastery in slice 1 is measured on the user's **own positions**. The user may have seen the best move on the findings screen before, so it partly measures memory. The lesson result says so plainly and never calls it transfer. Transfer is measured later on unseen puzzles from the Lichess puzzle database ([README](../../README.md#the-coaching-loop), step 6).

## Rejected options

- **Lessons computed from the findings on every request (nothing stored):** the content of a lesson would change whenever a game is analysed, and a passed lesson could fail again after a re-analysis.
- **Progress counters in their own tables** (solved count, lesson state): a second source of truth next to `drill_attempts`, which can drift from it.
- **The curriculum in database tables:** it would need an admin path or seed migrations for content that only changes in code review.
- **A fixed 5 × 5 to 8 puzzles:** impossible with the data players actually have.
- **Lichess puzzles in slice 1 to fill lessons:** needs the licence check, an import and rating matching; that is the transfer slice.
- **Accepting only the engine's best move** (the #36 bug): marks safe alternatives wrong, which teaches the wrong thing in a lesson about not hanging pieces.
- **One MultiPV search instead of a search per move:** cheaper, but the result depends on the number of lines and is harder to reproduce.
- **Storing only the accepted set:** a wrong answer other than the game's mistake would have no evidence.
- **Checking answers in the frontend:** the accepted moves would be in the page before the answer, and the rule would exist twice.
- **Asking the engine at answer time:** seconds of waiting, the analysis service must be up while the user plays, and no stored, reproducible result per drill.
- **Strict locking** (a lesson opens only after the previous one is mastered): considered in #74 and not chosen. It is a game mechanic more than a learning aid, and ADR 0007 keeps those out.
- **Repeating the check positions in a retry** when no unused positions are left: rejected in the [amendment of 2026-11-27](#amendment-2026-11-27-a-retry-needs-unused-positions).

## Consequences

- Lessons stay stable once assigned, and progress cannot move under the user: it rests on stored attempts and one stored fact per lesson (`mastered_at`).
- Every claim on a lesson screen has board evidence, including claims about moves the user tried and the game never had.
- Each drill costs about one search per legal move in the background, roughly 35 searches; the real figure and the budget come from #78. A change of engine depth means new verdicts, versioned like the detector.
- **Re-analysis:** drills are deleted with their findings (`ON DELETE CASCADE`), and lesson items with their drills. `mastered_at` stays on the assignment: mastery records what happened, not a live view.
- **The demo shows one lesson.** The Playwright scenarios use the demo data as their oracle, as the findings tests do: with "Find a safe move" alone, 5 findings give 5 drills, exactly lesson 1 (3 practice, 2 check), if all 5 pass the sanity rule. A detector change that changes this updates the scenarios in the same PR.
- **With the demo data, a failed check cannot be retried** ([amendment of 2026-11-27](#amendment-2026-11-27-a-retry-needs-unused-positions)): lesson 1 uses all 5 positions. A Playwright scenario that fails the check therefore expects no "Retry the mastery check", but "2 more positions needed" and the way back to practice. The retry itself (a new pair of check items) cannot be shown with the demo data; backend tests cover it with their own positions. When "See the capture" arrives and doubles the drills per finding, the demo will have unused positions again, and the scenarios change with it.
- **Bounded contexts:** the training context reads findings, games and the blind-spot report through the analysis context's read services (such as `FindingReadService`), not through its repositories.
- Multi-move puzzles and one-try transfer puzzles are not needed in slice 1 (every Hanging Pieces puzzle is a single move); they are recorded in #36's note for the transfer slice.

## Open questions

- **(a) What a mastery retry uses when there are no unused positions left:** decided on 2026-11-27, a retry needs unused positions. See the [amendment](#amendment-2026-11-27-a-retry-needs-unused-positions).
- **(b) Order of positions across lessons.** The initial heuristic (higher `materialLost` first, discovered losses last) is a guess; tune it on real data. A retry draws its pair from the same order, so it takes positions a later lesson would otherwise get.

## Amendment 2026-11-27: a retry needs unused positions

**Context:** open question (a) above. A retry is a new pair of check items, but with the demo data, lesson 1 uses all 5 positions, so the first retry has none left. Two options were on the table: repeat the same check positions, marked as a repeat, or offer a retry only once unused positions exist. Decided by Gergo.

**Decision:** a mastery retry is offered **only when at least 2 unused positions exist** for a new pair of check items. Otherwise the lesson shows how many more positions a retry needs (the existing "positions still needed" fact), and the user continues with practice and SM-2 reviews. **Positions are never repeated as check items within a lesson.**

**Why:**
- **A repeated check would measure memory, and the result would have to say so.** The result already explains that mastery on the user's own positions partly measures memory and **never calls it transfer** ("What mastery means in slice 1"). A check on positions the user has just seen solved, with the answer shown after each one, would be memory only. Calling that "mastered" would stretch the claim past what the evidence shows.
- **Progress comes only from solving puzzles** (#74). A repeat would let the user reach `mastered_at` by remembering two answers, which is progress from retrying, not from solving. With unused positions, every passed check is two positions solved on the first try, seen for the first time as a check.
- **Waiting is a fact about the data, not a lock.** "2 more positions needed" is the same kind of message as for a lesson that cannot be filled yet (soft locking above, and "no locks" in ADR 0007's amendment). Practice and reviews stay open, so nothing is held back.

**What changed:**

| Before | After |
|---|---|
| "A failed check can be retried right away. A retry is a new pair of check items." | A retry right away **only on 2 unused positions**; check positions are never repeated within a lesson; otherwise "N more positions needed" and practice and reviews |
| Lesson states without a retry rule | No new state: a lesson with a failed check stays `IN_PROGRESS`; derived `retryAvailable`, and "positions still needed" counts what a retry needs |
| `POST /api/lessons/{key}`: starts the lesson | also starts a retry; with too few unused positions it changes nothing and returns the lesson (200, `retryAvailable: false`) |
| Open question (a) | decided (this amendment) |

**Consequences:**
- **Demo data:** lesson 1 uses all 5 positions, so a failed check in the demo cannot be retried until new positions exist (new analysed games, or "See the capture", which doubles the drills per finding). The Playwright scenario for a failed check shows "2 more positions needed", not a retry button; the retry is tested in the backend.
- **A retry takes positions from the pool in the order of open question (b)**, so for a player with few findings, retries delay later lessons. Accepted: a retry is the user's own choice, and the map shows the effect.
- **The database rule stays as it is.** The unique `(assignment_id, drill_id)` on `lesson_items` already prevents a repeated position; the "2 unused positions" check is a domain rule in the training context.
- **Rejected: repeating the check positions, marked as a repeat** (option A). A retry would always be possible, and the result would say plainly that it measures memory, not transfer. Not chosen, because a repeat would still set `mastered_at`, and "mastered" would then mean different things on different lessons.
