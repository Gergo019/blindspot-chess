# 0007: Frontend: board component, licences and visual identity

- Status: accepted
- Date: 2026-11-16 (#49), updated 2026-11-20 (highlight style and contrast results, #50), amended 2026-11-21 (the Journey and an interactive board, #77; see the [amendment](#amendment-2026-11-21-the-journey-and-an-interactive-board))

## Context

Phase 1 (v0.1.0) stores findings with evidence lines, but they can only be seen in the database. The first screen (#24) shows them on a board. Before any frontend code exists, three things have to be settled: which board component (its licence decides the project's licence), how the product looks without borrowing another site's identity, and how evidence is drawn so that every claim on screen can be played through on the board (product rule 2).

The layout below comes from a design mockup agreed on 2026-11-16. The mockup is a private design draft and is not in the repo; this ADR describes its decisions in words, and the implementation is the reference from then on.

## Decision

### Board component and licences

- **[react-chessboard](https://github.com/Clariity/react-chessboard) (MIT)**, version 5.12.1 when this was written (peer dependency React 19). It draws the board, pieces, arrows and square styles, which is all the findings screen needs.
- **Not [Chessground](https://github.com/lichess-org/chessground)**, Lichess's board: it is GPL-3.0-or-later, and bundling it into the frontend would put the project under the GPL. The project's own code stays MIT (as with Stockfish, which runs as a separate program, ADR 0002).
- **Piece set: the "Cburnett" set** by Colin M. L. Burnett. react-chessboard bundles it as its default pieces without naming it (the SVG paths match the [Wikimedia Commons files](https://commons.wikimedia.org/wiki/File:Chess_plt45.svg)). On Commons the set is offered under GFDL (relicensed to CC BY-SA 3.0), BSD or GPL; we use it under the **BSD licence**, which needs only attribution. `THIRD_PARTY_NOTICES.md` (added with the scaffold, #50) credits it.
- **[chess.js](https://github.com/jhlywa/chess.js) (BSD-2-Clause)** for replaying lines and turning UCI into SAN in the browser, where needed.
- **Font: [Inter](https://rsms.me/inter/) (SIL OFL 1.1)**, self-hosted from the npm package, so the page makes no requests to font CDNs.
- Every bundled third-party asset is listed with its licence in `THIRD_PARTY_NOTICES.md`.

### Identity: Lichess's principles, not its look

- What we take from Lichess: the board is the hero, the interface is calm, everything is reachable by keyboard.
- What we do not take: no Lichess name or logo, no Lichess colours (its brown board), no Lichess piece set or sounds. Lichess appears only as the data source ("your Lichess games", the open database), as it does today.
- **No gamification:** no badges, confetti, streak banners or points. Progress is shown as evidence (games, positions, results), not as rewards. What this means for the Journey's map: see the [amendment of 2026-11-21](#amendment-2026-11-21-the-journey-and-an-interactive-board).

### Layout

- **Header:** the BlindSpot name with a simple mark, and navigation: Findings, Games, Drills. Drills is shown disabled until drills exist. (Amended 2026-11-21: "Drills" becomes "Journey", see the [amendment](#amendment-2026-11-21-the-journey-and-an-interactive-board).)
- **Board left, side panel right.** The board takes about two thirds of the width, at most about 760 px. On narrow screens the panel stacks below the board, which is then full width.
- **Board:** muted blue-grey squares (not brown), coordinates drawn inside the edge squares. Under the board, a small legend ("Your move", "Evidence line") and the keyboard hint.
- **Side panel, top to bottom:**
  1. **"Your blind spot" card:** the motif and "in N of your last 20 rapid games". Below the evidence threshold (3 games) it shows the examples without calling them a pattern.
  2. **List of findings:** piece, square, move number, colour and material lost per finding.
  3. **Selected finding:** its facts, the evidence line as clickable move chips, and the buttons "Find a better move" and "Play the reply". "Find a better move" stays disabled until drills exist; "Play the reply" steps through the evidence line.
  4. **Move list** of the game, with the mistake highlighted.
  5. **Footer:** engine, depth and detector version of the selected finding (product rule 4: the result says how it was produced).

### Evidence on the board

- **Your move:** an orange arrow, with its from and to squares highlighted.
- **Evidence:** a blue arrow for the current evidence move, with the target square highlighted.
- The board starts in the position before the mistake. **Arrow keys** step through the evidence line (left and right; Home and End jump to its start and end), and the move chips do the same by click. Focus is always visible, and the current move is announced to screen readers.
- **`EvidenceBoard` draws only the facts that exist today:** the piece and its square, the moved piece (with its from and to squares), and the evidence line. A new fact type (for example the cause and defence facts of #34) comes with its renderer in the same PR. A test asserts that every fact type the API can return has a renderer, so a claim can never appear in the panel without something to show on the board.

### Theme

- **Dark theme by default, light theme as an option**, remembered per browser. Colours are design tokens (CSS custom properties), so both themes share one set of components.
- A muted, neutral palette with **one accent colour for "your mistake": orange `#F0883E`**. Evidence is **blue: `#58A6FF` in the dark theme, `#1F6FEB` in the light theme**. No other saturated colours compete with these two.
- Text meets WCAG AA in both themes. Arrows and square highlights are checked for at least 3:1 contrast against both square colours (WCAG 1.4.11, non-text contrast); if a colour falls short on some square, the scaffold (#50) adjusts how that highlight is drawn (for example with an outline) without changing the two colours, and records the result here.

### Result of the contrast check (#50)

The scaffold tests every pair with `frontend/src/theme/contrast.test.ts`. The two meaning colours fail as a plain fill on the blue-grey squares, so **highlights are drawn as rings, not fills**: the orange or blue ring sits between two dark halo rings (`--halo`, #0B0D10 in both themes), so the halo meets the square and the colour meets the halo. The colours themselves are unchanged. Legend swatches use the same halo outline.

| Pair | Needed | Dark | Light |
|---|---|---|---|
| orange on light / dark square, plain fill (not used) | 3 | 1.24 / 1.63 | 1.87 / 1.02 |
| blue on light / dark square, plain fill (not used) | 3 | 1.24 / 1.63 | 3.41 / 1.79 |
| halo on light / dark square | 3 | 9.52 / 4.72 | 14.34 / 7.50 |
| orange on halo | 3 | 7.69 | 7.69 |
| blue on halo | 3 | 7.70 | 4.20 |
| coordinates on light / dark square | 4.5 | 9.52 / 4.72 | 14.34 / 7.50 |

Text, muted text and disabled text pass 4.5:1 on every background in both themes (lowest: 4.61, disabled text on the raised surface), and the focus ring passes 3:1 (lowest: 4.09). The full table is in the devlog entry of #50.

## Consequences

- The project stays MIT. The price is a less feature-rich board than Chessground (no premoves, fewer animation options); the findings screen and the drills do not need them.
- Every bundled asset has a recorded licence; adding a piece set, font or icon set means adding it to `THIRD_PARTY_NOTICES.md`.
- The UI is recognisably its own, and two colours carry the meaning on every screen: orange is always the user's mistake, blue is always evidence.
- Product rule 2 is enforced in the frontend by a test, not by convention.
- **Order of work:** the UI comes before Phase 2's detectors, so the existing findings can be seen and checked on a board: ADR 0007 (#49), then the scaffold and theme (#50), the read-only API (#51), the findings screen (#24), Playwright tests (#52), then one or two Cucumber scenarios, and after that the knight-fork detector (#26).

## Amendment 2026-11-21: the Journey and an interactive board

**Context:** the learning journey ([ADR 0008](0008-learning-journey.md), #74, #77) brings the first screens where the user moves pieces. The decisions above assume a read-only board, a "Drills" item that stays disabled, and a ban on gamification that a map of lessons could easily break.

**What changed:**

| Before | After |
|---|---|
| Navigation: Findings, Games, Drills (disabled) | Findings, Games, **Journey** (`/journey`). The SM-2 review queue can live under it later. |
| One board, `EvidenceBoard`: one labelled image with an `inert` inside | `EvidenceBoard` unchanged, plus a new **`PuzzleBoard`** for lessons |
| "No gamification" stated in general | stated for the map: progress as evidence, all lessons open (below) |
| Two meaning colours, orange and blue | unchanged; "Correct" and "Not this one" are text with an icon, not a third saturated colour |

### Screens

All three keep the layout above: the board on the left (about two thirds, at most about 760 px), the side panel on the right, stacked on narrow screens.

- **Map (`/journey`):**
  - The board previews the position of the recommended next puzzle, without arrows.
  - The side panel has the unit card, which reuses the "Your blind spot" card ("Hanging pieces: in 5 of your last 20 rapid games"), and a vertical path of lesson nodes.
  - Each node shows its number, title and state with its evidence: "4 of 5 positions solved first try", "Mastered", or "2 more positions needed".
  - The nodes are a list of buttons; the recommended next one has `aria-current="step"`.
- **Lesson (`/journey/lessons/:unit/:number`, for example `/journey/lessons/hanging-piece/1`):** corrected in #95 from `/journey/lessons/:key`, because a lesson's key has a slash and the API takes it as two segments ([ADR 0008, amendment of #90](0008-learning-journey.md#amendment-2026-11-27-90-lesson-urls-and-error-answers)).
  - The side panel has the short explanation (collapsible after the first puzzle), "Position 3 of 7", and the prompt ("White to move: find a move that keeps your pieces safe").
  - Check items are marked "Mastery check: one try".
  - After an answer, the panel says "Correct" or "Not this one", the move's verdict, the engine's move, and "In your game you played …". The board then shows the game's move as the orange arrow and the evidence line in blue, stepped with the arrow keys and chips, exactly as on the findings screen.
  - "Next" moves on.
- **Result (part of the lesson screen, once every item is answered):**
  - Per position: solved first try or not, with a link to the finding it came from (`/findings/:id`).
  - Whether the lesson is mastered, the recommended next lesson, or "Retry the mastery check".
  - In slice 1 it also says that the positions are the user's own, so this is practice, not a transfer test (ADR 0008).

### `PuzzleBoard`

- The same board look as `EvidenceBoard` (react-chessboard, the same squares, rings and halo).
- Moves by **dragging**, by **clicking the piece and then the target square**, and by a **text field** that takes a move in SAN or UCI. The text field is the reliable path for the keyboard and for screen readers: react-chessboard's pieces are unnamed buttons (#52), so the board itself is not the keyboard interface.
- A **promotion choice** (queen, rook, bishop, knight) as a labelled group of buttons.
- **chess.js checks legality in the browser**, only to refuse illegal moves at once. Whether a move is correct is decided by the server (ADR 0008).
- The board region has a label naming the side to move and the task, and the result of each move is announced in a `role="status"` region.
- **The e2e suite's axe check and CSP guard apply to every new screen**, in both themes.

### "No gamification" on the map

- **Allowed:** a path of lessons in a recommended order; states drawn from evidence (positions solved first try, mastered, positions still needed); links back to the games the positions come from.
- **Not allowed:** points, XP, streaks, badges, hearts or lives, timers, leaderboards, confetti, sounds, and messages that reward rather than inform.
- **No locks:** every lesson that can be filled is open (soft locking, ADR 0008). "2 more positions needed" describes missing data, not a reward to earn.

### Consequences

- The board stays recognisably one component family, but the lesson screen is the first screen whose board is not an image. Its accessibility rests on the text field and the status region, and axe checks it.
- The findings screen keeps no "Find a better move" button. Judging a move needs the per-move verdicts, so that happens in a lesson. Whether a finding links to its lesson is left to the frontend issue.
