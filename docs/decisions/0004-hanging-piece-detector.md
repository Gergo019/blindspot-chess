# 0004: Hanging-piece detector

- Status: accepted
- Date: 2026-11-13 (v0.1, then v0.2 the same day)
- Current version: `hanging-0.2`

## Context

The coaching loop needs findings: a named motif behind a user's mistake, with a line the player can replay on the board (product rule 2). The first motif is the hanging piece: a move that lets the opponent win one of the user's pieces. A naive check ("the moved piece is attacked and undefended") misses discovered attacks, fires on sound sacrifices and on even trades, and has no evidence line.

## Decision

A `HANGING_PIECE` finding is reported for a user move M when **all** of these hold:

1. **M is the user's move and is classified `MISTAKE` or `BLUNDER`.** This excludes sacrifices the engine approves of (they are `BEST`, `GOOD` or an `INACCURACY`).
2. **The engine's principal variation (PV) from the position after M starts with the opponent capturing one of the user's pieces:** knight, bishop, rook or queen. Pawns are out of scope. If the engine's reply is something else, for example because taking would lose to a tactic, there is no finding.
3. **Net material loss of at least 2.** Material balance from the user's side (P=1, N=3, B=3, R=5, Q=9) before M is compared with the balance at the end of the **window**: the first 4 PV plies, extended one ply at a time while the last ply in the window was a capture, up to 8 plies. An exchange is therefore judged after its captures end. Even trades (BxN, PxB) do not count; losing the exchange (rook for bishop, 2) does.
4. **No forced mate against the user:** neither a checkmate on the board inside the window, nor an engine score after M that is a mate against the user (however far away). A mating line will be its own motif later.

**Evidence line:** the window, i.e. the PV plies used for the material check (M's follow-up, not M itself), in UCI, space-separated.

**Facts** (the start of the typed claims of [ADR 0003](0003-facts-first-explanations.md)): `piece` (the user's piece that is captured), `square` (where it is captured), `capturedBy` (the opponent's capturing piece), `materialLost` (the net loss in points), and `movedPiece`, `movedFrom`, `movedTo` (the user's move M). In a discovered loss, `movedPiece` differs from `piece`.

Every finding records its `detectorVersion`, so findings can be recomputed when the rules change.

The detector is a pure function (`analysis-service/app/detectors/hanging_piece.py`): it receives the position before M, M, M's classification, and the engine's PV and score for the position after M; it never calls the engine. The engine keeps the first 8 PV plies of every position it analyses, with unchanged search settings.

### Version history

| Version | Change |
|---|---|
| `hanging-0.1` | Rules 1 to 4 with a fixed 4-ply window; mate checked inside the window only; facts `piece`, `square`, `capturedBy`, `materialLost`. |
| `hanging-0.2` | The window extends while captures continue, up to 8 plies. A mate score against the user after M blocks the finding, so mates beyond the window count. Facts gain `movedPiece`, `movedFrom`, `movedTo`. |

## Consequences

- Findings rest on the engine's own judgement twice: the classification (rule 1) and the PV (rule 2). A finding is therefore as reproducible as the analysis itself, and pinned by the same engine version and depth.
- Discovered losses are found, because rule 2 looks at what the opponent captures, not at the piece that moved; the facts name both.
- The net loss counts material the user wins back inside the window. A knight lost for a pawn is a 2-point loss and fires; a knight lost for two pawns (1 point) does not.
- Moves in already-lost or already-won positions are rarely `MISTAKE` or `BLUNDER` (classification uses winning chances), so a piece hung in a hopeless position is usually not reported.
- Exchanges longer than 8 plies are still cut off; whatever stands after ply 8 is the result.
- Pawn losses and mating attacks need their own motifs.

## Open questions

- **(a) Net loss or captured-piece value?** Rule 3 measures the net change over the window, so material won back reduces the loss: a hung knight can score 2 (one pawn regained) or not fire at all (two pawns regained). The alternative is to measure the value of the piece that is captured first. To be reviewed against real-game findings in Phase 2, when there are enough of them to compare both measures.
- **(b) Hung pieces in already-decided positions.** They are rarely `MISTAKE` or `BLUNDER`, so they produce no finding and are not drilled. Should they still count toward the pattern report ("you hang pieces when the game is already decided" can still be a habit worth showing)? Open; decide together with the pattern-report threshold in Phase 2.
